package io.github.lchareln.cex.matching.testkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.BooleanNode;
import tools.jackson.databind.node.LongNode;
import tools.jackson.databind.node.NullNode;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.node.StringNode;

final class M11EvidenceWriterTest {
  private static final String EVIDENCE_SCHEMA = "schemas/cex.lab-evidence.v2.schema.json";

  @Test
  void cleanAnnotatedTopologyPublishesTheDynamicInventoryExactlyOnce(@TempDir Path temporary) {
    Lab lab = createLab(temporary, Topology.VALID);
    AtomicInteger checks = new AtomicInteger();
    M11EvidenceWriter writer =
        new M11EvidenceWriter(
            (root, reports) -> {
              checks.incrementAndGet();
              return new M11CheckRunner.Result(M11CheckRunner.PASS, reports.resolve("check.json"));
            });

    M11EvidenceWriter.Result result =
        writer.write(lab.root(), lab.checks(), lab.evidence(), M11EvidenceWriter.UNIT_TAG);

    assertEquals(1, checks.get());
    assertEquals(M11CheckRunner.OUTPUTS, M11EvidenceWriter.REPORT_ARTIFACTS);
    JsonNode manifest = JsonSupport.parse(read(result.manifestPath()));
    assertEquals("cex.lab-evidence.v2", manifest.path("schemaVersion").stringValue());
    assertEquals("M11", manifest.path("unit").stringValue());
    assertTrue(manifest.path("productRelease").isNull());
    assertEquals(lab.sourceCommit(), manifest.path("source").path("commit").stringValue());
    assertEquals(
        M11EvidenceWriter.REQUIRED_CLAIMS,
        manifest.path("claims").valueStream().map(node -> node.path("id").stringValue()).toList());
    assertEquals(
        M11EvidenceWriter.LIMITATIONS,
        manifest.path("limitations").valueStream().map(JsonNode::stringValue).toList());

    List<String> bound = new ArrayList<>();
    for (JsonNode claim : manifest.path("claims")) {
      for (JsonNode artifact : claim.path("artifacts")) {
        String relative = artifact.path("path").stringValue();
        bound.add(relative);
        assertEquals(
            artifact.path("sha256").stringValue(),
            Hashing.sha256Hex(read(lab.evidence().resolve(relative))));
      }
    }
    Set<String> expected = expectedEvidencePaths(lab);
    Set<String> expectedSources = new LinkedHashSet<>(expected);
    expectedSources.remove("manifest.json");
    assertEquals(bound.size(), new LinkedHashSet<>(bound).size());
    assertEquals(expectedSources, new LinkedHashSet<>(bound));
    assertEquals(expectedSources.size(), result.artifactCount());
    assertEquals(expected, fileInventory(lab.evidence()));
    for (String report : M11CheckRunner.OUTPUTS) {
      assertTrue(expected.contains("reports/check/" + report));
    }
    assertEquals(result.manifestSha256(), Hashing.sha256Hex(read(result.manifestPath())));
    assertEquals(
        realPath(lab.clusterRoot()).toString(),
        manifest.path("environment").path("walRoot").stringValue());

    JsonNode check = JsonSupport.parse(read(lab.checks().resolve("check.json")));
    JsonNode clusterClaim = claim(manifest, "single-node-clustered-service");
    assertEquals(
        check.path("clusterRuntime"), clusterClaim.path("observations").path("clusterRuntime"));
  }

  @Test
  void inheritedCourseAndProductTagsMustPeelToTheSameCommit(@TempDir Path temporary) {
    Lab lab = createLab(temporary, Topology.MISMATCHED_INHERITED_TAGS);
    AtomicInteger checks = new AtomicInteger();

    IllegalStateException failure =
        assertThrows(
            IllegalStateException.class,
            () ->
                writer(checks)
                    .write(lab.root(), lab.checks(), lab.evidence(), M11EvidenceWriter.UNIT_TAG));

    assertTrue(failure.getMessage().contains("do not identify the same baseline"));
    assertEquals(0, checks.get());
    assertFalse(Files.exists(lab.evidence(), LinkOption.NOFOLLOW_LINKS));
  }

