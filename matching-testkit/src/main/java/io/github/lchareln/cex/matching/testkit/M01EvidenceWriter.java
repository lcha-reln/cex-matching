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

/** Generates and verifies the clean-tree evidence manifest for M01. */
public final class M01EvidenceWriter {
  static final List<String> REQUIRED_CLAIMS =
      List.of(
          "m00-input-regression",
          "price-time-priority",
          "matching-event-batches",
          "quantity-and-book-invariants",
          "deterministic-event-history",
          "semantic-mutants",
          "architecture-boundary");
  static final List<String> LIMITATIONS =
      List.of(
          "Only one in-memory BTC-USDT GTC limit-order book is implemented.",
          "Scenario order IDs are unique; duplicate IDs, duplicate commands, and addressable lifecycle semantics are outside M01.",
          "There is no cancel, amendment, order index, IOC, FOK, post-only, market order, STP, market state, or price band.",
          "There is no account, asset, position, fee, settlement, or risk logic.",
          "Fixed scenarios and semantic mutants are not the independent generated reference model or property proof deferred to M03.",
          "The unit has no persistence, networking, database, threads, Aeron, or high availability.",
          "The evidence makes no throughput, latency, recovery, or production-readiness claim.");

  private static final Pattern UNIT_TAG =
      Pattern.compile("^course/m01(?:\\.[1-9][0-9]*)?-complete$");
  private static final List<String> REPORT_ARTIFACTS =
      List.of(
          "check.json",
          "m00-regression.json",
          "price-time.json",
          "event-batches.json",
          "invariants.json",
          "canonical-history.utf8",
          "mutants.json",
          "architecture.json");

