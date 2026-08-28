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

/** Generates and verifies the clean-tree evidence manifest for M03. */
public final class M03EvidenceWriter {
  static final String PRODUCT_RELEASE = "matching-0.1.0";
  static final List<String> REQUIRED_CLAIMS =
      List.of(
          "m00-m02-regression",
          "independent-reference-model",
          "generated-property-suite",
          "quantity-lifecycle-invariants",
          "minimal-counterexamples",
          "counterexample-replay",
          "semantic-mutants",
          "architecture-boundary");
  static final List<String> LIMITATIONS =
      List.of(
          "Only one in-memory BTC-USDT GTC limit-order book with place and cancel is implemented.",
          "The generated suite is frozen at 256 histories by 64 commands and is bounded testing, not exhaustive or formal verification.",
          "Accepted order IDs are unique for one engine lifetime; terminal identity records are retained without pruning.",
          "A repeated Place is rejected as a duplicate order ID; durable command idempotency and prior-result replay are not implemented.",
          "There is no Cancel/Replace, amendment, mass cancel, IOC, FOK, post-only, market order, STP, market state, price band, or multi-instrument routing.",
          "There is no account, asset, position, fee, settlement, reservation, or risk logic.",
          "The unit has no persistence, networking, database, threads, Aeron, cluster replication, or high availability.",
          "The evidence makes no throughput, latency, recovery, durability, or production-readiness claim.");
  static final List<String> REPORT_ARTIFACTS =
      List.of(
          "m00-m02-regression.json",
          "reference-model.json",
          "generated-properties.json",
          "invariants.json",
          "counterexamples.json",
          "counterexamples.canonical.utf8",
          "replay.json",
          "mutants.json",
          "architecture.json",
          "check.json");
  static final Set<String> EXPECTED_ARTIFACT_PATHS =
      Set.of(
          "inputs/property-suite-v1.json",
          "inputs/counterexamples-v1.json",
          "reports/m00-m02-regression.json",
          "reports/reference-model.json",
          "reports/generated-properties.json",
          "reports/invariants.json",
          "reports/counterexamples.json",
          "reports/counterexamples.canonical.utf8",
          "reports/replay.json",
          "reports/mutants.json",
          "reports/architecture.json",
          "reports/check.json");

  private static final Pattern UNIT_TAG = Pattern.compile("^course/m03-complete$");
  private static final Pattern FULL_GIT_COMMIT = Pattern.compile("^[a-f0-9]{40}(?:[a-f0-9]{24})?$");
  private static final String PROPERTY_SUITE =
      "matching-testkit/src/test/resources/m03/fixtures/property-suite-v1.json";
  private static final String COUNTEREXAMPLES = "counterexamples-v1.json";
  private final CheckExecutor checkExecutor;

  public M03EvidenceWriter() {
    this((root, reports) -> new M03CheckRunner().run(root, reports));
  }

  M03EvidenceWriter(CheckExecutor checkExecutor) {
    this.checkExecutor = java.util.Objects.requireNonNull(checkExecutor, "checkExecutor");
  }