  @Test
  void inheritedM10MustPrecedeTheM11StartBoundary(@TempDir Path temporary) {
    Lab lab = createLab(temporary, Topology.INHERITED_AFTER_START);
    AtomicInteger checks = new AtomicInteger();

    IllegalStateException failure =
        assertThrows(
            IllegalStateException.class,
            () ->
                writer(checks)
                    .write(lab.root(), lab.checks(), lab.evidence(), M11EvidenceWriter.UNIT_TAG));

    assertTrue(failure.getMessage().contains("is not an ancestor of course/m11-start"));
    assertEquals(0, checks.get());
    assertFalse(Files.exists(lab.evidence(), LinkOption.NOFOLLOW_LINKS));
  }

  @Test
  void sourceArtifactMutationAtEitherBoundaryLeavesNoEvidence(@TempDir Path temporary) {
    for (MutationBoundary boundary : MutationBoundary.values()) {
      Lab lab = createLab(temporary.resolve(boundary.name().toLowerCase()), Topology.VALID);
      Path sourceArtifact = lab.checks().resolve(M11EvidenceWriter.REPORT_ARTIFACTS.getFirst());
      M11EvidenceWriter writer =
          new M11EvidenceWriter(
              checkExecutor(),
              new M11EvidenceWriter.BoundaryHook() {
                @Override
                public void beforeFinalVerification(Path root) {
                  if (boundary == MutationBoundary.STAGING) {
                    mutate(sourceArtifact, boundary);
                  }
                }

                @Override
                public void beforePostPublishVerification(Path root) {
                  if (boundary == MutationBoundary.POST_PUBLISH) {
                    mutate(sourceArtifact, boundary);
                  }
                }
              });

      IllegalStateException failure =
          assertThrows(
              IllegalStateException.class,
              () ->
                  writer.write(
                      lab.root(), lab.checks(), lab.evidence(), M11EvidenceWriter.UNIT_TAG));

      assertTrue(failure.getMessage().contains("artifact"));
      assertFalse(Files.exists(lab.evidence(), LinkOption.NOFOLLOW_LINKS));
    }
  }

  @ParameterizedTest(name = "{0}")
  @EnumSource(ManifestTamper.class)
  void exactProjectionRejectsSchemaValidManifestTampering(
      ManifestTamper tamper, @TempDir Path temporary) {
    Lab lab = createLab(temporary, Topology.VALID);
    M11EvidenceWriter writer =
        new M11EvidenceWriter(
            checkExecutor(),
            new M11EvidenceWriter.BoundaryHook() {
              @Override
              public void beforeFinalVerification(Path root) {
                Path manifestPath = stagingManifest(root);
                ObjectNode manifest = (ObjectNode) JsonSupport.parse(read(manifestPath));
                tamper.apply(manifest);
                write(manifestPath, JsonSupport.prettyBytes(manifest));
              }

              @Override
              public void beforePostPublishVerification(Path root) {}
            });

    IllegalStateException failure =
        assertThrows(
            IllegalStateException.class,
            () ->
                writer.write(lab.root(), lab.checks(), lab.evidence(), M11EvidenceWriter.UNIT_TAG));

    assertTrue(failure.getMessage().contains("exact projection"));
    assertFalse(Files.exists(lab.evidence(), LinkOption.NOFOLLOW_LINKS));
  }

  private static M11EvidenceWriter writer(AtomicInteger checks) {
    return new M11EvidenceWriter(
        (root, reports) -> {
          checks.incrementAndGet();
          return new M11CheckRunner.Result(M11CheckRunner.PASS, reports.resolve("check.json"));
        });
  }

  private static M11EvidenceWriter.CheckExecutor checkExecutor() {
    return (root, reports) ->
        new M11CheckRunner.Result(M11CheckRunner.PASS, reports.resolve("check.json"));
  }

  private static void mutate(Path sourceArtifact, MutationBoundary boundary) {
    write(
        sourceArtifact,
        ("mutated M11 source artifact at " + boundary + "\n").getBytes(StandardCharsets.UTF_8));
  }

