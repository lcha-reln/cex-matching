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

final class M04EvidenceWriterTest {
  @Test
  void cleanAnnotatedUnitTagPublishesNullProductReleaseAndAllHashes(@TempDir Path temporary)
      throws IOException {
    Lab lab = createLab(temporary, true);
    AtomicInteger freshChecks = new AtomicInteger();
    M04EvidenceWriter writer =
        new M04EvidenceWriter(
            (root, reports) -> {
              freshChecks.incrementAndGet();
              return new M04CheckRunner.Result(M04CheckRunner.PASS, reports.resolve("check.json"));
            });

    M04EvidenceWriter.Result result =
        writer.write(lab.root(), lab.reports(), lab.evidence(), M04EvidenceWriter.UNIT_TAG);

    assertEquals(1, freshChecks.get());
    JsonNode manifest = JsonSupport.parse(Files.readAllBytes(result.manifestPath()));
    assertEquals("M04", manifest.path("unit").stringValue());
    assertTrue(manifest.path("productRelease").isNull());
    JsonNode generated = claim(manifest, "generated-property-suite").path("observations");
    assertEquals("M04H1", generated.path("generator").path("canonicalFormat").stringValue());
    JsonNode boundaries = claim(manifest, "policy-invariants-and-boundaries").path("observations");
    assertEquals(4, boundaries.path("boundaries").path("exactRawPolicyVariants").intValue());
    assertEquals(
        M04EvidenceWriter.REQUIRED_CLAIMS,
        manifest.path("claims").valueStream().map(node -> node.path("id").stringValue()).toList());
    assertEquals(
        M04EvidenceWriter.LIMITATIONS,
        manifest.path("limitations").valueStream().map(JsonNode::stringValue).toList());
    List<String> paths = new ArrayList<>();
    for (JsonNode claim : manifest.path("claims")) {
      for (JsonNode artifact : claim.path("artifacts")) {
        String relative = artifact.path("path").stringValue();
        paths.add(relative);
        assertEquals(
            artifact.path("sha256").stringValue(),
            Hashing.sha256Hex(Files.readAllBytes(lab.evidence().resolve(relative))));
      }
    }
    assertEquals(paths.size(), new LinkedHashSet<>(paths).size());
    assertEquals(M04EvidenceWriter.EXPECTED_ARTIFACT_PATHS, new LinkedHashSet<>(paths));
    assertEquals(
        result.manifestSha256(), Hashing.sha256Hex(Files.readAllBytes(result.manifestPath())));
    assertTrue(git(lab.root(), "status", "--porcelain", "--untracked-files=normal").isBlank());
  }

  @Test
  void dirtyTreeFailsBeforePublishing(@TempDir Path temporary) {
    Lab lab = createLab(temporary, true);
    write(lab.root().resolve("dirty.txt"), "dirty\n");

    IllegalStateException failure =
        assertThrows(
            IllegalStateException.class,
            () ->
                writer()
                    .write(lab.root(), lab.reports(), lab.evidence(), M04EvidenceWriter.UNIT_TAG));

    assertTrue(failure.getMessage().contains("clean working tree"));
    assertFalse(Files.exists(lab.evidence().resolve("manifest.json")));
  }

  @Test
  void lightweightCompletionTagIsRejected(@TempDir Path temporary) {
    Lab lab = createLab(temporary, false);

    IllegalStateException failure =
        assertThrows(
            IllegalStateException.class,
            () ->
                writer()
                    .write(lab.root(), lab.reports(), lab.evidence(), M04EvidenceWriter.UNIT_TAG));

    assertTrue(failure.getMessage().contains("must be annotated"));
    assertFalse(Files.exists(lab.evidence().resolve("manifest.json")));
  }

  @Test
  void productReleaseTagAtM04HeadIsRejected(@TempDir Path temporary) {
    Lab lab = createLab(temporary, true);
    git(lab.root(), "tag", "-a", "matching-9.9.9", "-m", "forbidden M04 product release");

    IllegalStateException failure =
        assertThrows(
            IllegalStateException.class,
            () ->
                writer()
                    .write(lab.root(), lab.reports(), lab.evidence(), M04EvidenceWriter.UNIT_TAG));

    assertTrue(failure.getMessage().contains("must not have a matching-* product release tag"));
    assertFalse(Files.exists(lab.evidence().resolve("manifest.json")));
  }

  @Test
  void missingReportCleansStagingAndPublishesNothing(@TempDir Path temporary) throws IOException {
    Lab lab = createLab(temporary, true);
    Files.delete(lab.reports().resolve("fixed-event-batches.json"));

    assertThrows(
        IllegalStateException.class,
        () ->
            writer().write(lab.root(), lab.reports(), lab.evidence(), M04EvidenceWriter.UNIT_TAG));
    assertFalse(Files.exists(lab.evidence()));
    Path parent = lab.evidence().getParent();
    try (var paths = Files.list(parent)) {
      assertTrue(
          paths.noneMatch(path -> path.getFileName().toString().startsWith(".M04-staging-")));
    }
  }

  private static M04EvidenceWriter writer() {
    return new M04EvidenceWriter(
        (root, reports) ->
            new M04CheckRunner.Result(M04CheckRunner.PASS, reports.resolve("check.json")));
  }

  private static JsonNode claim(JsonNode manifest, String id) {
    return manifest
        .path("claims")
        .valueStream()
        .filter(node -> id.equals(node.path("id").stringValue()))
        .findFirst()
        .orElseThrow();
  }