  public Result write(
      Path repositoryRoot, Path checkDirectory, Path evidenceDirectory, String unitTag) {
    Path root = repositoryRoot.toAbsolutePath().normalize();
    Path destination = evidenceDirectory.toAbsolutePath().normalize();
    require(UNIT_TAG.matcher(unitTag).matches(), "invalid M01 complete tag: " + unitTag);
    requireClean(root);
    String sourceCommit = git(root, "rev-parse", "HEAD").strip();
    require(sourceCommit.matches("^[a-f0-9]{40}$"), "HEAD is not a full Git commit");
    verifyCourseProperties(root, unitTag);

    Path checkOutput = checkDirectory.toAbsolutePath().normalize();
    require(
        checkOutput.equals(root.resolve("build/reports/m01")),
        "check directory must be the fixed M01 report path");
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
        check, readString(root.resolve("schemas/matching.m01.check.v2.schema.json")), false);
    require("PASS".equals(check.path("status").stringValue()), "m01Check is not PASS");
    require("M01".equals(check.path("unit").stringValue()), "m01Check unit changed");
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
        require(Files.isRegularFile(source), "missing M01 report artifact: " + source);
        Files.copy(source, reports.resolve(name), StandardCopyOption.REPLACE_EXISTING);
      }
      Files.copy(
          root.resolve("matching-testkit/src/test/resources/m01/fixtures/price-time-v1.json"),
          inputs.resolve("price-time-v1.json"),
          StandardCopyOption.REPLACE_EXISTING);
    } catch (IOException exception) {
      throw new IllegalStateException("cannot prepare M01 evidence artifacts", exception);
    }
  }

  private static ObjectNode manifest(
      String unitTag, String sourceCommit, JsonNode check, Path evidenceDirectory) {
    ObjectNode root = JsonSupport.MAPPER.createObjectNode();
    root.put("schemaVersion", "cex.lab-evidence.v1");
    root.put("case", "high-availability-cex");
    root.put("project", "matching");
    root.put("unit", "M01");
    root.put("unitTag", unitTag);
    root.putNull("productRelease");
    root.put("planVersion", "0.3");

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
        "m00-input-regression",
        "correctness",
        "Every frozen M00-invalid input remains Rejected without book mutation or acceptance-sequence consumption.",
        observation(check, "m00Regression"),
        evidenceDirectory,
        unitTag,
        List.of("reports/m00-regression.json"));
    addClaim(
        claims,
        "price-time-priority",
        "correctness",
        "The frozen corpus matches best-price then acceptance-sequence priority at maker price.",
        observation(check, "scenarioCorpus"),
        evidenceDirectory,
        unitTag,
        List.of("inputs/price-time-v1.json", "reports/price-time.json"));
    addClaim(
        claims,
        "matching-event-batches",
        "correctness",
        "Every command emits the frozen Rejected or Accepted, Trade*, Rested? event grammar.",
        observation(check, "eventBatches"),
        evidenceDirectory,
        unitTag,
        List.of("reports/event-batches.json"));
    addClaim(
        claims,
        "quantity-and-book-invariants",
        "correctness",
        "Positive trades, quantity conservation, ordered queues, no empty levels, and no crossed book hold after every batch.",
        observation(check, "invariants"),
        evidenceDirectory,
        unitTag,
        List.of("reports/invariants.json"));
    addClaim(
        claims,
        "deterministic-event-history",
        "correctness",
        "One hundred fresh corpus parses and fresh engines produce one M01H1 byte history and digest.",
        observation(check, "replays"),
        evidenceDirectory,
        unitTag,
        List.of("reports/canonical-history.utf8", "reports/check.json"));
    addClaim(
        claims,
        "semantic-mutants",
        "mutation-testing",
        "LIFO, taker-price, and skipped-maker mutants are killed by business assertions; system errors do not count.",
        observation(check, "mutants"),
        evidenceDirectory,
        unitTag,
        List.of("reports/mutants.json"));
    addClaim(
        claims,
        "architecture-boundary",
        "architecture",
        "matching-core remains a deterministic two-module business core without I/O or future runtime dependencies.",
        observation(check, "architecture"),
        evidenceDirectory,
        unitTag,
        List.of("reports/architecture.json"));

    ArrayNode limitations = root.putArray("limitations");
    LIMITATIONS.forEach(limitations::add);
    root.putNull("supersedes");
    root.put("generatedAt", Instant.now().toString());
    return root;
  }

  private static ObjectNode observation(JsonNode check, String field) {
    JsonNode source = check.path(field);
    if (source.isObject()) {
      return (ObjectNode) source.deepCopy();
    }
    ObjectNode observation = JsonSupport.MAPPER.createObjectNode();
    observation.put("report", "reports/check.json");
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
        "command", "./gradlew m01Check m01Evidence -Pm01.unitTag=" + unitTag + " --no-daemon");
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
    require("M01".equals(manifest.path("unit").stringValue()), "evidence unit is not M01");
    require(unitTag.equals(manifest.path("unitTag").stringValue()), "evidence unitTag changed");
    require(manifest.path("productRelease").isNull(), "M01 must not publish a product release");
    require("0.3".equals(manifest.path("planVersion").stringValue()), "evidence plan changed");
    require(
        sourceCommit.equals(manifest.path("source").path("commit").stringValue()),
        "evidence source changed");
    require(!manifest.path("source").path("dirty").booleanValue(), "dirty evidence is forbidden");
    require(
        manifest.path("supersedes").isNull(),
        "initial M01 evidence cannot supersede another bundle");

    List<String> limitations = new ArrayList<>();
    manifest.path("limitations").forEach(node -> limitations.add(node.stringValue()));
    require(LIMITATIONS.equals(limitations), "evidence limitations changed");

    Set<String> claimIds = new LinkedHashSet<>();
    Set<String> artifactPaths = new LinkedHashSet<>();
    String expectedCommand =
        "./gradlew m01Check m01Evidence -Pm01.unitTag=" + unitTag + " --no-daemon";
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
    requireProperty(properties, "unit", "M01");
    requireProperty(properties, "planVersion", "0.3");
    requireProperty(properties, "lifecycle", "CODE_VERIFIED");
    requireProperty(properties, "designDepth", "CONTRACT");
    requireProperty(properties, "startRef", "course/m01-start");
    requireProperty(properties, "completeRef", unitTag);
    requireProperty(properties, "m01Check.expectedStatus", "PASS");
    requireProperty(properties, "evidencePath", "build/lab-evidence/M01/manifest.json");
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
      return Files.createTempDirectory(parent, ".M01-staging-");
    } catch (IOException exception) {
      throw new IllegalStateException("cannot create M01 evidence staging directory", exception);
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
      throw new IllegalStateException("cannot publish M01 evidence directory", exception);
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
      throw new IllegalStateException("cannot inspect M01 evidence directory", exception);
    }
  }

  private static void deleteTree(Path root) {
    try (var paths = Files.walk(root)) {
      for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
        Files.delete(path);
      }
    } catch (IOException exception) {
      throw new IllegalStateException("cannot replace prior M01 evidence directory", exception);
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
    require(status.isBlank(), "m01Evidence requires a clean working tree");
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
