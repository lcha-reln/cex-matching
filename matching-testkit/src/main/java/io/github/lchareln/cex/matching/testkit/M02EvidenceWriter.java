package io.github.lchareln.cex.matching.testkit;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Generates and verifies the clean-tree evidence manifest for M02. */
public final class M02EvidenceWriter {
  static final List<String> REQUIRED_CLAIMS =
      List.of(
          "m00-m01-regression",
          "cancel-event-batches",
          "addressable-order-cancellation",
          "irreversible-terminal-states",
          "order-registry-book-invariants",
          "deterministic-lifecycle-history",
          "semantic-mutants",
          "architecture-boundary");
  static final List<String> LIMITATIONS =
      List.of(
          "Only one in-memory BTC-USDT GTC limit-order book with place and cancel is implemented.",
          "Accepted order IDs are unique for the lifetime of one engine process; terminal identity records are retained without pruning.",
          "A repeated place command is rejected as a duplicate order ID; command-level idempotency and prior-result replay are not implemented.",
          "There is no Cancel/Replace, amendment, mass cancel, IOC, FOK, post-only, market order, STP, market state, or price band.",
          "There is no account, asset, position, fee, settlement, reservation-release, or risk logic.",
          "Fixed scenarios and semantic mutants are not the independent generated reference model or property proof deferred to M03.",
          "The unit has no persistence, networking, database, threads, Aeron, or high availability.",
          "The evidence makes no throughput, latency, recovery, durable-idempotency, or production-readiness claim.");
  static final List<String> REPORT_ARTIFACTS =
      List.of(
          "m00-m01-regression.json",
          "cancel-event-batches.json",
          "lifecycle.json",
          "registry-invariants.json",
          "canonical-history.utf8",
          "mutants.json",
          "architecture.json",
          "check.json");
  static final Set<String> EXPECTED_ARTIFACT_PATHS =
      Set.of(
          "reports/check.json",
          "reports/m00-m01-regression.json",
          "reports/cancel-event-batches.json",
          "reports/lifecycle.json",
          "reports/registry-invariants.json",
          "reports/canonical-history.utf8",
          "reports/mutants.json",
          "reports/architecture.json",
          "inputs/order-lifecycle-v1.json");

  private static final Pattern UNIT_TAG =
      Pattern.compile("^course/m02(?:\\.[1-9][0-9]*)?-complete$");
  private static final Pattern FULL_GIT_COMMIT = Pattern.compile("^[a-f0-9]{40}(?:[a-f0-9]{24})?$");
  private static final String INPUT_PATH =
      "matching-testkit/src/test/resources/m02/fixtures/order-lifecycle-v1.json";

