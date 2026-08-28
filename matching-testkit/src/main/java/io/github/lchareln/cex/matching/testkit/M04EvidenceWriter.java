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

/** Generates clean-tree M04 evidence bound only to annotated course/m04-complete. */
public final class M04EvidenceWriter {
  static final String UNIT_TAG = "course/m04-complete";
  static final List<String> REQUIRED_CLAIMS =
      List.of(
          "m00-m03-semantic-regression",
          "fixed-golden-history",
          "generated-property-suite",
          "policy-invariants-and-boundaries",
          "minimal-counterexamples",
          "counterexample-replay",
          "semantic-mutants",
          "architecture-boundary");
  static final List<String> LIMITATIONS =
      List.of(
          "Only one in-memory BTC-USDT limit-order book with place and cancel is implemented.",
          "Only GTC, IOC, FOK, and Post-only execution policies are implemented; price bands enter M05 and STP enters M06.",
          "The generated suite is frozen at 192 histories by 64 commands and is bounded testing, not exhaustive or formal verification.",
          "The exact raw-policy and Long.MAX FOK boundaries are separate unit tests because generated quantities are limited to one through five.",
          "Accepted order IDs remain unique for one engine lifetime; terminal identities are retained without pruning.",
          "There is no account, asset, position, fee, margin, settlement, reservation, or risk logic.",
          "There is no persistence, networking, database, thread, Aeron, replication, or high availability in this unit.",
          "The evidence makes no throughput, latency, durability, recovery, or production-readiness claim.");
  static final List<String> REPORT_ARTIFACTS =
      List.of(
          "m00-m03-regression.json",
          "fixed-scenario-pack.json",
          "fixed-event-batches.json",
          "fixed-history.canonical.utf8",
          "generated-history.canonical.utf8",
          "generated-properties.json",
          "invariants.json",
          "coverage.json",
          "boundaries.json",
          "counterexamples.json",
          "counterexamples.canonical.utf8",
          "replay.json",
          "mutants.json",
          "architecture.json",
          "check.json");
  static final Set<String> EXPECTED_ARTIFACT_PATHS =
      Set.of(
          "inputs/execution-policy-v1.json",
          "inputs/property-suite-v1.json",
          "inputs/counterexamples-v1.json",
          "reports/m00-m03-regression.json",
          "reports/fixed-scenario-pack.json",
          "reports/fixed-event-batches.json",
          "reports/fixed-history.canonical.utf8",
          "reports/generated-history.canonical.utf8",
          "reports/generated-properties.json",
          "reports/invariants.json",
          "reports/coverage.json",
          "reports/boundaries.json",
          "reports/counterexamples.json",
          "reports/counterexamples.canonical.utf8",
          "reports/replay.json",
          "reports/mutants.json",
          "reports/architecture.json",
          "reports/check.json");

  private static final Pattern FULL_COMMIT = Pattern.compile("^[a-f0-9]{40}(?:[a-f0-9]{24})?$");
  private final CheckExecutor checkExecutor;

  public M04EvidenceWriter() {
    this((root, reports) -> new M04CheckRunner().run(root, reports));
  }

  M04EvidenceWriter(CheckExecutor checkExecutor) {
    this.checkExecutor = java.util.Objects.requireNonNull(checkExecutor, "checkExecutor");
  }

