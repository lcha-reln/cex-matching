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
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;

final class M01EvidenceWriterTest {
  @Test
  void cleanRepositoryProducesPatchTagManifestWithVerifiedClaimsAndHashes(@TempDir Path temporary) {
    LabRepository lab = createLabRepository(temporary, "course/m01.1-complete");

    M01EvidenceWriter.Result result =
        new M01EvidenceWriter()
            .write(lab.root(), lab.checkDirectory(), lab.evidenceDirectory(), lab.unitTag());

    JsonNode manifest = JsonSupport.parse(readBytes(result.manifestPath()));
    assertEquals(lab.unitTag(), manifest.path("unitTag").stringValue());
    assertEquals(git(lab.root(), "rev-parse", "HEAD").strip(), result.sourceCommit());
    assertEquals(result.sourceCommit(), manifest.path("source").path("commit").stringValue());
    assertEquals(
        M01EvidenceWriter.REQUIRED_CLAIMS,
        manifest
            .path("claims")
            .valueStream()
            .map(claim -> claim.path("id").stringValue())
            .toList());
    assertEquals(
        M01EvidenceWriter.LIMITATIONS,
        manifest.path("limitations").valueStream().map(JsonNode::stringValue).toList());
    for (JsonNode claim : manifest.path("claims")) {
      assertEquals(
          "./gradlew m01Check m01Evidence -Pm01.unitTag=" + lab.unitTag() + " --no-daemon",
          claim.path("command").stringValue());
      for (JsonNode artifact : claim.path("artifacts")) {
        Path path = lab.evidenceDirectory().resolve(artifact.path("path").stringValue());
        assertTrue(Files.isRegularFile(path));
        assertEquals(artifact.path("sha256").stringValue(), Hashing.sha256Hex(readBytes(path)));
      }
    }
    assertEquals(Hashing.sha256Hex(readBytes(result.manifestPath())), result.manifestSha256());
    assertTrue(git(lab.root(), "status", "--porcelain", "--untracked-files=normal").isBlank());
  }

  @Test
  void dirtyRepositoryIsRejectedBeforeEvidenceIsWritten(@TempDir Path temporary) {
    LabRepository lab = createLabRepository(temporary, "course/m01-complete");
    writeString(lab.root().resolve("dirty.txt"), "not committed\n");

    IllegalStateException failure =
        assertThrows(
            IllegalStateException.class,
            () ->
                new M01EvidenceWriter()
                    .write(
                        lab.root(), lab.checkDirectory(), lab.evidenceDirectory(), lab.unitTag()));

    assertTrue(failure.getMessage().contains("clean working tree"));
    assertFalse(Files.exists(lab.evidenceDirectory().resolve("manifest.json")));
  }

  @Test
  void completeTagMustMatchTheCommittedCourseContract(@TempDir Path temporary) {
    LabRepository lab = createLabRepository(temporary, "course/m01-complete");

    IllegalStateException failure =
        assertThrows(
            IllegalStateException.class,
            () ->
                new M01EvidenceWriter()
                    .write(
                        lab.root(),
                        lab.checkDirectory(),
                        lab.evidenceDirectory(),
                        "course/m01.1-complete"));

    assertTrue(failure.getMessage().contains("completeRef"));
    assertFalse(Files.exists(lab.evidenceDirectory().resolve("manifest.json")));
  }

  @Test
  void symlinkedEvidenceParentCannotRedirectPublishedArtifacts(@TempDir Path temporary)
      throws IOException {
    LabRepository lab = createLabRepository(temporary, "course/m01-complete");
    Path outside = Files.createDirectories(temporary.resolve("outside-evidence"));
    Path labEvidence = lab.root().resolve("build/lab-evidence");
    Files.createDirectories(labEvidence.getParent());
    Files.createSymbolicLink(labEvidence, outside);

    assertThrows(
        IllegalStateException.class,
        () ->
            new M01EvidenceWriter()
                .write(lab.root(), lab.checkDirectory(), lab.evidenceDirectory(), lab.unitTag()));
    assertDirectoryEmpty(outside);
  }

  @Test
  void symlinkedReportParentIsRejectedEvenWhenExternalReportsExist(@TempDir Path temporary)
      throws IOException {
    LabRepository lab = createLabRepository(temporary, "course/m01-complete");
    Path reportsParent = lab.root().resolve("build/reports");
    Path outside = temporary.resolve("outside-reports");
    Files.move(reportsParent, outside);
    Files.createSymbolicLink(reportsParent, outside);

    assertThrows(
        IllegalStateException.class,
        () ->
            new M01EvidenceWriter()
                .write(lab.root(), lab.checkDirectory(), lab.evidenceDirectory(), lab.unitTag()));
    assertFalse(Files.exists(lab.evidenceDirectory().resolve("manifest.json")));
    assertTrue(Files.isRegularFile(outside.resolve("m01/check.json")));
  }

  private static LabRepository createLabRepository(Path temporary, String unitTag) {
    Path root = temporary.resolve("repo");
    Path sourceRoot = M01TestPaths.root();
    copy(
        sourceRoot.resolve("schemas/cex.lab-evidence.v1.schema.json"),
        root.resolve("schemas/cex.lab-evidence.v1.schema.json"));
    copy(
        sourceRoot.resolve("schemas/matching.m01.check.v2.schema.json"),
        root.resolve("schemas/matching.m01.check.v2.schema.json"));
    copy(
        sourceRoot.resolve("matching-testkit/src/test/resources/m01/fixtures/price-time-v1.json"),
        root.resolve("matching-testkit/src/test/resources/m01/fixtures/price-time-v1.json"));
    writeString(root.resolve(".gitignore"), "/build/\n");
    writeString(root.resolve("course.properties"), courseProperties(unitTag));
    git(root, "init", "-q");
    git(root, "config", "user.name", "M01 Evidence Test");
    git(root, "config", "user.email", "m01-evidence@example.invalid");
    git(root, "config", "commit.gpgsign", "false");
    git(root, "add", ".");
    git(root, "commit", "-q", "-m", "test: freeze M01 evidence inputs");

    Path checkDirectory = root.resolve("build/reports/m01");
    M01CheckRunner.Result check = new M01CheckRunner().run(sourceRoot, checkDirectory, root);
    assertEquals(M01CheckRunner.PASS, check.status());
    return new LabRepository(root, checkDirectory, root.resolve("build/lab-evidence/M01"), unitTag);
  }

  private static String courseProperties(String unitTag) {
    return """
        case=high-availability-cex
        profile=SPOT-CEX-1.0
        planVersion=0.3
        project=matching
        unit=M01
        lifecycle=CODE_VERIFIED
        designDepth=CONTRACT
        startRef=course/m01-start
        completeRef=%s
        m01Check.expectedStatus=PASS
        evidencePath=build/lab-evidence/M01/manifest.json
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
