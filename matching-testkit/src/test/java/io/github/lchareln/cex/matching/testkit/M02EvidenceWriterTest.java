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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;

final class M02EvidenceWriterTest {
  @Test
  void annotatedCleanRepositoryProducesExactManifestAndHashes(@TempDir Path temporary)
      throws IOException {
    LabRepository lab = createLabRepository(temporary, "course/m02.1-complete", true);

    M02EvidenceWriter.Result result =
        new M02EvidenceWriter()
            .write(lab.root(), lab.checkDirectory(), lab.evidenceDirectory(), lab.unitTag());

    JsonNode manifest = JsonSupport.parse(readBytes(result.manifestPath()));
    assertEquals("M02", manifest.path("unit").stringValue());
    assertEquals("0.4", manifest.path("planVersion").stringValue());
    assertEquals(lab.unitTag(), manifest.path("unitTag").stringValue());
    assertTrue(manifest.path("productRelease").isNull());
    assertTrue(result.sourceCommit().matches("^[a-f0-9]{40}(?:[a-f0-9]{24})?$"));
    assertEquals(git(lab.root(), "rev-parse", "HEAD").strip(), result.sourceCommit());
    assertEquals(result.sourceCommit(), manifest.path("source").path("commit").stringValue());
    assertEquals(
        M02EvidenceWriter.REQUIRED_CLAIMS,
        manifest
            .path("claims")
            .valueStream()
            .map(claim -> claim.path("id").stringValue())
            .toList());
    assertEquals(
        M02EvidenceWriter.LIMITATIONS,
        manifest.path("limitations").valueStream().map(JsonNode::stringValue).toList());

    List<String> artifactPaths = new ArrayList<>();
    for (JsonNode claim : manifest.path("claims")) {
      assertEquals(
          "./gradlew m02Check m02Evidence -Pm02.unitTag=" + lab.unitTag() + " --no-daemon",
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
    assertEquals(M02EvidenceWriter.EXPECTED_ARTIFACT_PATHS, new LinkedHashSet<>(artifactPaths));
    assertEquals(
        M02EvidenceWriter.REPORT_ARTIFACTS.stream().sorted().toList(),
        listNames(lab.evidenceDirectory().resolve("reports")));
    assertEquals(
        List.of("order-lifecycle-v1.json"), listNames(lab.evidenceDirectory().resolve("inputs")));
    assertEquals(Hashing.sha256Hex(readBytes(result.manifestPath())), result.manifestSha256());
    assertTrue(git(lab.root(), "status", "--porcelain", "--untracked-files=normal").isBlank());
  }

  @Test
  void dirtyRepositoryIsRejectedBeforeEvidenceIsWritten(@TempDir Path temporary) {
    LabRepository lab = createLabRepository(temporary, "course/m02-complete", true);
    writeString(lab.root().resolve("dirty.txt"), "not committed\n");

    IllegalStateException failure =
        assertThrows(
            IllegalStateException.class,
            () ->
                new M02EvidenceWriter()
                    .write(
                        lab.root(), lab.checkDirectory(), lab.evidenceDirectory(), lab.unitTag()));

    assertTrue(failure.getMessage().contains("clean working tree"));
    assertFalse(Files.exists(lab.evidenceDirectory().resolve("manifest.json")));
  }

  @Test
  void lightweightCompleteTagIsRejected(@TempDir Path temporary) {
    LabRepository lab = createLabRepository(temporary, "course/m02-complete", false);

    IllegalStateException failure =
        assertThrows(
            IllegalStateException.class,
            () ->
                new M02EvidenceWriter()
                    .write(
                        lab.root(), lab.checkDirectory(), lab.evidenceDirectory(), lab.unitTag()));

    assertTrue(failure.getMessage().contains("annotated"));
    assertFalse(Files.exists(lab.evidenceDirectory().resolve("manifest.json")));
  }

  @Test
  void annotatedCompleteTagMustPeelToHead(@TempDir Path temporary) {
    LabRepository lab = createLabRepository(temporary, "course/m02-complete", true);
    writeString(lab.root().resolve("after-tag.txt"), "later commit\n");
    git(lab.root(), "add", "after-tag.txt");
    git(lab.root(), "commit", "-q", "-m", "test: move HEAD after tag");

    IllegalStateException failure =
        assertThrows(
            IllegalStateException.class,
            () ->
                new M02EvidenceWriter()
                    .write(
                        lab.root(), lab.checkDirectory(), lab.evidenceDirectory(), lab.unitTag()));

    assertTrue(failure.getMessage().contains("peel to HEAD"));
    assertFalse(Files.exists(lab.evidenceDirectory().resolve("manifest.json")));
  }

  @Test
  void completeTagMustMatchTheCommittedCourseContract(@TempDir Path temporary) {
    LabRepository lab = createLabRepository(temporary, "course/m02-complete", true);

    IllegalStateException failure =
        assertThrows(
            IllegalStateException.class,
            () ->
                new M02EvidenceWriter()
                    .write(
                        lab.root(),
                        lab.checkDirectory(),
                        lab.evidenceDirectory(),
                        "course/m02.1-complete"));

    assertTrue(failure.getMessage().contains("completeRef"));
    assertFalse(Files.exists(lab.evidenceDirectory().resolve("manifest.json")));
  }

  @Test
  void missingRequiredReportFailsClosed(@TempDir Path temporary) throws IOException {
    LabRepository lab = createLabRepository(temporary, "course/m02-complete", true);
    Files.delete(lab.checkDirectory().resolve("lifecycle.json"));

    IllegalStateException failure =
        assertThrows(
            IllegalStateException.class,
            () ->
                new M02EvidenceWriter()
                    .write(
                        lab.root(), lab.checkDirectory(), lab.evidenceDirectory(), lab.unitTag()));

    assertTrue(failure.getMessage().contains("missing M02 report artifact"));
    assertFalse(Files.exists(lab.evidenceDirectory().resolve("manifest.json")));
  }

  @Test
  void symlinkedEvidenceParentCannotRedirectPublishedArtifacts(@TempDir Path temporary)
      throws IOException {
    LabRepository lab = createLabRepository(temporary, "course/m02-complete", true);
    Path outside = Files.createDirectories(temporary.resolve("outside-evidence"));
    Path labEvidence = lab.root().resolve("build/lab-evidence");
    Files.createDirectories(labEvidence.getParent());
    Files.createSymbolicLink(labEvidence, outside);

    assertThrows(
        IllegalStateException.class,
        () ->
            new M02EvidenceWriter()
                .write(lab.root(), lab.checkDirectory(), lab.evidenceDirectory(), lab.unitTag()));
    assertDirectoryEmpty(outside);
  }

  @Test
  void symlinkedReportParentIsRejectedEvenWhenExternalReportsExist(@TempDir Path temporary)
      throws IOException {
    LabRepository lab = createLabRepository(temporary, "course/m02-complete", true);
    Path reportsParent = lab.root().resolve("build/reports");
    Path outside = temporary.resolve("outside-reports");
    Files.move(reportsParent, outside);
    Files.createSymbolicLink(reportsParent, outside);

    assertThrows(
        IllegalStateException.class,
        () ->
            new M02EvidenceWriter()
                .write(lab.root(), lab.checkDirectory(), lab.evidenceDirectory(), lab.unitTag()));
    assertFalse(Files.exists(lab.evidenceDirectory().resolve("manifest.json")));
    assertTrue(Files.isRegularFile(outside.resolve("m02/check.json")));
  }

  private static LabRepository createLabRepository(
      Path temporary, String unitTag, boolean annotatedTag) {
    Path root = temporary.resolve("repo");
    Path sourceRoot = M01TestPaths.root();
    copy(
        sourceRoot.resolve("schemas/cex.lab-evidence.v1.schema.json"),
        root.resolve("schemas/cex.lab-evidence.v1.schema.json"));
    copy(
        sourceRoot.resolve(
            "matching-testkit/src/test/resources/m02/fixtures/order-lifecycle-v1.json"),
        root.resolve("matching-testkit/src/test/resources/m02/fixtures/order-lifecycle-v1.json"));
    writeString(root.resolve("schemas/matching.m02.check.v2.schema.json"), m02CheckSchema());
    writeString(root.resolve(".gitignore"), "/build/\n");
    writeString(root.resolve("course.properties"), courseProperties(unitTag));
    git(root, "init", "-q");
    git(root, "config", "user.name", "M02 Evidence Test");
    git(root, "config", "user.email", "m02-evidence@example.invalid");
    git(root, "config", "commit.gpgsign", "false");
    git(root, "add", ".");
    git(root, "commit", "-q", "-m", "test: freeze M02 evidence inputs");
    if (annotatedTag) {
      git(root, "tag", "-a", unitTag, "-m", "test: annotated M02 completion");
    } else {
      git(root, "tag", unitTag);
    }

    Path checkDirectory = root.resolve("build/reports/m02");
    for (String name : M02EvidenceWriter.REPORT_ARTIFACTS) {
      String content =
          "check.json".equals(name)
              ? m02Check()
              : "canonical-history.utf8".equals(name)
                  ? "M02H1|test-history\n"
                  : "{\"status\":\"PASS\",\"artifact\":\"" + name + "\"}\n";
      writeString(checkDirectory.resolve(name), content);
    }
    return new LabRepository(root, checkDirectory, root.resolve("build/lab-evidence/M02"), unitTag);
  }

  private static String courseProperties(String unitTag) {
    return """
        case=high-availability-cex
        profile=SPOT-CEX-1.0
        planVersion=0.4
        project=matching
        unit=M02
        lifecycle=CODE_VERIFIED
        designDepth=CONTRACT
        startRef=course/m02-start
        completeRef=%s
        m02Check.expectedStatus=PASS
        evidencePath=build/lab-evidence/M02/manifest.json
        """
        .formatted(unitTag);
  }

  private static String m02CheckSchema() {
    return """
        {
          "$schema": "https://json-schema.org/draft/2020-12/schema",
          "type": "object",
          "additionalProperties": false,
          "required": [
            "schemaVersion", "unit", "status", "contractPlanVersion", "m01Regression",
            "eventBatches", "scenarioCorpus", "lifecycle", "registryInvariants", "replays",
            "mutants", "architecture"
          ],
          "properties": {
            "schemaVersion": { "const": "matching.m02.check.v2" },
            "unit": { "const": "M02" },
            "status": { "const": "PASS" },
            "contractPlanVersion": { "const": "0.4" },
            "m01Regression": { "type": "object" },
            "eventBatches": { "type": "object" },
            "scenarioCorpus": { "type": "object" },
            "lifecycle": { "type": "object" },
            "registryInvariants": { "type": "object" },
            "replays": { "type": "object" },
            "mutants": { "type": "object" },
            "architecture": { "type": "object" }
          }
        }
        """;
  }

  private static String m02Check() {
    return """
        {
          "schemaVersion": "matching.m02.check.v2",
          "unit": "M02",
          "status": "PASS",
          "contractPlanVersion": "0.4",
          "m01Regression": { "status": "PASS" },
          "eventBatches": { "status": "PASS" },
          "scenarioCorpus": { "scenarios": 10, "commands": 34 },
          "lifecycle": { "status": "PASS" },
          "registryInvariants": { "status": "PASS" },
          "replays": { "requested": 100, "completed": 100, "distinctDigests": 1 },
          "mutants": { "status": "PASS", "systemErrorControl": "SYSTEM_ERROR" },
          "architecture": { "status": "PASS" }
        }
        """;
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

  private static void assertDirectoryEmpty(Path directory) throws IOException {
    try (var paths = Files.list(directory)) {
      assertTrue(paths.findAny().isEmpty());
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