  public Result write(
      Path repositoryRoot, Path checkDirectory, Path evidenceDirectory, String unitTag) {
    Path root = repositoryRoot.toAbsolutePath().normalize();
    Path destination = evidenceDirectory.toAbsolutePath().normalize();
    require(UNIT_TAG.matcher(unitTag).matches(), "invalid M02 complete tag: " + unitTag);
    requireClean(root);
    String sourceCommit = git(root, "rev-parse", "HEAD").strip();
    require(FULL_GIT_COMMIT.matcher(sourceCommit).matches(), "HEAD is not a full Git commit");
    verifyCourseProperties(root, unitTag);
    verifyAnnotatedCompleteTag(root, unitTag, sourceCommit);

    Path checkOutput = checkDirectory.toAbsolutePath().normalize();
    require(
        checkOutput.equals(root.resolve("build/reports/m02")),
        "check directory must be the fixed M02 report path");
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
          "HEAD changed while generating M02 evidence");
      verifyAnnotatedCompleteTag(root, unitTag, sourceCommit);
      requireClean(root);
      publishStagingDirectory(root, staging, destination);
      try {
        require(
            sourceCommit.equals(git(root, "rev-parse", "HEAD").strip()),
            "HEAD changed while publishing M02 evidence");
        requireClean(root);
      } catch (RuntimeException exception) {
        deleteTreeQuietly(destination);
        throw exception;
      }
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
        check, readString(root.resolve("schemas/matching.m02.check.v2.schema.json")), false);
    require(
        "matching.m02.check.v2".equals(check.path("schemaVersion").stringValue()),
        "m02Check schema changed");
    require("PASS".equals(check.path("status").stringValue()), "m02Check is not PASS");
    require("M02".equals(check.path("unit").stringValue()), "m02Check unit changed");
    require(
        "0.4".equals(check.path("contractPlanVersion").stringValue()),
        "m02Check contract plan changed");
    return check;
  }

  private static void prepareEvidenceFiles(Path root, Path checkDirectory, Path evidenceDirectory) {
    try {
      Path reports = evidenceDirectory.resolve("reports");
      Path inputs = evidenceDirectory.resolve("inputs");
      Files.createDirectories(reports);
      Files.createDirectories(inputs);
      for (String name : REPORT_ARTIFACTS) {
        Path source = checkDirectory.resolve(name);
        require(
            Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS),
            "missing M02 report artifact: " + source);
        Files.copy(source, reports.resolve(name), StandardCopyOption.REPLACE_EXISTING);
      }
      Path input = root.resolve(INPUT_PATH);
      SafeOutputPaths.requireNoSymlinkComponents(root, input);
      require(
          Files.isRegularFile(input, LinkOption.NOFOLLOW_LINKS),
          "missing M02 scenario input: " + input);
      Files.copy(
          input, inputs.resolve("order-lifecycle-v1.json"), StandardCopyOption.REPLACE_EXISTING);
    } catch (IOException exception) {
      throw new IllegalStateException("cannot prepare M02 evidence artifacts", exception);
    }
  }

  private static ObjectNode manifest(
      String unitTag, String sourceCommit, JsonNode check, Path evidenceDirectory) {
    ObjectNode root = JsonSupport.MAPPER.createObjectNode();
    root.put("schemaVersion", "cex.lab-evidence.v1");
    root.put("case", "high-availability-cex");
    root.put("project", "matching");
    root.put("unit", "M02");
    root.put("unitTag", unitTag);
    root.putNull("productRelease");
    root.put("planVersion", "0.4");

    ObjectNode source = root.putObject("source");
    source.put("commit", sourceCommit);
    source.put("dirty", false);

    ObjectNode environment = root.putObject("environment");
    environment.put("java", System.getProperty("java.runtime.version"));
    environment.put("os", System.getProperty("os.name"));
    environment.put("arch", System.getProperty("os.arch"));

    ArrayNode claims = root.putArray("claims");
    addClaim(
        claims,
        "m00-m01-regression",
        "correctness",
        "The frozen M00 input contract and completed M01 price-time matcher remain PASS under M02.",
        passObservation(check, "m01Regression"),
        evidenceDirectory,
        unitTag,
        List.of("reports/m00-m01-regression.json"));
    addClaim(
        claims,
        "cancel-event-batches",
        "correctness",
        "Every Cancel emits exactly one frozen validation, business rejection, or cancellation result.",
        passObservation(check, "eventBatches"),
        evidenceDirectory,
        unitTag,
        List.of("reports/cancel-event-batches.json"));
    addClaim(
        claims,
        "addressable-order-cancellation",
        "correctness",
        "The frozen 10-scenario, 34-command corpus proves exact cancellation by accepted order ID.",
        observation(check, "scenarioCorpus"),
        evidenceDirectory,
        unitTag,
        List.of("inputs/order-lifecycle-v1.json"));
    addClaim(
        claims,
        "irreversible-terminal-states",
        "correctness",
        "Filled and canceled identities remain terminal while unknown cancellation creates no tombstone.",
        passObservation(check, "lifecycle"),
        evidenceDirectory,
        unitTag,
        List.of("reports/lifecycle.json"));
    addClaim(
        claims,
        "order-registry-book-invariants",
        "correctness",
        "Across the frozen history, an independent lifecycle ledger and returned resting book nodes remain bijective and FIFO-safe.",
        passObservation(check, "registryInvariants"),
        evidenceDirectory,
        unitTag,
        List.of("reports/registry-invariants.json"));
    addClaim(
        claims,
        "deterministic-lifecycle-history",
        "correctness",
        "One hundred fresh corpus parses and engines produce one M02H1 byte history and digest.",
        observation(check, "replays"),
        evidenceDirectory,
        unitTag,
        List.of("reports/canonical-history.utf8"));
    addClaim(
        claims,
        "semantic-mutants",
        "mutation-testing",
        "All required M02 lifecycle mutants are killed by business assertions; system errors do not count.",
        passObservation(check, "mutants"),
        evidenceDirectory,
        unitTag,
        List.of("reports/mutants.json"));
    addClaim(
        claims,
        "architecture-boundary",
        "architecture",
        "matching-core remains deterministic and free of I/O, runtime adapters, persistence, and Aeron.",
        passObservation(check, "architecture"),
        evidenceDirectory,
        unitTag,
        List.of("reports/architecture.json", "reports/check.json"));

    ArrayNode limitations = root.putArray("limitations");
    LIMITATIONS.forEach(limitations::add);
    root.putNull("supersedes");
    root.put("generatedAt", Instant.now().toString());
    return root;
  }

  private static ObjectNode observation(JsonNode check, String field) {
    JsonNode source = check.path(field);
    require(source.isObject(), "m02Check observation is missing or malformed: " + field);
    return (ObjectNode) source.deepCopy();
  }

  private static ObjectNode passObservation(JsonNode check, String field) {
    ObjectNode observation = observation(check, field);
    require(
        "PASS".equals(observation.path("status").stringValue()),
        "m02Check observation is not PASS: " + field);
    return observation;
  }

  private static void addClaim(
      ArrayNode claims,
      String id,
      String category,
      String statement,
      ObjectNode observations,
      Path evidenceDirectory,
      String unitTag,
      List<String> artifactPaths) {
    ObjectNode claim = claims.addObject();
    claim.put("id", id);
    claim.put("category", category);
    claim.put("statement", statement);
    claim.put("status", "pass");
    claim.put(
        "command", "./gradlew m02Check m02Evidence -Pm02.unitTag=" + unitTag + " --no-daemon");
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
    require(
        "high-availability-cex".equals(manifest.path("case").stringValue()),
        "evidence case changed");
    require("matching".equals(manifest.path("project").stringValue()), "evidence project changed");
    require("M02".equals(manifest.path("unit").stringValue()), "evidence unit is not M02");
    require(unitTag.equals(manifest.path("unitTag").stringValue()), "evidence unitTag changed");
    require(manifest.path("productRelease").isNull(), "M02 must not publish a product release");
    require("0.4".equals(manifest.path("planVersion").stringValue()), "evidence plan changed");
    require(
        sourceCommit.equals(manifest.path("source").path("commit").stringValue()),
        "evidence source changed");
    require(!manifest.path("source").path("dirty").booleanValue(), "dirty evidence is forbidden");
    require(
        manifest.path("supersedes").isNull(),
        "initial M02 evidence cannot supersede another bundle");

    List<String> limitations = new ArrayList<>();
    manifest.path("limitations").forEach(node -> limitations.add(node.stringValue()));
    require(LIMITATIONS.equals(limitations), "evidence limitations changed");

    Set<String> claimIds = new LinkedHashSet<>();
    Set<String> artifactPaths = new LinkedHashSet<>();
    String expectedCommand =
        "./gradlew m02Check m02Evidence -Pm02.unitTag=" + unitTag + " --no-daemon";
    for (JsonNode claim : manifest.path("claims")) {
      require(
          "pass".equals(claim.path("status").stringValue()), "non-pass claim cannot be published");
      require(
          expectedCommand.equals(claim.path("command").stringValue()),
          "evidence claim command changed");
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
    require(artifactPaths.equals(EXPECTED_ARTIFACT_PATHS), "evidence artifact set changed");
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
    requireProperty(properties, "unit", "M02");
    requireProperty(properties, "planVersion", "0.4");
    requireProperty(properties, "lifecycle", "CODE_VERIFIED");
    requireProperty(properties, "designDepth", "CONTRACT");
    requireProperty(properties, "startRef", "course/m02-start");
    requireProperty(properties, "completeRef", unitTag);
    requireProperty(properties, "m02Check.expectedStatus", "PASS");
    requireProperty(properties, "evidencePath", "build/lab-evidence/M02/manifest.json");
  }

  private static void verifyAnnotatedCompleteTag(Path root, String unitTag, String sourceCommit) {
    require(
        "tag".equals(git(root, "cat-file", "-t", unitTag).strip()),
        "M02 complete tag must be annotated");
    require(
        sourceCommit.equals(git(root, "rev-list", "-n", "1", unitTag).strip()),
        "M02 complete tag must peel to HEAD");
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
      return Files.createTempDirectory(parent, ".M02-staging-");
    } catch (IOException exception) {
      throw new IllegalStateException("cannot create M02 evidence staging directory", exception);
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
      throw new IllegalStateException("cannot publish M02 evidence directory", exception);
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
      throw new IllegalStateException("cannot inspect M02 evidence directory", exception);
    }
  }

  private static void deleteTree(Path root) {
    try (var paths = Files.walk(root)) {
      for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
        Files.delete(path);
      }
    } catch (IOException exception) {
      throw new IllegalStateException("cannot replace prior M02 evidence directory", exception);
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

  private static void requireProperty(Properties properties, String key, String expected) {
    require(expected.equals(properties.getProperty(key)), "unexpected course property: " + key);
  }

  private static void requireClean(Path root) {
    String status = git(root, "status", "--porcelain", "--untracked-files=normal");
    require(status.isBlank(), "m02Evidence requires a clean working tree");
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