  public Result write(
      Path repositoryRoot,
      Path checkDirectory,
      Path evidenceDirectory,
      String unitTag,
      String productReleaseTag) {
    Path root = repositoryRoot.toAbsolutePath().normalize();
    Path destination = evidenceDirectory.toAbsolutePath().normalize();
    require(UNIT_TAG.matcher(unitTag).matches(), "invalid M03 complete tag: " + unitTag);
    require(
        PRODUCT_RELEASE.equals(productReleaseTag),
        "invalid M03 product release tag: " + productReleaseTag);
    requireClean(root);
    String sourceCommit = git(root, "rev-parse", "HEAD").strip();
    require(FULL_GIT_COMMIT.matcher(sourceCommit).matches(), "HEAD is not a full Git commit");
    verifyCourseProperties(root, unitTag);
    verifyAnnotatedTag(root, unitTag, sourceCommit, "M03 complete tag");
    verifyAnnotatedTag(root, PRODUCT_RELEASE, sourceCommit, "M03 product release tag");

    Path checkOutput = checkDirectory.toAbsolutePath().normalize();
    require(
        checkOutput.equals(root.resolve("build/reports/m03")),
        "check directory must be the fixed M03 report path");
    M03CheckRunner.Result freshCheck = checkExecutor.run(root, checkOutput);
    require(
        M03CheckRunner.PASS.equals(freshCheck.status()), "fresh in-process m03Check is not PASS");
    require(
        freshCheck
            .reportPath()
            .toAbsolutePath()
            .normalize()
            .equals(checkOutput.resolve("check.json")),
        "fresh in-process m03Check returned an unexpected report path");
    requireSafeExistingTree(root, checkOutput);
    JsonNode check = parseAndValidateCheck(root, checkOutput.resolve("check.json"), unitTag);
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
          "HEAD changed while generating M03 evidence");
      verifyAnnotatedTag(root, unitTag, sourceCommit, "M03 complete tag");
      verifyAnnotatedTag(root, PRODUCT_RELEASE, sourceCommit, "M03 product release tag");
      requireClean(root);
      publishStagingDirectory(root, staging, destination);
      try {
        require(
            sourceCommit.equals(git(root, "rev-parse", "HEAD").strip()),
            "HEAD changed while publishing M03 evidence");
        verifyAnnotatedTag(root, unitTag, sourceCommit, "M03 complete tag");
        verifyAnnotatedTag(root, PRODUCT_RELEASE, sourceCommit, "M03 product release tag");
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

  private static JsonNode parseAndValidateCheck(Path root, Path checkPath, String unitTag) {
    JsonNode check = JsonSupport.parse(readBytes(checkPath));
    JsonSupport.validate(
        check, readString(root.resolve("schemas/matching.m03.check.v2.schema.json")), false);
    require(
        "matching.m03.check.v2".equals(check.path("schemaVersion").stringValue()),
        "m03Check schema changed");
    require("PASS".equals(check.path("status").stringValue()), "m03Check is not PASS");
    require("M03".equals(check.path("unit").stringValue()), "m03Check unit changed");
    require(
        "0.5".equals(check.path("contractPlanVersion").stringValue()),
        "m03Check contract plan changed");
    JsonNode releaseTarget = check.path("releaseTarget");
    require(
        unitTag.equals(releaseTarget.path("unitTag").stringValue()),
        "m03Check unit tag target changed");
    require(
        PRODUCT_RELEASE.equals(releaseTarget.path("productRelease").stringValue()),
        "m03Check product release target changed");
    require(
        "M03_EVIDENCE_ONLY".equals(releaseTarget.path("verification").stringValue()),
        "m03Check release verification authority changed");
    return check;
  }

  private static void prepareEvidenceFiles(Path root, Path checkDirectory, Path evidenceDirectory) {
    try {
      Path reports = evidenceDirectory.resolve("reports");
      Path inputs = evidenceDirectory.resolve("inputs");
      Files.createDirectories(reports);
      Files.createDirectories(inputs);
      for (String name : REPORT_ARTIFACTS) {
        copyRequired(checkDirectory.resolve(name), reports.resolve(name), "M03 report artifact");
      }
      Path propertySuite = root.resolve(PROPERTY_SUITE);
      SafeOutputPaths.requireNoSymlinkComponents(root, propertySuite);
      copyRequired(
          propertySuite, inputs.resolve("property-suite-v1.json"), "M03 property-suite input");
      copyRequired(
          checkDirectory.resolve(COUNTEREXAMPLES),
          inputs.resolve("counterexamples-v1.json"),
          "M03 counterexample input");
    } catch (IOException exception) {
      throw new IllegalStateException("cannot prepare M03 evidence artifacts", exception);
    }
  }

  private static void copyRequired(Path source, Path destination, String kind) throws IOException {
    require(
        Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS), "missing " + kind + ": " + source);
    Files.copy(source, destination, StandardCopyOption.REPLACE_EXISTING);
  }

