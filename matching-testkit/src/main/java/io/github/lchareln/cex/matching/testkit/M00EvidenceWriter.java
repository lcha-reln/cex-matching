package io.github.lchareln.cex.matching.testkit;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Generates and verifies the clean-tree evidence manifest for M00. */
public final class M00EvidenceWriter {
  private static final Pattern UNIT_TAG =
      Pattern.compile("^course/m00(?:\\.[1-9][0-9]*)?-complete$");
  private static final List<String> REQUIRED_CLAIMS =
      List.of(
          "input-contract",
          "canonical-history",
          "deterministic-replay",
          "semantic-mutant",
          "architecture-boundary");

  public Result write(
      Path repositoryRoot, Path checkDirectory, Path evidenceDirectory, String unitTag) {
    Path root = repositoryRoot.toAbsolutePath().normalize();
    Path destination = evidenceDirectory.toAbsolutePath().normalize();
    require(UNIT_TAG.matcher(unitTag).matches(), "invalid M00 complete tag: " + unitTag);
    requireClean(root);
    String sourceCommit = git(root, "rev-parse", "HEAD").strip();
    require(sourceCommit.matches("^[a-f0-9]{40}$"), "HEAD is not a full Git commit");
    verifyCourseProperties(root, unitTag);

    Path checkOutput = checkDirectory.toAbsolutePath().normalize();
    require(
        checkOutput.equals(root.resolve("build/reports/m00")),
        "check directory must be the fixed M00 report path");
    requireSafeExistingTree(root, checkOutput);
    JsonNode check = parseAndValidateCheck(root, checkOutput.resolve("check.json"));
    Path staging = createStagingDirectory(root, destination);
    try {
      prepareEvidenceFiles(root, checkOutput, staging);

      ObjectNode manifest = manifest(unitTag, sourceCommit, check, staging);
      JsonSupport.validate(
          manifest, readString(root.resolve("schemas/cex.lab-evidence.v1.schema.json")), true);
      verifyManifestSemantics(manifest, staging, sourceCommit, unitTag);
      AtomicFiles.write(staging.resolve("manifest.json"), JsonSupport.prettyBytes(manifest));

      require(
          sourceCommit.equals(git(root, "rev-parse", "HEAD").strip()),
          "HEAD changed while generating evidence");
      requireClean(root);
      publishStagingDirectory(root, staging, destination);
      Path manifestPath = destination.resolve("manifest.json");
      return new Result(manifestPath, sourceCommit, Hashing.sha256Hex(readBytes(manifestPath)));
    } catch (RuntimeException exception) {
      deleteTreeQuietly(staging);
      throw exception;
    }
  }

  private static JsonNode parseAndValidateCheck(Path root, Path checkPath) {
    JsonNode check = JsonSupport.parse(readBytes(checkPath));
    JsonSupport.validate(
        check, readString(root.resolve("schemas/matching.m00.check.v2.schema.json")), false);
    require(M00CheckRunner.PASS.equals(check.path("status").stringValue()), "m00Check is not PASS");
    return check;
  }

  private static void prepareEvidenceFiles(Path root, Path checkDirectory, Path evidenceDirectory) {
    try {
      Path reports = evidenceDirectory.resolve("reports");
      Path inputs = evidenceDirectory.resolve("inputs");
      Files.createDirectories(reports);
      Files.createDirectories(inputs);
      Files.deleteIfExists(evidenceDirectory.resolve("manifest.json"));
      for (String name :
          List.of(
              "check.json",
              "canonical-history.utf8",
              "validation-results.json",
              "mutants.json",
              "architecture.json")) {
        Path source = checkDirectory.resolve(name);
        require(Files.isRegularFile(source), "missing M00 report artifact: " + source);
        Files.copy(source, reports.resolve(name), StandardCopyOption.REPLACE_EXISTING);
      }
      Files.copy(
          root.resolve("matching-testkit/src/test/resources/m00/fixtures/history-v1.json"),
          inputs.resolve("history-v1.json"),
          StandardCopyOption.REPLACE_EXISTING);
    } catch (IOException exception) {
      throw new IllegalStateException("cannot prepare evidence artifacts", exception);
    }
  }