  public Result write(
      Path repositoryRoot, Path checkDirectory, Path evidenceDirectory, String unitTag) {
    Path root = repositoryRoot.toAbsolutePath().normalize();
    Path destination = evidenceDirectory.toAbsolutePath().normalize();
    require(UNIT_TAG.equals(unitTag), "invalid M04 complete tag: " + unitTag);
    requireClean(root);
    String sourceCommit = git(root, "rev-parse", "HEAD").strip();
    require(FULL_COMMIT.matcher(sourceCommit).matches(), "HEAD is not a full Git commit");
    verifyCourseProperties(root, unitTag);
    verifyAnnotatedTag(root, unitTag, sourceCommit);
    verifyNoProductReleaseTag(root);

    Path checkOutput = checkDirectory.toAbsolutePath().normalize();
    require(
        checkOutput.equals(root.resolve("build/reports/m04")),
        "check directory must be the fixed M04 report path");
    M04CheckRunner.Result fresh = checkExecutor.run(root, checkOutput);
    require(M04CheckRunner.PASS.equals(fresh.status()), "fresh in-process m04Check is not PASS");
    require(
        fresh.reportPath().toAbsolutePath().normalize().equals(checkOutput.resolve("check.json")),
        "fresh m04Check returned an unexpected path");
    requireSafeTree(root, checkOutput);
    JsonNode check = parseCheck(root, checkOutput.resolve("check.json"), unitTag);

    Path staging = createStaging(root, destination);
    try {
      prepare(root, checkOutput, staging);
      ObjectNode manifest = manifest(unitTag, sourceCommit, check, staging);
      JsonSupport.validate(
          manifest, readString(root.resolve("schemas/cex.lab-evidence.v1.schema.json")), true);
      verifyManifest(manifest, staging, sourceCommit, unitTag);
      AtomicFiles.write(staging.resolve("manifest.json"), JsonSupport.prettyBytes(manifest));
      recheck(root, sourceCommit, unitTag);
      publish(root, staging, destination);
      try {
        recheck(root, sourceCommit, unitTag);
      } catch (RuntimeException failure) {
        deleteQuietly(destination);
        throw failure;
      }
      Path manifestPath = destination.resolve("manifest.json");
      return new Result(manifestPath, sourceCommit, Hashing.sha256Hex(readBytes(manifestPath)));
    } catch (RuntimeException failure) {
      deleteQuietly(staging);
      throw failure;
    }
  }

  private static JsonNode parseCheck(Path root, Path checkPath, String unitTag) {
    JsonNode check = JsonSupport.parse(readBytes(checkPath));
    JsonSupport.validate(check, readString(root.resolve(M04CheckRunner.CHECK_SCHEMA_PATH)), false);
    require(
        M04CheckRunner.SCHEMA_VERSION.equals(check.path("schemaVersion").stringValue()),
        "m04Check schema changed");
    require(M04CheckRunner.PASS.equals(check.path("status").stringValue()), "m04Check is not PASS");
    require("M04".equals(check.path("unit").stringValue()), "m04Check unit changed");
    require("0.6".equals(check.path("contractPlanVersion").stringValue()), "m04Check plan changed");
    JsonNode release = check.path("releaseTarget");
    require(unitTag.equals(release.path("unitTag").stringValue()), "m04Check unit tag changed");
    require(release.path("productRelease").isNull(), "M04 must not claim a product release");
    require(
        "M04_EVIDENCE_ONLY".equals(release.path("verification").stringValue()),
        "m04Check verification authority changed");
    return check;
  }

  private static void prepare(Path root, Path checkDirectory, Path staging) {
    try {
      Path reports = staging.resolve("reports");
      Path inputs = staging.resolve("inputs");
      Files.createDirectories(reports);
      Files.createDirectories(inputs);
      for (String name : REPORT_ARTIFACTS) {
        copy(checkDirectory.resolve(name), reports.resolve(name), "M04 report artifact");
      }
      copy(
          root.resolve(M04StartCheckRunner.FIXED_CORPUS_PATH),
          inputs.resolve("execution-policy-v1.json"),
          "M04 fixed input");
      copy(
          root.resolve(M04StartCheckRunner.GENERATOR_PATH),
          inputs.resolve("property-suite-v1.json"),
          "M04 generated input");
      copy(
          checkDirectory.resolve("counterexamples-v1.json"),
          inputs.resolve("counterexamples-v1.json"),
          "M04 counterexample input");
    } catch (IOException failure) {
      throw new IllegalStateException("cannot prepare M04 evidence artifacts", failure);
    }
  }

