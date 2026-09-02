package io.github.lchareln.cex.matching.testkit;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** Runs M09 semantics on current classes while preserving its immutable source-only boundary. */
final class M10InheritedM09Regression {
  private static final String BASELINE_REF = "course/m09-complete";
  private static final int MAX_ARCHIVE_ENTRIES = 20_000;
  private static final long MAX_ARCHIVE_BYTES = 256L * 1024L * 1024L;

  ObjectNode run(Path repositoryRoot, Path workspace) {
    Path root = repositoryRoot.toAbsolutePath().normalize();
    Path working = SafeOutputPaths.resolveTrustedOutput(root, workspace);
    deleteTree(working);
    try {
      Files.createDirectories(working);
      require(
          "tag".equals(git(root, "cat-file", "-t", BASELINE_REF).strip()),
          "M09 complete baseline is not an annotated tag");
      String baselineCommit = git(root, "rev-parse", BASELINE_REF + "^{}").strip();
      String semanticCommit = git(root, "rev-parse", "HEAD").strip();
      requireFullCommit(baselineCommit, "M09 baseline");
      requireFullCommit(semanticCommit, "M10 semantic source");

      Path archive = working.resolve("m09-source-baseline.zip");
      gitArchive(root, archive, baselineCommit);
      Path view = working.resolve("source-baseline");
      Files.createDirectory(view);
      extractArchive(archive, view);
      Files.delete(archive);
      verifyRegularTree(view);

      Path reports = view.resolve("build/reports/m09-current-class-regression");
      M09CheckRunner.Result inherited = new M09CheckRunner().run(view, reports);
      if (M09CheckRunner.SYSTEM_ERROR.equals(inherited.status())) {
        throw new IllegalStateException("inherited M09 judge returned SYSTEM_ERROR");
      }
      require(M09CheckRunner.PASS.equals(inherited.status()), "inherited M09 semantics failed");
      JsonNode inheritedCheck = JsonSupport.parse(Files.readAllBytes(inherited.reportPath()));
      require(
          M09CheckRunner.PASS.equals(inheritedCheck.path("status").stringValue()),
          "inherited M09 check artifact is not PASS");
      JsonNode fixed =
          JsonSupport.parse(Files.readAllBytes(reports.resolve("fixed-scenarios.json")));
      JsonNode generated =
          JsonSupport.parse(Files.readAllBytes(reports.resolve("generated-properties.json")));
      JsonNode mutants = JsonSupport.parse(Files.readAllBytes(reports.resolve("mutants.json")));
      ObjectNode summary = JsonSupport.MAPPER.createObjectNode();
      summary.put("unit", "M09");
      summary.put("completeRef", BASELINE_REF);
      summary.put("status", M10CheckRunner.PASS);
      summary.put("fixedScenarios", fixed.path("scenarios").intValue());
      summary.put("generatedOperations", generated.path("operations").intValue());
      summary.put("mutantsKilled", mutants.path("killedAsStudentFailure").intValue());
      summary.put("semanticSource", "CURRENT_HEAD_COMPILED_PRODUCTION_CLASSES");
      summary.put("semanticSourceCommit", semanticCommit);
      summary.put("sourceOnlyArchitectureBaseline", BASELINE_REF);
      summary.put("sourceOnlyArchitectureBaselineCommit", baselineCommit);
      summary.put("sourceOnlyArchitectureSupersededBy", "M10_ARCHITECTURE_GATE");
      return summary;
    } catch (IOException failure) {
      throw new IllegalStateException("cannot run inherited M09 semantic regression", failure);
    } finally {
      deleteTree(working);
    }
  }

  private static void gitArchive(Path root, Path archive, String commit) throws IOException {
    Process process =
        new ProcessBuilder("git", "archive", "--format=zip", "--output", archive.toString(), commit)
            .directory(root.toFile())
            .start();
    String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    String error = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
    int exit = await(process, "git archive");
    require(exit == 0, "git archive failed: " + (error + output).strip());
    require(
        Files.isRegularFile(archive, LinkOption.NOFOLLOW_LINKS),
        "git archive did not produce a regular file");
  }

  private static void extractArchive(Path archive, Path destination) throws IOException {
    int entries = 0;
    long bytes = 0;
    try (InputStream input = Files.newInputStream(archive);
        ZipInputStream zip = new ZipInputStream(input, StandardCharsets.UTF_8)) {
      ZipEntry entry;
      while ((entry = zip.getNextEntry()) != null) {
        entries = Math.incrementExact(entries);
        require(entries <= MAX_ARCHIVE_ENTRIES, "M09 source archive has too many entries");
        String name = entry.getName();
        require(
            name != null && !name.isBlank() && !name.contains("\\"),
            "M09 source archive has a non-canonical entry");
        Path relative = Path.of(name).normalize();
        require(
            !relative.isAbsolute() && !relative.startsWith(".."),
            "M09 source archive entry escapes the baseline view");
        Path target = destination.resolve(relative).normalize();
        require(target.startsWith(destination), "M09 source archive entry escapes extraction");
        if (entry.isDirectory()) {
          Files.createDirectories(target);
        } else {
          require(
              !Files.exists(target, LinkOption.NOFOLLOW_LINKS),
              "M09 source archive repeats an entry");
          Files.createDirectories(target.getParent());
          long copied = Files.copy(zip, target);
          bytes = Math.addExact(bytes, copied);
          require(bytes <= MAX_ARCHIVE_BYTES, "M09 source archive exceeds the extraction limit");
        }
        zip.closeEntry();
      }
    }
    require(entries > 0 && bytes > 0, "M09 source archive is empty");
  }

  private static void verifyRegularTree(Path root) throws IOException {
    try (var paths = Files.walk(root)) {
      for (Path path : paths.toList()) {
        require(!Files.isSymbolicLink(path), "M09 source view contains a symbolic link");
        require(
            Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)
                || Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS),
            "M09 source view contains a non-regular entry");
      }
    }
  }

  private static String git(Path root, String... arguments) {
    List<String> command = new ArrayList<>();
    command.add("git");
    command.addAll(List.of(arguments));
    try {
      Process process = new ProcessBuilder(command).directory(root.toFile()).start();
      String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
      String error = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
      int exit = await(process, "git");
      require(exit == 0, "git command failed: " + error.strip());
      return output;
    } catch (IOException failure) {
      throw new IllegalStateException("cannot execute git", failure);
    }
  }

  private static int await(Process process, String operation) {
    try {
      return process.waitFor();
    } catch (InterruptedException failure) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(operation + " interrupted", failure);
    }
  }

  private static void requireFullCommit(String value, String label) {
    require(value.matches("[a-f0-9]{40}"), label + " is not a full SHA-1 commit");
  }

  private static void deleteTree(Path path) {
    if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return;
    try (var paths = Files.walk(path)) {
      for (Path current : paths.sorted(Comparator.reverseOrder()).toList()) {
        Files.deleteIfExists(current);
      }
    } catch (IOException failure) {
      throw new IllegalStateException("cannot clear M09 regression view " + path, failure);
    }
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new IllegalStateException(message);
  }
}
