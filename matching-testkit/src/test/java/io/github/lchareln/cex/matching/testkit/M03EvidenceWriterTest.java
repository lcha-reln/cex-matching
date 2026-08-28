package io.github.lchareln.cex.matching.testkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;

final class M03EvidenceWriterTest {
  @Test
  void cleanRepositoryWithBothAnnotatedTagsProducesExactManifest(@TempDir Path temporary)
      throws IOException {
    LabRepository lab = createLabRepository(temporary, "course/m03-complete", true);
    AtomicInteger freshChecks = new AtomicInteger();
    M03EvidenceWriter writer =
        new M03EvidenceWriter(
            (root, reports) -> {
              freshChecks.incrementAndGet();
              return new M03CheckRunner.Result(M03CheckRunner.PASS, reports.resolve("check.json"));
            });

    M03EvidenceWriter.Result result =
        writer.write(
            lab.root(),
            lab.checkDirectory(),
            lab.evidenceDirectory(),
            lab.unitTag(),
            M03EvidenceWriter.PRODUCT_RELEASE);

    assertEquals(1, freshChecks.get());

    JsonNode manifest = JsonSupport.parse(readBytes(result.manifestPath()));
    assertEquals("M03", manifest.path("unit").stringValue());
    assertEquals("0.5", manifest.path("planVersion").stringValue());
    assertEquals(lab.unitTag(), manifest.path("unitTag").stringValue());
    assertEquals(M03EvidenceWriter.PRODUCT_RELEASE, manifest.path("productRelease").stringValue());
    assertEquals(git(lab.root(), "rev-parse", "HEAD").strip(), result.sourceCommit());
    assertEquals(result.sourceCommit(), manifest.path("source").path("commit").stringValue());
    assertEquals(
        M03EvidenceWriter.REQUIRED_CLAIMS,
        manifest
            .path("claims")
            .valueStream()
            .map(claim -> claim.path("id").stringValue())
            .toList());
    assertEquals(
        M03EvidenceWriter.LIMITATIONS,
        manifest.path("limitations").valueStream().map(JsonNode::stringValue).toList());

    List<String> artifactPaths = new ArrayList<>();
    for (JsonNode claim : manifest.path("claims")) {
      assertEquals(
          "./gradlew m03Check m03Evidence -Pm03.unitTag="
              + lab.unitTag()
              + " -Pm03.productRelease=matching-0.1.0 --no-daemon",
          claim.path("command").stringValue());
      for (JsonNode artifact : claim.path("artifacts")) {
        String relative = artifact.path("path").stringValue();
        artifactPaths.add(relative);
        Path path = lab.evidenceDirectory().resolve(relative);
        assertTrue(Files.isRegularFile(path));
        assertEquals(artifact.path("sha256").stringValue(), Hashing.sha256Hex(readBytes(path)));
      }
    }
    assertEquals(artifactPaths.size(), new LinkedHashSet<>(artifactPaths).size());
    assertEquals(M03EvidenceWriter.EXPECTED_ARTIFACT_PATHS, new LinkedHashSet<>(artifactPaths));
    assertEquals(
        M03EvidenceWriter.REPORT_ARTIFACTS.stream().sorted().toList(),
        listNames(lab.evidenceDirectory().resolve("reports")));
    assertEquals(
        List.of("counterexamples-v1.json", "property-suite-v1.json"),
        listNames(lab.evidenceDirectory().resolve("inputs")));
    assertEquals(Hashing.sha256Hex(readBytes(result.manifestPath())), result.manifestSha256());
    assertTrue(git(lab.root(), "status", "--porcelain", "--untracked-files=normal").isBlank());
  }

  @Test
  void dirtyRepositoryIsRejectedBeforeEvidenceIsWritten(@TempDir Path temporary) {
    LabRepository lab = createLabRepository(temporary, "course/m03-complete", true);
    writeString(lab.root().resolve("dirty.txt"), "not committed\n");

    IllegalStateException failure =
        assertThrows(
            IllegalStateException.class,
            () ->
                writer()
                    .write(
                        lab.root(),
                        lab.checkDirectory(),
                        lab.evidenceDirectory(),
                        lab.unitTag(),
                        M03EvidenceWriter.PRODUCT_RELEASE));

    assertTrue(failure.getMessage().contains("clean working tree"));
    assertFalse(Files.exists(lab.evidenceDirectory().resolve("manifest.json")));
  }