  private static void copy(Path source, Path target, String kind) throws IOException {
    require(
        Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS), "missing " + kind + ": " + source);
    Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
  }

  private static ObjectNode manifest(
      String unitTag, String sourceCommit, JsonNode check, Path staging) {
    ObjectNode root = JsonSupport.MAPPER.createObjectNode();
    root.put("schemaVersion", "cex.lab-evidence.v1");
    root.put("case", "high-availability-cex");
    root.put("project", "matching");
    root.put("unit", "M04");
    root.put("unitTag", unitTag);
    root.putNull("productRelease");
    root.put("planVersion", "0.6");
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
        "m00-m03-semantic-regression",
        "correctness",
        "The frozen M03 command bytes, neutral GTC business semantics, six mutants, and six minimal counterexamples remain valid without rebinding its historical architecture gate.",
        observation(check, "inheritedM03"),
        staging,
        unitTag,
        List.of("reports/m00-m03-regression.json"));
    addClaim(
        claims,
        "fixed-golden-history",
        "correctness",
        "All 14 fixed scenarios and 48 commands agree with the independent model and publish replayable scenario-pack, event-batch, and M04F1 artifacts.",
        observation(check, "fixedCorpus"),
        staging,
        unitTag,
        List.of(
            "inputs/execution-policy-v1.json",
            "reports/fixed-scenario-pack.json",
            "reports/fixed-event-batches.json",
            "reports/fixed-history.canonical.utf8"));
    addClaim(
        claims,
        "generated-property-suite",
        "correctness",
        "The frozen SplitMix64 suite regenerates the same M04H1 bytes and agrees at all 12,288 command boundaries.",
        combinedObservation(check, "properties", "generator"),
        staging,
        unitTag,
        List.of(
            "inputs/property-suite-v1.json",
            "reports/generated-history.canonical.utf8",
            "reports/generated-properties.json"));
    addClaim(
        claims,
        "policy-invariants-and-boundaries",
        "correctness",
        "The event ledger, semantic pre-state coverage, exact raw-policy grammar, and separate Long.MAX FOK paths pass.",
        combinedObservation(check, "coverage", "boundaries"),
        staging,
        unitTag,
        List.of("reports/invariants.json", "reports/coverage.json", "reports/boundaries.json"));
    addClaim(
        claims,
        "minimal-counterexamples",
        "correctness",
        "All eight required semantic faults have generated-source, one-minimal, schema-valid counterexamples with M04X1 bytes.",
        observation(check, "counterexamples"),
        staging,
        unitTag,
        List.of(
            "inputs/counterexamples-v1.json",
            "reports/counterexamples.json",
            "reports/counterexamples.canonical.utf8"));
    addClaim(
        claims,
        "counterexample-replay",
        "correctness",
        "Strict replay regenerates provenance and reproduces all eight expected STUDENT_FAILURE fingerprints.",
        observation(check, "counterexamples"),
        staging,
        unitTag,
        List.of("reports/replay.json"));
    addClaim(
        claims,
        "semantic-mutants",
        "mutation-testing",
        "All eight business mutants are killed as STUDENT_FAILURE while a throwing control remains SYSTEM_ERROR.",
        observation(check, "mutants"),
        staging,
        unitTag,
        List.of("reports/mutants.json"));
    addClaim(
        claims,
        "architecture-boundary",
        "architecture",
        "The deterministic dependency-free core and independent flat-list reference model pass at the annotated M04 unit tag without a product release.",
        observation(check, "architecture"),
        staging,
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
    require(source.isObject(), "missing m04Check observation: " + field);
    require("PASS".equals(source.path("status").stringValue()), "non-PASS observation: " + field);
    return ((ObjectNode) source).deepCopy();
  }

  private static ObjectNode combinedObservation(
      JsonNode check, String primaryField, String companionField) {
    ObjectNode result = observation(check, primaryField);
    JsonNode companion = check.path(companionField);
    require(companion.isObject(), "missing m04Check observation: " + companionField);
    result.set(companionField, companion.deepCopy());
    return result;
  }

  private static void addClaim(
      ArrayNode claims,
      String id,
      String category,
      String statement,
      ObjectNode observations,
      Path staging,
      String unitTag,
      List<String> artifacts) {
    ObjectNode claim = claims.addObject();
    claim.put("id", id);
    claim.put("category", category);
    claim.put("statement", statement);
    claim.put("status", "pass");
    claim.put(
        "command", "./gradlew m04Check m04Evidence -Pm04.unitTag=" + unitTag + " --no-daemon");
    claim.set("observations", observations);
    ArrayNode files = claim.putArray("artifacts");
    for (String relative : artifacts) {
      ObjectNode artifact = files.addObject();
      artifact.put("path", relative);
      artifact.put("sha256", Hashing.sha256Hex(readBytes(staging.resolve(relative))));
    }
  }

  private static void verifyManifest(
      JsonNode manifest, Path staging, String sourceCommit, String unitTag) {
    require("M04".equals(manifest.path("unit").stringValue()), "evidence unit changed");
    require(unitTag.equals(manifest.path("unitTag").stringValue()), "evidence unit tag changed");
    require(manifest.path("productRelease").isNull(), "M04 evidence claimed product release");
    require("0.6".equals(manifest.path("planVersion").stringValue()), "evidence plan changed");
    require(
        sourceCommit.equals(manifest.path("source").path("commit").stringValue()),
        "evidence source changed");
    require(manifest.path("supersedes").isNull(), "M04 initial evidence cannot supersede");
    require(
        LIMITATIONS.equals(
            manifest.path("limitations").valueStream().map(JsonNode::stringValue).toList()),
        "M04 evidence limitations changed");
    Set<String> claimIds = new LinkedHashSet<>();
    Set<String> paths = new LinkedHashSet<>();
    String command = "./gradlew m04Check m04Evidence -Pm04.unitTag=" + unitTag + " --no-daemon";
    for (JsonNode claim : manifest.path("claims")) {
      require("pass".equals(claim.path("status").stringValue()), "non-pass M04 claim");
      require(command.equals(claim.path("command").stringValue()), "M04 evidence command changed");
      require(claimIds.add(claim.path("id").stringValue()), "duplicate M04 evidence claim");
      for (JsonNode artifact : claim.path("artifacts")) {
        String relative = artifact.path("path").stringValue();
        require(paths.add(relative), "duplicate M04 evidence artifact: " + relative);
        Path relativePath = Path.of(relative);
        require(!relativePath.isAbsolute() && !relative.contains(".."), "unsafe evidence path");
        Path resolved = staging.resolve(relativePath).normalize();
        require(resolved.startsWith(staging), "evidence artifact escapes staging");
        SafeOutputPaths.requireNoSymlinkComponents(staging, resolved);
        require(
            Files.isRegularFile(resolved, LinkOption.NOFOLLOW_LINKS),
            "missing artifact: " + relative);
        require(
            artifact.path("sha256").stringValue().equals(Hashing.sha256Hex(readBytes(resolved))),
            "evidence artifact hash mismatch: " + relative);
      }
    }
    require(
        claimIds.equals(new LinkedHashSet<>(REQUIRED_CLAIMS)), "M04 evidence claim order changed");
    require(paths.equals(EXPECTED_ARTIFACT_PATHS), "M04 evidence artifact set changed");
  }

  private static void verifyCourseProperties(Path root, String unitTag) {
    Properties properties = new Properties();
    try (var reader =
        Files.newBufferedReader(root.resolve("course.properties"), StandardCharsets.UTF_8)) {
      properties.load(reader);
    } catch (IOException failure) {
      throw new IllegalStateException("cannot read course.properties", failure);
    }
    property(properties, "case", "high-availability-cex");
    property(properties, "profile", "SPOT-CEX-1.0");
    property(properties, "planVersion", "0.6");
    property(properties, "project", "matching");
    property(properties, "unit", "M04");
    property(properties, "lifecycle", "CODE_VERIFIED");
    property(properties, "designDepth", "IMPLEMENTED");
    property(properties, "startRef", "course/m04-start");
    property(properties, "completeRef", unitTag);
    property(properties, "m04Check.expectedStatus", "PASS");
    property(properties, "evidencePath", "build/lab-evidence/M04/manifest.json");
    require(
        properties.getProperty("productRelease") == null, "M04 must not declare productRelease");
  }

  private static void property(Properties properties, String key, String expected) {
    require(expected.equals(properties.getProperty(key)), "unexpected course property: " + key);
  }

  private static void recheck(Path root, String sourceCommit, String unitTag) {
    require(
        sourceCommit.equals(git(root, "rev-parse", "HEAD").strip()),
        "HEAD changed during M04 evidence");
    verifyAnnotatedTag(root, unitTag, sourceCommit);
    verifyNoProductReleaseTag(root);
    requireClean(root);
  }

  private static void verifyAnnotatedTag(Path root, String tag, String sourceCommit) {
    require(
        "tag".equals(git(root, "cat-file", "-t", tag).strip()),
        "M04 complete tag must be annotated");
    require(
        sourceCommit.equals(git(root, "rev-list", "-n", "1", tag).strip()),
        "M04 complete tag must peel to HEAD");
  }

  private static void verifyNoProductReleaseTag(Path root) {
    require(
        git(root, "tag", "--points-at", "HEAD", "--list", "matching-*").isBlank(),
        "M04 HEAD must not have a matching-* product release tag");
  }

  private static Path createStaging(Path root, Path destination) {
    require(destination.startsWith(root), "M04 evidence directory must stay inside repository");
    Path parent = destination.getParent();
    require(parent != null, "M04 evidence destination has no parent");
    SafeOutputPaths.requireNoSymlinkComponents(root, parent);
    requireSafeTree(root, destination);
    try {
      Files.createDirectories(parent);
      return Files.createTempDirectory(parent, ".M04-staging-");
    } catch (IOException failure) {
      throw new IllegalStateException("cannot create M04 evidence staging", failure);
    }
  }

  private static void publish(Path root, Path staging, Path destination) {
    requireSafeTree(root, staging);
    requireSafeTree(root, destination);
    if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
      delete(destination);
    }
    try {
      try {
        Files.move(staging, destination, StandardCopyOption.ATOMIC_MOVE);
      } catch (AtomicMoveNotSupportedException ignored) {
        Files.move(staging, destination);
      }
    } catch (IOException failure) {
      throw new IllegalStateException("cannot publish M04 evidence", failure);
    }
    requireSafeTree(root, destination);
  }

  private static void requireSafeTree(Path root, Path path) {
    SafeOutputPaths.requireNoSymlinkComponents(root, path);
    if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
      return;
    }
    try (var paths = Files.walk(path)) {
      paths.forEach(
          candidate -> require(!Files.isSymbolicLink(candidate), "symlink in evidence tree"));
    } catch (IOException failure) {
      throw new IllegalStateException("cannot inspect M04 evidence tree", failure);
    }
  }

  private static void requireClean(Path root) {
    require(
        git(root, "status", "--porcelain", "--untracked-files=normal").isBlank(),
        "m04Evidence requires a clean working tree");
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
    } catch (IOException failure) {
      throw new IllegalStateException("cannot execute git", failure);
    } catch (InterruptedException failure) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("git command interrupted", failure);
    }
  }

  private static void delete(Path root) {
    try (var paths = Files.walk(root)) {
      for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
        Files.delete(path);
      }
    } catch (IOException failure) {
      throw new IllegalStateException("cannot replace M04 evidence directory", failure);
    }
  }

  private static void deleteQuietly(Path root) {
    if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
      return;
    }
    try {
      delete(root);
    } catch (RuntimeException ignored) {
      // Preserve the original failure.
    }
  }

  private static byte[] readBytes(Path path) {
    try {
      return Files.readAllBytes(path);
    } catch (IOException failure) {
      throw new IllegalStateException("cannot read " + path, failure);
    }
  }

  private static String readString(Path path) {
    return new String(readBytes(path), StandardCharsets.UTF_8);
  }

  private static void require(boolean condition, String message) {
    if (!condition) {
      throw new IllegalStateException(message);
    }
  }

  public record Result(Path manifestPath, String sourceCommit, String manifestSha256) {}

  @FunctionalInterface
  interface CheckExecutor {
    M04CheckRunner.Result run(Path root, Path reports);
  }
}