  private static Lab createLab(Path temporary, Topology topology) {
    Path root = temporary.resolve("repo");
    Path source = Path.of(System.getProperty("matching.repositoryRoot"));
    copy(source.resolve(EVIDENCE_SCHEMA), root.resolve(EVIDENCE_SCHEMA));
    copy(
        source.resolve(M11CheckRunner.CHECK_SCHEMA_PATH),
        root.resolve(M11CheckRunner.CHECK_SCHEMA_PATH));
    copy(
        source.resolve(M11CheckRunner.COUNTEREXAMPLE_SCHEMA_PATH),
        root.resolve(M11CheckRunner.COUNTEREXAMPLE_SCHEMA_PATH));
    copy(
        source.resolve(M11StartCheckRunner.WORKLOAD_SCHEMA_PATH),
        root.resolve(M11StartCheckRunner.WORKLOAD_SCHEMA_PATH));
    copy(
        source.resolve(M11StartCheckRunner.WORKLOAD_PATH),
        root.resolve(M11StartCheckRunner.WORKLOAD_PATH));
    JsonNode workload = JsonSupport.parse(read(root.resolve(M11StartCheckRunner.WORKLOAD_PATH)));
    for (JsonNode golden : workload.path("goldenFixtures")) {
      Path relative = Path.of(golden.path("path").stringValue());
      copy(source.resolve(relative), root.resolve(relative));
    }
    write(root.resolve(".gitignore"), "/build/\n".getBytes(StandardCharsets.UTF_8));
    write(root.resolve("course.properties"), course().getBytes(StandardCharsets.UTF_8));

    git(root, "init", "-q");
    git(root, "config", "user.name", "M11 Evidence Test");
    git(root, "config", "user.email", "m11-evidence@example.invalid");
    git(root, "config", "commit.gpgsign", "false");
    git(root, "config", "tag.gpgSign", "false");
    git(root, "add", ".");
    git(root, "commit", "-q", "-m", "test: freeze M11 evidence inputs");
    createTopology(root, topology);

    String sourceCommit = git(root, "rev-parse", "HEAD").strip();
    String inheritedCommit = git(root, "rev-parse", "course/m10-complete^{}").strip();
    Path clusterRoot = root.resolve("build/tmp/m11");
    createDirectories(clusterRoot);
    Path checks = root.resolve("build/reports/m11");
    for (String name : M11CheckRunner.OUTPUTS) {
      byte[] bytes =
          name.endsWith(".json")
              ? ("{\"schemaVersion\":\"m11-test-artifact\",\"name\":\"" + name + "\"}\n")
                  .getBytes(StandardCharsets.UTF_8)
              : ("M11 test artifact " + name + "\n").getBytes(StandardCharsets.UTF_8);
      write(checks.resolve(name), bytes);
    }
    write(
        checks.resolve("check.json"),
        JsonSupport.prettyBytes(check(root, checks, sourceCommit, inheritedCommit, clusterRoot)));
    return new Lab(root, checks, root.resolve("build/lab-evidence/M11"), clusterRoot, sourceCommit);
  }

  private static void createTopology(Path root, Topology topology) {
    switch (topology) {
      case VALID -> {
        annotatedTag(root, "course/m10-complete");
        annotatedTag(root, "matching-0.5.0");
        emptyCommit(root, "test: M11 start");
        annotatedTag(root, "course/m11-start");
        emptyCommit(root, "test: M11 complete");
        annotatedTag(root, M11EvidenceWriter.UNIT_TAG);
      }
      case MISMATCHED_INHERITED_TAGS -> {
        annotatedTag(root, "course/m10-complete");
        emptyCommit(root, "test: distinct product baseline");
        annotatedTag(root, "matching-0.5.0");
        emptyCommit(root, "test: M11 start");
        annotatedTag(root, "course/m11-start");
        emptyCommit(root, "test: M11 complete");
        annotatedTag(root, M11EvidenceWriter.UNIT_TAG);
      }
      case INHERITED_AFTER_START -> {
        annotatedTag(root, "course/m11-start");
        emptyCommit(root, "test: inherited baseline after M11 start");
        annotatedTag(root, "course/m10-complete");
        annotatedTag(root, "matching-0.5.0");
        emptyCommit(root, "test: M11 complete");
        annotatedTag(root, M11EvidenceWriter.UNIT_TAG);
      }
    }
  }