  @Test
  void lightweightProductReleaseTagIsRejected(@TempDir Path temporary) {
    LabRepository lab = createLabRepository(temporary, "course/m03-complete", false);

    IllegalStateException failure =
        assertThrows(
            IllegalStateException.class,
            () ->
                writer()
                    .write(
                        lab.root(),
                        lab.checkDirectory(),
                        lab.evidenceDirectory(),
                        lab.unitTag(),
                        M03EvidenceWriter.PRODUCT_RELEASE));

    assertTrue(failure.getMessage().contains("product release tag must be annotated"));
    assertFalse(Files.exists(lab.evidenceDirectory().resolve("manifest.json")));
  }

  @Test
  void productReleaseArgumentIsFrozen(@TempDir Path temporary) {
    LabRepository lab = createLabRepository(temporary, "course/m03-complete", true);

    IllegalStateException failure =
        assertThrows(
            IllegalStateException.class,
            () ->
                writer()
                    .write(
                        lab.root(),
                        lab.checkDirectory(),
                        lab.evidenceDirectory(),
                        lab.unitTag(),
                        "matching-0.1.1"));

    assertTrue(failure.getMessage().contains("invalid M03 product release tag"));
  }

  @Test
  void missingCounterexampleInputFailsClosed(@TempDir Path temporary) throws IOException {
    LabRepository lab = createLabRepository(temporary, "course/m03-complete", true);
    Files.delete(lab.checkDirectory().resolve("counterexamples-v1.json"));

    IllegalStateException failure =
        assertThrows(
            IllegalStateException.class,
            () ->
                writer()
                    .write(
                        lab.root(),
                        lab.checkDirectory(),
                        lab.evidenceDirectory(),
                        lab.unitTag(),
                        M03EvidenceWriter.PRODUCT_RELEASE));

    assertTrue(failure.getMessage().contains("missing M03 counterexample input"));
    assertFalse(Files.exists(lab.evidenceDirectory().resolve("manifest.json")));
  }

  @Test
  void symlinkedEvidenceParentCannotRedirectPublishedArtifacts(@TempDir Path temporary)
      throws IOException {
    LabRepository lab = createLabRepository(temporary, "course/m03-complete", true);
    Path outside = Files.createDirectories(temporary.resolve("outside-evidence"));
    Path labEvidence = lab.root().resolve("build/lab-evidence");
    Files.createDirectories(labEvidence.getParent());
    Files.createSymbolicLink(labEvidence, outside);

    assertThrows(
        IllegalStateException.class,
        () ->
            writer()
                .write(
                    lab.root(),
                    lab.checkDirectory(),
                    lab.evidenceDirectory(),
                    lab.unitTag(),
                    M03EvidenceWriter.PRODUCT_RELEASE));
    try (var files = Files.list(outside)) {
      assertTrue(files.findAny().isEmpty());
    }
  }

  private static LabRepository createLabRepository(
      Path temporary, String unitTag, boolean annotatedProductRelease) {
    Path root = temporary.resolve("repo");
    Path sourceRoot = Path.of(System.getProperty("matching.repositoryRoot"));
    copy(
        sourceRoot.resolve("schemas/cex.lab-evidence.v1.schema.json"),
        root.resolve("schemas/cex.lab-evidence.v1.schema.json"));
    writeString(root.resolve("schemas/matching.m03.check.v2.schema.json"), m03CheckSchema());
    writeString(
        root.resolve("matching-testkit/src/test/resources/m03/fixtures/property-suite-v1.json"),
        "{\"schemaVersion\":\"matching.m03.generator.v1\"}\n");
    writeString(root.resolve(".gitignore"), "/build/\n");
    writeString(root.resolve("course.properties"), courseProperties(unitTag));
    git(root, "init", "-q");
    git(root, "config", "user.name", "M03 Evidence Test");
    git(root, "config", "user.email", "m03-evidence@example.invalid");
    git(root, "config", "commit.gpgsign", "false");
    git(root, "add", ".");
    git(root, "commit", "-q", "-m", "test: freeze M03 evidence inputs");
    git(root, "tag", "-a", unitTag, "-m", "test: annotated M03 completion");
    if (annotatedProductRelease) {
      git(
          root,
          "tag",
          "-a",
          M03EvidenceWriter.PRODUCT_RELEASE,
          "-m",
          "test: annotated matching release");
    } else {
      git(root, "tag", M03EvidenceWriter.PRODUCT_RELEASE);
    }

    Path checkDirectory = root.resolve("build/reports/m03");
    for (String name : M03EvidenceWriter.REPORT_ARTIFACTS) {
      String content =
          "check.json".equals(name)
              ? m03Check(unitTag)
              : "counterexamples.canonical.utf8".equals(name)
                  ? "M03X1|test-counterexample\n"
                  : "{\"status\":\"PASS\",\"artifact\":\"" + name + "\"}\n";
      writeString(checkDirectory.resolve(name), content);
    }
    writeString(
        checkDirectory.resolve("counterexamples-v1.json"),
        "{\"schemaVersion\":\"matching.m03.counterexamples.v1\",\"scenarios\":[]}\n");
    return new LabRepository(root, checkDirectory, root.resolve("build/lab-evidence/M03"), unitTag);
  }