  private static ObjectNode manifest(
      String unitTag, String sourceCommit, JsonNode check, Path evidenceDirectory) {
    ObjectNode root = JsonSupport.MAPPER.createObjectNode();
    root.put("schemaVersion", "cex.lab-evidence.v1");
    root.put("case", "high-availability-cex");
    root.put("project", "matching");
    root.put("unit", "M03");
    root.put("unitTag", unitTag);
    root.put("productRelease", PRODUCT_RELEASE);
    root.put("planVersion", "0.5");

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
        "m00-m02-regression",
        "correctness",
        "The completed M00 validation, M01 matching, and M02 cancellation contracts remain PASS under M03.",
        passObservation(check, "m02Regression"),
        evidenceDirectory,
        unitTag,
        List.of("reports/m00-m02-regression.json"));
    addClaim(
        claims,
        "independent-reference-model",
        "correctness",
        "A flat-list linear-scan reference model with no production project dependency independently computes every expected outcome.",
        passObservation(check, "independence"),
        evidenceDirectory,
        unitTag,
        List.of("reports/reference-model.json"));
    addClaim(
        claims,
        "generated-property-suite",
        "correctness",
        "The frozen SplitMix64 suite agrees with the reference model at all 16,384 command boundaries and regenerates deterministically.",
        generatedObservation(check),
        evidenceDirectory,
        unitTag,
        List.of("inputs/property-suite-v1.json", "reports/generated-properties.json"));
    addClaim(
        claims,
        "quantity-lifecycle-invariants",
        "correctness",
        "An event-derived ledger checks quantity partition, lifecycle, book ordering, FIFO, identity bijection, and uncrossed boundaries after every command.",
        passObservation(check, "properties"),
        evidenceDirectory,
        unitTag,
        List.of("reports/invariants.json"));
    addClaim(
        claims,
        "minimal-counterexamples",
        "correctness",
        "Every required semantic mutant has one persisted one-minimal counterexample with a stable property fingerprint.",
        passObservation(check, "counterexamples"),
        evidenceDirectory,
        unitTag,
        List.of("inputs/counterexamples-v1.json", "reports/counterexamples.canonical.utf8"));
    addClaim(
        claims,
        "counterexample-replay",
        "correctness",
        "Strict replay from persisted bytes reproduces all six STUDENT_FAILURE fingerprints on fresh candidates.",
        passObservation(check, "counterexamples"),
        evidenceDirectory,
        unitTag,
        List.of("reports/replay.json"));
    addClaim(
        claims,
        "semantic-mutants",
        "mutation-testing",
        "All six required business mutants are killed while a throwing control remains SYSTEM_ERROR.",
        passObservation(check, "mutants"),
        evidenceDirectory,
        unitTag,
        List.of("reports/mutants.json", "reports/counterexamples.json"));
    addClaim(
        claims,
        "architecture-boundary",
        "architecture",
        "The M02 core boundary is preserved and matching-reference remains structurally independent; both annotated release refs identify this commit.",
        architectureObservation(check, unitTag),
        evidenceDirectory,
        unitTag,
        List.of("reports/architecture.json", "reports/check.json"));