  private static void emptyCommit(Path root, String message) {
    git(root, "commit", "-q", "--allow-empty", "-m", message);
  }

  private static void annotatedTag(Path root, String tag) {
    git(root, "tag", "-a", tag, "-m", "test: " + tag);
  }

  private static ObjectNode check(
      Path root, Path reports, String sourceCommit, String inheritedCommit, Path clusterRoot) {
    JsonNode schema = JsonSupport.parse(read(root.resolve(M11CheckRunner.CHECK_SCHEMA_PATH)));
    ObjectNode check = (ObjectNode) materialize(schema, schema, "root", 0);
    ((ObjectNode) check.path("source")).put("commit", sourceCommit).put("dirty", false);
    ((ObjectNode) check.path("inheritedM10")).put("baselineCommit", inheritedCommit);
    ObjectNode architecture = (ObjectNode) check.path("architecture");
    architecture.put("m10CoreTree", inheritedCommit);
    architecture.put("headCoreTree", inheritedCommit);

    ObjectNode environment = (ObjectNode) check.path("environment");
    environment.put("javaRuntime", "test-runtime");
    environment.put("javaVersion", "25-test");
    environment.put("javaVendor", "test-vendor");
    environment.put("vmName", "test-vm");
    ((ArrayNode) environment.path("jvmArguments")).removeAll().add("-Dm11.evidence.test=true");
    environment.put("osName", "test-os");
    environment.put("osVersion", "1");
    environment.put("osArchitecture", "test-arch");
    environment.put("availableProcessors", 1);
    environment.put("maximumHeapBytes", 1);
    environment.put("clusterRoot", clusterRoot.toAbsolutePath().normalize().toString());
    environment.put("fileStoreName", "test-store");
    environment.put("fileStoreType", "test-type");
    environment.put("runStartedAt", "2026-09-03T00:00:00Z");
    environment.put("runFinishedAt", "2026-09-03T00:00:01Z");
    enrichEnvironmentWhenSupported(environment, clusterRoot);

    ArrayNode bindings = (ArrayNode) check.path("artifactBindings");
    bindings.removeAll();
    for (String name : M11CheckRunner.OUTPUTS) {
      byte[] bytes = read(reports.resolve(name));
      bindings
          .addObject()
          .put("path", name)
          .put("sha256", Hashing.sha256Hex(bytes))
          .put("bytes", bytes.length);
    }
    return check;
  }

  private static void enrichEnvironmentWhenSupported(ObjectNode environment, Path clusterRoot) {
    if (!environment.has("physicalMemoryBytes")) return;
    environment.put("physicalMemoryBytes", 8_589_934_592L);
    ((ArrayNode) environment.path("garbageCollectorNames")).removeAll().add("Test GC");
    environment.put("cpuModel", "test-cpu");
    environment.put("storageDevice", "test-storage");
    environment.put("filesystem", "test-filesystem");
    environment.put("powerPolicy", "test-power");
    Path normalized = clusterRoot.toAbsolutePath().normalize();
    environment.put("walRoot", normalized.toString());
    environment.put("walRootUri", normalized.toUri().toASCIIString());
    environment.put("walFileStoreName", "test-store");
    environment.put("walFileStoreType", "test-type");
    environment.put("walFileStoreTotalSpaceBytes", 1_000_000L);
    environment.put("walFileStoreUsableSpaceBytes", 700_000L);
    environment.put("walFileStoreUnallocatedSpaceBytes", 800_000L);
  }