  private static Path createStagingDirectory(Path root, Path destination) {
    require(destination.startsWith(root), "evidence directory must remain inside the repository");
    Path parent = destination.getParent();
    require(parent != null, "evidence directory has no parent");
    SafeOutputPaths.requireNoSymlinkComponents(root, parent);
    requireSafeExistingTree(root, destination);
    try {
      Files.createDirectories(parent);
      SafeOutputPaths.requireNoSymlinkComponents(root, parent);
      return Files.createTempDirectory(parent, ".M00-staging-");
    } catch (IOException exception) {
      throw new IllegalStateException("cannot create evidence staging directory", exception);
    }
  }

  private static void publishStagingDirectory(Path root, Path staging, Path destination) {
    requireSafeExistingTree(root, staging);
    requireSafeExistingTree(root, destination);
    if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
      deleteTree(destination);
    }
    try {
      try {
        Files.move(staging, destination, StandardCopyOption.ATOMIC_MOVE);
      } catch (AtomicMoveNotSupportedException exception) {
        Files.move(staging, destination);
      }
    } catch (IOException exception) {
      throw new IllegalStateException("cannot publish evidence directory", exception);
    }
    requireSafeExistingTree(root, destination);
  }

  private static void requireSafeExistingTree(Path root, Path path) {
    SafeOutputPaths.requireNoSymlinkComponents(root, path);
    if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
      return;
    }
    try (var paths = Files.walk(path)) {
      paths.forEach(
          candidate ->
              require(
                  !Files.isSymbolicLink(candidate),
                  "symlink inside evidence tree is forbidden: " + candidate));
    } catch (IOException exception) {
      throw new IllegalStateException("cannot inspect evidence directory", exception);
    }
  }

  private static void deleteTree(Path root) {
    try (var paths = Files.walk(root)) {
      for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
        Files.delete(path);
      }
    } catch (IOException exception) {
      throw new IllegalStateException("cannot replace prior evidence directory", exception);
    }
  }

  private static void deleteTreeQuietly(Path root) {
    if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
      return;
    }
    try {
      deleteTree(root);
    } catch (RuntimeException ignored) {
      // Preserve the original evidence-generation failure.
    }
  }

  private static ObjectNode manifest(
      String unitTag, String sourceCommit, JsonNode check, Path evidenceDirectory) {
    ObjectNode root = JsonSupport.MAPPER.createObjectNode();
    root.put("schemaVersion", "cex.lab-evidence.v1");
    root.put("case", "high-availability-cex");
    root.put("project", "matching");
    root.put("unit", "M00");
    root.put("unitTag", unitTag);
    root.putNull("productRelease");
    root.put("planVersion", "0.1");

    ObjectNode source = root.putObject("source");
    source.put("commit", sourceCommit);
    source.put("dirty", false);

    ObjectNode environment = root.putObject("environment");
    environment.put("java", System.getProperty("java.runtime.version"));
    environment.put("os", System.getProperty("os.name"));
    environment.put("arch", System.getProperty("os.arch"));

    ArrayNode claims = root.putArray("claims");
    addInputClaim(claims, evidenceDirectory, check);
    addCanonicalClaim(claims, evidenceDirectory, check);
    addReplayClaim(claims, evidenceDirectory, check);
    addMutantClaim(claims, evidenceDirectory, check);
    addArchitectureClaim(claims, evidenceDirectory, check);

    ArrayNode limitations = root.putArray("limitations");
    limitations.add("Only one PlaceLimitOrder input contract is implemented for BTC-USDT.");
    limitations.add(
        "A VALID result is not Accepted, Rested, or Trade and creates no order-book state.");
    limitations.add(
        "There is no cancel, amendment, market order, TIF, STP, fee, asset, or account logic.");
    limitations.add(
        "The unit has no persistence, networking, database, threads, Aeron, or high availability.");
    limitations.add(
        "The evidence makes no throughput, latency, recovery, or production-readiness claim.");
    root.putNull("supersedes");
    root.put("generatedAt", Instant.now().toString());
    return root;
  }

  private static void addInputClaim(ArrayNode claims, Path evidenceDirectory, JsonNode check) {
    ObjectNode observations = JsonSupport.MAPPER.createObjectNode();
    observations.put("records", check.path("fixture").path("records").intValue());
    observations.put("valid", check.path("fixture").path("valid").intValue());
    observations.put("invalid", check.path("fixture").path("invalid").intValue());
    observations.put("fixtureSha256", check.path("fixture").path("sha256").stringValue());
    addClaim(
        claims,
        "input-contract",
        "correctness",
        "Frozen fixture records produce the expected VALID or first-priority INVALID result.",
        observations,
        evidenceDirectory,
        List.of("inputs/history-v1.json", "reports/validation-results.json"));
  }

  private static void addCanonicalClaim(ArrayNode claims, Path evidenceDirectory, JsonNode check) {
    ObjectNode observations = JsonSupport.MAPPER.createObjectNode();
    observations.put("format", check.path("canonical").path("format").stringValue());
    observations.put("lines", check.path("canonical").path("lines").intValue());
    observations.put("utf8Bytes", check.path("canonical").path("utf8Bytes").intValue());
    observations.put("digest", check.path("canonical").path("digest").stringValue());
    addClaim(
        claims,
        "canonical-history",
        "correctness",
        "Canonical M00H1 bytes and SHA-256 match the checked-in golden.",
        observations,
        evidenceDirectory,
        List.of("reports/canonical-history.utf8"));
  }

  private static void addReplayClaim(ArrayNode claims, Path evidenceDirectory, JsonNode check) {
    ObjectNode observations = JsonSupport.MAPPER.createObjectNode();
    observations.put("requested", check.path("replays").path("requested").intValue());
    observations.put("completed", check.path("replays").path("completed").intValue());
    observations.put("distinctDigests", check.path("replays").path("distinctDigests").intValue());
    addClaim(
        claims,
        "deterministic-replay",
        "correctness",
        "One hundred fresh replays produce identical results, bytes, and digest.",
        observations,
        evidenceDirectory,
        List.of("reports/check.json"));
  }

  private static void addMutantClaim(ArrayNode claims, Path evidenceDirectory, JsonNode check) {
    ObjectNode observations = JsonSupport.MAPPER.createObjectNode();
    observations.put("id", check.path("requiredMutant").path("id").stringValue());
    observations.put(
        "classification", check.path("requiredMutant").path("classification").stringValue());
    observations.put("killed", check.path("requiredMutant").path("killed").booleanValue());
    observations.put("caseId", check.path("requiredMutant").path("caseId").stringValue());
    addClaim(
        claims,
        "semantic-mutant",
        "mutation-testing",
        "The required quantity-zero mutant is killed by a business assertion, not a system error.",
        observations,
        evidenceDirectory,
        List.of("reports/mutants.json"));
  }

  private static void addArchitectureClaim(
      ArrayNode claims, Path evidenceDirectory, JsonNode check) {
    ObjectNode observations = JsonSupport.MAPPER.createObjectNode();
    observations.put(
        "coreSourceFiles", check.path("architecture").path("coreSourceFiles").intValue());
    observations.put("violations", check.path("architecture").path("violations").intValue());
    addClaim(
        claims,
        "architecture-boundary",
        "architecture",
        "matching-core has no production dependency, I/O, clock, random, runtime, or future-module leak.",
        observations,
        evidenceDirectory,
        List.of("reports/architecture.json"));
  }

  private static void addClaim(
      ArrayNode claims,
      String id,
      String category,
      String statement,
      ObjectNode observations,
      Path evidenceDirectory,
      List<String> artifactPaths) {
    ObjectNode claim = claims.addObject();
    claim.put("id", id);
    claim.put("category", category);
    claim.put("statement", statement);
    claim.put("status", "pass");
    claim.put("command", "./gradlew m00Check m00Evidence --no-daemon");
    claim.set("observations", observations);
    ArrayNode artifacts = claim.putArray("artifacts");
    for (String path : artifactPaths) {
      ObjectNode artifact = artifacts.addObject();
      artifact.put("path", path);
      artifact.put("sha256", Hashing.sha256Hex(readBytes(evidenceDirectory.resolve(path))));
    }
  }

  private static void verifyManifestSemantics(
      JsonNode manifest, Path evidenceDirectory, String sourceCommit, String unitTag) {
    require("M00".equals(manifest.path("unit").stringValue()), "evidence unit is not M00");
    require(unitTag.equals(manifest.path("unitTag").stringValue()), "evidence unitTag changed");
    require(
        sourceCommit.equals(manifest.path("source").path("commit").stringValue()),
        "evidence source changed");
    require(!manifest.path("source").path("dirty").booleanValue(), "dirty evidence is forbidden");

    Set<String> claimIds = new LinkedHashSet<>();
    Set<String> artifactPaths = new LinkedHashSet<>();
    for (JsonNode claim : manifest.path("claims")) {
      require(
          "pass".equals(claim.path("status").stringValue()), "non-pass claim cannot be published");
      require(claimIds.add(claim.path("id").stringValue()), "duplicate evidence claim");
      for (JsonNode artifact : claim.path("artifacts")) {
        String relative = artifact.path("path").stringValue();
        require(artifactPaths.add(relative), "duplicate artifact path: " + relative);
        Path relativePath = Path.of(relative);
        require(!relativePath.isAbsolute(), "absolute artifact path is forbidden");
        require(!relative.contains(".."), "parent traversal is forbidden in artifact path");
        Path resolved = evidenceDirectory.resolve(relativePath).normalize();
        require(
            resolved.startsWith(evidenceDirectory.normalize()), "artifact escapes evidence root");
        SafeOutputPaths.requireNoSymlinkComponents(evidenceDirectory, resolved);
        require(
            Files.isRegularFile(resolved, LinkOption.NOFOLLOW_LINKS),
            "artifact is missing: " + relative);
        try {
          require(
              resolved.toRealPath().startsWith(evidenceDirectory.toRealPath()),
              "artifact real path escapes evidence root: " + relative);
        } catch (IOException exception) {
          throw new IllegalStateException("cannot resolve artifact path: " + relative, exception);
        }
        require(
            artifact.path("sha256").stringValue().equals(Hashing.sha256Hex(readBytes(resolved))),
            "artifact hash mismatch: " + relative);
      }
    }
    require(
        claimIds.equals(new LinkedHashSet<>(REQUIRED_CLAIMS)),
        "evidence claim set or order changed");
  }

  private static void verifyCourseProperties(Path root, String unitTag) {
    Properties properties = new Properties();
    try (var reader =
        Files.newBufferedReader(root.resolve("course.properties"), StandardCharsets.UTF_8)) {
      properties.load(reader);
    } catch (IOException exception) {
      throw new IllegalStateException("cannot read course.properties", exception);
    }
    requireProperty(properties, "case", "high-availability-cex");
    requireProperty(properties, "profile", "SPOT-CEX-1.0");
    requireProperty(properties, "project", "matching");
    requireProperty(properties, "unit", "M00");
    requireProperty(properties, "planVersion", "0.1");
    requireProperty(properties, "lifecycle", "CODE_VERIFIED");
    requireProperty(properties, "designDepth", "CONTRACT");
    requireProperty(properties, "startRef", "course/m00.2-start");
    requireProperty(properties, "completeRef", unitTag);
    requireProperty(properties, "m00Check.expectedStatus", "PASS");
    requireProperty(properties, "evidencePath", "build/lab-evidence/M00/manifest.json");
  }

  private static void requireProperty(Properties properties, String key, String expected) {
    require(expected.equals(properties.getProperty(key)), "unexpected course property: " + key);
  }

  private static void requireClean(Path root) {
    String status = git(root, "status", "--porcelain", "--untracked-files=normal");
    require(status.isBlank(), "m00Evidence requires a clean working tree");
  }

  private static String git(Path root, String... arguments) {
    List<String> command = new java.util.ArrayList<>();
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
            "git command failed (" + exitCode + "): " + new String(output, StandardCharsets.UTF_8));
      }
      return new String(output, StandardCharsets.UTF_8);
    } catch (IOException exception) {
      throw new IllegalStateException("cannot execute git", exception);
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("git command was interrupted", exception);
    }
  }

  private static byte[] readBytes(Path path) {
    try {
      return Files.readAllBytes(path);
    } catch (IOException exception) {
      throw new IllegalStateException("cannot read " + path, exception);
    }
  }

  private static String readString(Path path) {
    try {
      return Files.readString(path, StandardCharsets.UTF_8);
    } catch (IOException exception) {
      throw new IllegalStateException("cannot read " + path, exception);
    }
  }

  private static void require(boolean condition, String message) {
    if (!condition) {
      throw new IllegalStateException(message);
    }
  }

  public record Result(Path manifestPath, String sourceCommit, String manifestSha256) {}
}