    ArrayNode limitations = root.putArray("limitations");
    LIMITATIONS.forEach(limitations::add);
    root.putNull("supersedes");
    root.put("generatedAt", Instant.now().toString());
    return root;
  }

  private static ObjectNode generatedObservation(JsonNode check) {
    ObjectNode result = passObservation(check, "properties");
    result.set("generator", requiredObject(check, "generator").deepCopy());
    result.set("determinism", requiredObject(check, "determinism").deepCopy());
    return result;
  }

  private static ObjectNode architectureObservation(JsonNode check, String unitTag) {
    ObjectNode result = passObservation(check, "architecture");
    ObjectNode release = result.putObject("release");
    release.put("unitTag", unitTag);
    release.put("productRelease", PRODUCT_RELEASE);
    release.put("sameCommit", true);
    release.put("verifiedBy", "M03EvidenceWriter");
    return result;
  }

  private static ObjectNode passObservation(JsonNode check, String field) {
    ObjectNode observation = requiredObject(check, field).deepCopy();
    require(
        "PASS".equals(observation.path("status").stringValue()),
        "m03Check observation is not PASS: " + field);
    return observation;
  }

  private static ObjectNode requiredObject(JsonNode check, String field) {
    JsonNode source = check.path(field);
    require(source.isObject(), "m03Check observation is missing or malformed: " + field);
    return (ObjectNode) source;
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
        "command",
        "./gradlew m03Check m03Evidence -Pm03.unitTag="
            + unitTag
            + " -Pm03.productRelease="
            + PRODUCT_RELEASE
            + " --no-daemon");
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
    require("M03".equals(manifest.path("unit").stringValue()), "evidence unit is not M03");
    require(unitTag.equals(manifest.path("unitTag").stringValue()), "evidence unitTag changed");
    require(
        PRODUCT_RELEASE.equals(manifest.path("productRelease").stringValue()),
        "evidence product release changed");
    require("0.5".equals(manifest.path("planVersion").stringValue()), "evidence plan changed");
    require(
        sourceCommit.equals(manifest.path("source").path("commit").stringValue()),
        "evidence source changed");
    require(!manifest.path("source").path("dirty").booleanValue(), "dirty evidence is forbidden");
    require(
        manifest.path("supersedes").isNull(),
        "initial M03 evidence cannot supersede another bundle");

    List<String> limitations = new ArrayList<>();
    manifest.path("limitations").forEach(node -> limitations.add(node.stringValue()));
    require(LIMITATIONS.equals(limitations), "evidence limitations changed");

    Set<String> claimIds = new LinkedHashSet<>();
    Set<String> artifactPaths = new LinkedHashSet<>();
    String expectedCommand =
        "./gradlew m03Check m03Evidence -Pm03.unitTag="
            + unitTag
            + " -Pm03.productRelease="
            + PRODUCT_RELEASE
            + " --no-daemon";
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
    requireProperty(properties, "unit", "M03");
    requireProperty(properties, "planVersion", "0.5");
    requireProperty(properties, "lifecycle", "CODE_VERIFIED");
    requireProperty(properties, "designDepth", "IMPLEMENTED");
    requireProperty(properties, "startRef", "course/m03-start");
    requireProperty(properties, "completeRef", unitTag);
    requireProperty(properties, "productRelease", PRODUCT_RELEASE);
    requireProperty(properties, "m03Check.expectedStatus", "PASS");
    requireProperty(properties, "evidencePath", "build/lab-evidence/M03/manifest.json");
  }

  private static void verifyAnnotatedTag(
      Path root, String tag, String sourceCommit, String description) {
    require(
        "tag".equals(git(root, "cat-file", "-t", tag).strip()), description + " must be annotated");
    require(
        sourceCommit.equals(git(root, "rev-list", "-n", "1", tag).strip()),
        description + " must peel to HEAD");
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
      return Files.createTempDirectory(parent, ".M03-staging-");
    } catch (IOException exception) {
      throw new IllegalStateException("cannot create M03 evidence staging directory", exception);
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
      throw new IllegalStateException("cannot publish M03 evidence directory", exception);
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
      throw new IllegalStateException("cannot inspect M03 evidence directory", exception);
    }
  }

  private static void deleteTree(Path root) {
    try (var paths = Files.walk(root)) {
      for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
        Files.delete(path);
      }
    } catch (IOException exception) {
      throw new IllegalStateException("cannot replace prior M03 evidence directory", exception);
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
    require(status.isBlank(), "m03Evidence requires a clean working tree");
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

  @FunctionalInterface
  interface CheckExecutor {
    M03CheckRunner.Result run(Path repositoryRoot, Path reportDirectory);
  }
}