  private static JsonNode materialize(
      JsonNode rootSchema, JsonNode schema, String field, int ordinal) {
    if (schema.has("$ref")) {
      String reference = schema.path("$ref").stringValue();
      if (reference == null || !reference.startsWith("#/")) {
        throw new IllegalStateException("unsupported M11 check schema reference: " + reference);
      }
      return materialize(rootSchema, rootSchema.at(reference.substring(1)), field, ordinal);
    }
    if (schema.has("allOf")) {
      ObjectNode merged = JsonSupport.MAPPER.createObjectNode();
      for (JsonNode part : schema.path("allOf")) {
        JsonNode value = materialize(rootSchema, part, field, ordinal);
        if (!(value instanceof ObjectNode object)) {
          throw new IllegalStateException("M11 check allOf fixture is not an object");
        }
        merged.setAll(object);
      }
      return merged;
    }
    if (schema.has("oneOf")) {
      return materialize(rootSchema, schema.path("oneOf").get(0), field, ordinal);
    }
    if (schema.has("const")) {
      return schema.path("const").deepCopy();
    }
    if (schema.has("enum")) {
      return schema.path("enum").get(0).deepCopy();
    }
    String type = schema.path("type").stringValue();
    if ("object".equals(type)) {
      ObjectNode object = JsonSupport.MAPPER.createObjectNode();
      for (JsonNode required : schema.path("required")) {
        String name = required.stringValue();
        object.set(
            name, materialize(rootSchema, schema.path("properties").path(name), name, ordinal));
      }
      return object;
    }
    if ("array".equals(type)) {
      ArrayNode array = JsonSupport.MAPPER.createArrayNode();
      int minimum =
          schema.path("minItems").isIntegralNumber() ? schema.path("minItems").intValue() : 0;
      for (int index = 0; index < minimum; index++) {
        array.add(materialize(rootSchema, schema.path("items"), field, index));
      }
      return array;
    }
    if ("string".equals(type)) {
      String pattern = schema.has("pattern") ? schema.path("pattern").stringValue() : null;
      if (pattern != null && pattern.contains("{64}")) return StringNode.valueOf("a".repeat(64));
      if (pattern != null && pattern.contains("{40}")) return StringNode.valueOf("b".repeat(40));
      if (schema.has("format") && "date-time".equals(schema.path("format").stringValue())) {
        return StringNode.valueOf("2026-09-03T00:00:00Z");
      }
      return StringNode.valueOf("path".equals(field) ? "artifact-" + ordinal : "value");
    }
    if ("integer".equals(type)) {
      long minimum =
          schema.path("minimum").isIntegralNumber() ? schema.path("minimum").longValue() : 0;
      return LongNode.valueOf(minimum);
    }
    if ("boolean".equals(type)) return BooleanNode.FALSE;
    if ("null".equals(type)) return NullNode.getInstance();
    throw new IllegalStateException(
        "unsupported M11 check schema fixture at " + field + ": " + schema);
  }

  private static Set<String> expectedEvidencePaths(Lab lab) {
    Set<String> expected = new LinkedHashSet<>();
    expected.add("inputs/workload-v1.json");
    JsonNode workload =
        JsonSupport.parse(read(lab.root().resolve(M11StartCheckRunner.WORKLOAD_PATH)));
    for (JsonNode golden : workload.path("goldenFixtures")) {
      expected.add("inputs/goldens/" + Path.of(golden.path("path").stringValue()).getFileName());
    }
    for (String schema :
        List.of(
            M11StartCheckRunner.WORKLOAD_SCHEMA_PATH,
            M11CheckRunner.CHECK_SCHEMA_PATH,
            M11CheckRunner.COUNTEREXAMPLE_SCHEMA_PATH,
            EVIDENCE_SCHEMA)) {
      expected.add("schemas/" + Path.of(schema).getFileName());
    }
    M11CheckRunner.OUTPUTS.forEach(name -> expected.add("reports/check/" + name));
    expected.add("reports/check/check.json");
    expected.add("manifest.json");
    return expected;
  }

  private static JsonNode claim(JsonNode manifest, String id) {
    return manifest
        .path("claims")
        .valueStream()
        .filter(value -> id.equals(value.path("id").stringValue()))
        .findFirst()
        .orElseThrow();
  }