  private static Lab createLab(Path temporary, boolean annotatedTag) {
    Path root = temporary.resolve("repo");
    Path source = Path.of(System.getProperty("matching.repositoryRoot"));
    copy(
        source.resolve("schemas/cex.lab-evidence.v1.schema.json"),
        root.resolve("schemas/cex.lab-evidence.v1.schema.json"));
    write(root.resolve("schemas/matching.m04.check.v2.schema.json"), relaxedCheckSchema());
    write(
        root.resolve("matching-testkit/src/test/resources/m04/fixtures/execution-policy-v1.json"),
        "{\"schemaVersion\":\"matching.m04.scenario.v1\"}\n");
    write(
        root.resolve("matching-testkit/src/test/resources/m04/fixtures/property-suite-v1.json"),
        "{\"schemaVersion\":\"matching.m04.generator.v1\"}\n");
    write(root.resolve(".gitignore"), "/build/\n");
    write(root.resolve("course.properties"), courseProperties());
    git(root, "init", "-q");
    git(root, "config", "user.name", "M04 Evidence Test");
    git(root, "config", "user.email", "m04-evidence@example.invalid");
    git(root, "config", "commit.gpgsign", "false");
    git(root, "add", ".");
    git(root, "commit", "-q", "-m", "test: freeze M04 evidence inputs");
    if (annotatedTag) {
      git(root, "tag", "-a", M04EvidenceWriter.UNIT_TAG, "-m", "test: M04 complete");
    } else {
      git(root, "tag", M04EvidenceWriter.UNIT_TAG);
    }
    Path reports = root.resolve("build/reports/m04");
    for (String name : M04EvidenceWriter.REPORT_ARTIFACTS) {
      write(
          reports.resolve(name),
          "check.json".equals(name)
              ? check()
              : "{\"status\":\"PASS\",\"artifact\":\"" + name + "\"}\n");
    }
    write(
        reports.resolve("counterexamples-v1.json"),
        "{\"schemaVersion\":\"matching.m04.counterexamples.v1\"}\n");
    return new Lab(root, reports, root.resolve("build/lab-evidence/M04"));
  }

  private static String courseProperties() {
    return """
        case=high-availability-cex
        profile=SPOT-CEX-1.0
        planVersion=0.6
        project=matching
        unit=M04
        lifecycle=CODE_VERIFIED
        designDepth=IMPLEMENTED
        startRef=course/m04-start
        completeRef=course/m04-complete
        m04Check.expectedStatus=PASS
        evidencePath=build/lab-evidence/M04/manifest.json
        """;
  }

  private static String relaxedCheckSchema() {
    return """
        {
          "$schema": "https://json-schema.org/draft/2020-12/schema",
          "type": "object",
          "additionalProperties": true,
          "required": [
            "schemaVersion", "unit", "status", "contractPlanVersion", "inheritedM03",
            "fixedCorpus", "properties", "coverage", "counterexamples", "mutants",
            "architecture", "releaseTarget"
          ],
          "properties": {
            "schemaVersion": { "const": "matching.m04.check.v2" },
            "unit": { "const": "M04" },
            "status": { "const": "PASS" },
            "contractPlanVersion": { "const": "0.6" }
          }
        }
        """;
  }

  private static String check() {
    return """
        {
          "schemaVersion": "matching.m04.check.v2",
          "unit": "M04",
          "status": "PASS",
          "contractPlanVersion": "0.6",
          "inheritedM03": { "status": "PASS", "artifact": "m00-m03-regression.json" },
          "fixedCorpus": { "status": "PASS", "artifact": "fixed-scenario-pack.json" },
          "generator": {
            "canonicalFormat": "M04H1",
            "canonicalDigest": "sha256:6005c674d0c42927989f1c8c4d1ddce224d06ceff0b95bf58615d23c4496ba51",
            "canonicalBytes": 1496773,
            "canonicalLines": 12481
          },
          "properties": { "status": "PASS", "artifact": "generated-properties.json" },
          "coverage": { "status": "PASS", "artifact": "coverage.json" },
          "boundaries": {
            "status": "PASS",
            "artifact": "boundaries.json",
            "exactRawPolicyVariants": 4,
            "exactRawPolicyPaths": 2,
            "longMaxFokDeductionPaths": 2
          },
          "counterexamples": { "status": "PASS", "artifact": "counterexamples.json" },
          "mutants": { "status": "PASS", "artifact": "mutants.json" },
          "architecture": { "status": "PASS", "artifact": "architecture.json" },
          "releaseTarget": {
            "unitTag": "course/m04-complete",
            "productRelease": null,
            "verification": "M04_EVIDENCE_ONLY"
          }
        }
        """;
  }

  private static void copy(Path source, Path target) {
    try {
      Files.createDirectories(target.getParent());
      Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
    } catch (IOException failure) {
      throw new IllegalStateException("cannot copy test file", failure);
    }
  }

  private static void write(Path path, String content) {
    try {
      Files.createDirectories(path.getParent());
      Files.writeString(path, content, StandardCharsets.UTF_8);
    } catch (IOException failure) {
      throw new IllegalStateException("cannot write test file", failure);
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
      int exit = process.waitFor();
      if (exit != 0) {
        throw new IllegalStateException(
            "git failed: " + new String(output, StandardCharsets.UTF_8));
      }
      return new String(output, StandardCharsets.UTF_8);
    } catch (IOException failure) {
      throw new IllegalStateException("cannot execute git", failure);
    } catch (InterruptedException failure) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("git interrupted", failure);
    }
  }

  private record Lab(Path root, Path reports, Path evidence) {}
}