  private static M03EvidenceWriter writer() {
    return new M03EvidenceWriter(
        (root, reports) ->
            new M03CheckRunner.Result(M03CheckRunner.PASS, reports.resolve("check.json")));
  }

  private static String courseProperties(String unitTag) {
    return """
        case=high-availability-cex
        profile=SPOT-CEX-1.0
        planVersion=0.5
        project=matching
        unit=M03
        lifecycle=CODE_VERIFIED
        designDepth=IMPLEMENTED
        startRef=course/m03-start
        completeRef=%s
        productRelease=matching-0.1.0
        m03Check.expectedStatus=PASS
        evidencePath=build/lab-evidence/M03/manifest.json
        """
        .formatted(unitTag);
  }

  private static String m03CheckSchema() {
    return """
        {
          "$schema": "https://json-schema.org/draft/2020-12/schema",
          "type": "object",
          "additionalProperties": true,
          "required": [
            "schemaVersion", "unit", "status", "contractPlanVersion", "generator",
            "m02Regression", "independence", "properties", "determinism",
            "counterexamples", "mutants", "architecture", "releaseTarget"
          ],
          "properties": {
            "schemaVersion": { "const": "matching.m03.check.v2" },
            "unit": { "const": "M03" },
            "status": { "const": "PASS" },
            "contractPlanVersion": { "const": "0.5" }
          }
        }
        """;
  }

  private static String m03Check(String unitTag) {
    return """
        {
          "schemaVersion": "matching.m03.check.v2",
          "unit": "M03",
          "status": "PASS",
          "contractPlanVersion": "0.5",
          "generator": { "algorithm": "splitmix64-v1", "histories": 256, "commands": 16384 },
          "m02Regression": { "status": "PASS", "artifact": "m00-m02-regression.json" },
          "independence": { "status": "PASS", "artifact": "reference-model.json" },
          "properties": { "status": "PASS", "artifact": "generated-properties.json" },
          "determinism": { "generations": 2, "distinctCommandDigests": 1 },
          "counterexamples": { "status": "PASS", "artifact": "counterexamples.json" },
          "mutants": { "status": "PASS", "artifact": "mutants.json" },
          "architecture": { "status": "PASS", "artifact": "architecture.json" },
          "releaseTarget": {
            "unitTag": "%s",
            "productRelease": "matching-0.1.0",
            "verification": "M03_EVIDENCE_ONLY"
          }
        }
        """
        .formatted(unitTag);
  }

  private static void copy(Path source, Path target) {
    try {
      Files.createDirectories(target.getParent());
      Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
    } catch (IOException exception) {
      throw new IllegalStateException("cannot copy test input " + source, exception);
    }
  }

  private static void writeString(Path path, String content) {
    try {
      Files.createDirectories(path.getParent());
      Files.writeString(path, content, StandardCharsets.UTF_8);
    } catch (IOException exception) {
      throw new IllegalStateException("cannot write test input " + path, exception);
    }
  }

  private static byte[] readBytes(Path path) {
    try {
      return Files.readAllBytes(path);
    } catch (IOException exception) {
      throw new IllegalStateException("cannot read " + path, exception);
    }
  }

  private static List<String> listNames(Path directory) throws IOException {
    try (var paths = Files.list(directory)) {
      return paths.map(path -> path.getFileName().toString()).sorted().toList();
    }
  }

  private static String git(Path root, String... arguments) {
    List<String> command = new ArrayList<>();
    command.add("git");
    command.add("-C");
    command.add(root.toString());
    command.addAll(List.of(arguments));
    try {
      Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
      byte[] output = process.getInputStream().readAllBytes();
      int exitCode = process.waitFor();
      if (exitCode != 0) {
        throw new IllegalStateException(
            "git command failed: " + new String(output, StandardCharsets.UTF_8));
      }
      return new String(output, StandardCharsets.UTF_8);
    } catch (IOException exception) {
      throw new IllegalStateException("cannot execute git", exception);
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("git command was interrupted", exception);
    }
  }

  private record LabRepository(
      Path root, Path checkDirectory, Path evidenceDirectory, String unitTag) {}
}