  private static Set<String> fileInventory(Path root) {
    try (var paths = Files.walk(root)) {
      return new LinkedHashSet<>(
          paths
              .filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
              .map(root::relativize)
              .map(path -> path.toString().replace(java.io.File.separatorChar, '/'))
              .sorted()
              .toList());
    } catch (IOException failure) {
      throw new IllegalStateException("cannot inventory fake M11 evidence", failure);
    }
  }

  private static Path stagingManifest(Path root) {
    Path parent = root.resolve("build/lab-evidence");
    try (var paths = Files.list(parent)) {
      return paths
          .filter(path -> path.getFileName().toString().startsWith(".M11-staging-"))
          .map(path -> path.resolve("manifest.json"))
          .filter(Files::isRegularFile)
          .findFirst()
          .orElseThrow(() -> new IllegalStateException("M11 staging manifest is missing"));
    } catch (IOException failure) {
      throw new IllegalStateException("cannot locate M11 staging manifest", failure);
    }
  }

  private static String course() {
    return """
    case=high-availability-cex
    profile=SPOT-CEX-1.0
    planVersion=0.14
    project=matching
    unit=M11
    lifecycle=COMPLETE
    designDepth=IMPLEMENTED
    startRef=course/m11-start
    completeRef=course/m11-complete
    m11Check.expectedStatus=PASS
    evidencePath=build/lab-evidence/M11/manifest.json
    """;
  }

  private static byte[] read(Path path) {
    try {
      return Files.readAllBytes(path);
    } catch (IOException failure) {
      throw new IllegalStateException("cannot read M11 evidence test artifact", failure);
    }
  }

  private static Path realPath(Path path) {
    try {
      return path.toRealPath();
    } catch (IOException failure) {
      throw new IllegalStateException("cannot resolve M11 evidence test path", failure);
    }
  }

  private static void copy(Path source, Path target) {
    try {
      Files.createDirectories(target.getParent());
      Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
    } catch (IOException failure) {
      throw new IllegalStateException("cannot copy M11 evidence test input", failure);
    }
  }

  private static void write(Path path, byte[] bytes) {
    try {
      Files.createDirectories(path.getParent());
      Files.write(path, bytes);
    } catch (IOException failure) {
      throw new IllegalStateException("cannot write M11 evidence test artifact", failure);
    }
  }

  private static void createDirectories(Path path) {
    try {
      Files.createDirectories(path);
    } catch (IOException failure) {
      throw new IllegalStateException("cannot create M11 evidence test directory", failure);
    }
  }

  private static String git(Path root, String... arguments) {
    try {
      Files.createDirectories(root);
      List<String> command = new ArrayList<>();
      command.add("git");
      command.addAll(List.of(arguments));
      Process process = new ProcessBuilder(command).directory(root.toFile()).start();
      String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
      String error = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
      int exit = process.waitFor();
      if (exit != 0) throw new IllegalStateException("git failed: " + error);
      return output;
    } catch (IOException | InterruptedException failure) {
      if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
      throw new IllegalStateException("cannot run git in M11 evidence test", failure);
    }
  }

  private enum Topology {
    VALID,
    MISMATCHED_INHERITED_TAGS,
    INHERITED_AFTER_START
  }

  private enum MutationBoundary {
    STAGING,
    POST_PUBLISH
  }

  private enum ManifestTamper {
    OBSERVATION {
      @Override
      void apply(ObjectNode manifest) {
        ((ObjectNode) manifest.path("claims").path(0).path("observations")).put("tampered", true);
      }
    },
    ENVIRONMENT {
      @Override
      void apply(ObjectNode manifest) {
        ObjectNode environment = (ObjectNode) manifest.path("environment");
        environment.put("maximumHeapBytes", environment.path("maximumHeapBytes").longValue() + 1L);
      }
    },
    STATEMENT {
      @Override
      void apply(ObjectNode manifest) {
        ((ObjectNode) manifest.path("claims").path(0)).put("statement", "tampered statement");
      }
    };

    abstract void apply(ObjectNode manifest);
  }

  private record Lab(
      Path root, Path checks, Path evidence, Path clusterRoot, String sourceCommit) {}
}
