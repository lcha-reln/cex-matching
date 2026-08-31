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
import java.util.List;
import java.util.Properties;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Generates tag-bound, clean-tree M05 evidence without claiming a product release. */
public final class M05EvidenceWriter {
  static final String UNIT_TAG = "course/m05-complete";
  static final List<String> REPORT_ARTIFACTS =
      List.of(
          "m00-m04-regression.json",
          "ruleset-hash-vectors.json",
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
  static final List<String> LIMITATIONS =
      List.of(
          "Only one in-memory BTC-USDT limit-order book with Place, Cancel, PrepareRuleSet, and ActivateRuleSet is implemented.",
          "Only a content-addressed absolute order-entry price band is governed; reference-price formulas, dynamic bands, market modes, and mass cancel are deferred.",
          "Existing resting orders are grandfathered across activation and may remain outside the newly active order-entry band.",
          "Only GTC, IOC, FOK, and Post-only are implemented; STP and later order types are deferred.",
          "The generated suite is frozen at 160 histories by 64 commands and is bounded testing, not exhaustive or formal verification.",
          "Accepted order IDs remain unique for one engine lifetime; terminal identities are retained without pruning.",
          "There is no account, asset, position, fee, margin, settlement, reservation, or risk logic.",
          "There is no persistence, networking, database, thread, Aeron, replication, failover, or high availability in this unit.",
          "The evidence makes no throughput, latency, durability, recovery, or production-readiness claim.");
  private static final Pattern FULL_COMMIT = Pattern.compile("^[a-f0-9]{40}(?:[a-f0-9]{24})?$");
  private final CheckExecutor checkExecutor;

  public M05EvidenceWriter() {
    this((root, reports) -> new M05CheckRunner().run(root, reports));
  }

  M05EvidenceWriter(CheckExecutor checkExecutor) {
    this.checkExecutor = java.util.Objects.requireNonNull(checkExecutor, "checkExecutor");
  }

  public Result write(
      Path repositoryRoot, Path checkDirectory, Path evidenceDirectory, String unitTag) {
    Path root = repositoryRoot.toAbsolutePath().normalize();
    Path reports = checkDirectory.toAbsolutePath().normalize();
    Path destination = evidenceDirectory.toAbsolutePath().normalize();
    require(UNIT_TAG.equals(unitTag), "invalid M05 complete tag: " + unitTag);
    require(
        reports.equals(root.resolve("build/reports/m05")),
        "check directory must be build/reports/m05");
    require(
        destination.equals(root.resolve("build/lab-evidence/M05")),
        "evidence directory must be build/lab-evidence/M05");
    requireClean(root);
    String sourceCommit = git(root, "rev-parse", "HEAD").strip();
    require(FULL_COMMIT.matcher(sourceCommit).matches(), "HEAD is not a full commit");
    verifyCourseContract(root);
    verifyAnnotatedTag(root, sourceCommit);
    require(
        git(root, "tag", "--points-at", "HEAD", "--list", "matching-*").isBlank(),
        "M05 must not create a product release tag");

    M05CheckRunner.Result fresh = checkExecutor.run(root, reports);
    require(M05CheckRunner.PASS.equals(fresh.status()), "fresh M05 check is not PASS");
    JsonNode check = JsonSupport.parse(readBytes(fresh.reportPath()));
    JsonSupport.validate(check, readString(root.resolve(M05CheckRunner.CHECK_SCHEMA_PATH)), false);
    require(
        "matching.m05.check.v2".equals(check.path("schemaVersion").stringValue()),
        "M05 check schema changed");
    require("M05".equals(check.path("unit").stringValue()), "M05 check unit changed");
    require(
        "0.7".equals(check.path("contractPlanVersion").stringValue()), "M05 plan version changed");
    require(
        check.path("releaseTarget").path("productRelease").isNull(),
        "M05 unexpectedly claims a product release");

    Path staging = destination.resolveSibling(".M05-staging-" + sourceCommit.substring(0, 12));
    deleteTree(staging);
    try {
      Files.createDirectories(staging.resolve("inputs"));
      Files.createDirectories(staging.resolve("reports"));
      copy(
          root.resolve(M05StartCheckRunner.FIXED_CORPUS_PATH),
          staging.resolve("inputs/versioned-price-band-v1.json"));
      copy(
          root.resolve(M05StartCheckRunner.GENERATOR_PATH),
          staging.resolve("inputs/property-suite-v1.json"));
      copy(
          reports.resolve("counterexamples-v1.json"),
          staging.resolve("inputs/counterexamples-v1.json"));
      for (String name : REPORT_ARTIFACTS) {
        copy(reports.resolve(name), staging.resolve("reports").resolve(name));
      }
      ObjectNode manifest = manifest(sourceCommit, check, staging);
      JsonSupport.validate(
          manifest, readString(root.resolve("schemas/cex.lab-evidence.v1.schema.json")), true);
      AtomicFiles.write(staging.resolve("manifest.json"), JsonSupport.prettyBytes(manifest));
      requireClean(root);
      require(
          git(root, "rev-parse", "HEAD").strip().equals(sourceCommit),
          "HEAD changed while generating M05 evidence");
      publish(staging, destination);
      requireClean(root);
      Path manifestPath = destination.resolve("manifest.json");
      return new Result(manifestPath, sourceCommit, Hashing.sha256Hex(readBytes(manifestPath)));
    } catch (IOException failure) {
      deleteTree(staging);
      throw new IllegalStateException("cannot generate M05 evidence", failure);
    }
  }

  private static ObjectNode manifest(String sourceCommit, JsonNode check, Path staging) {
    ObjectNode root = JsonSupport.MAPPER.createObjectNode();
    root.put("schemaVersion", "cex.lab-evidence.v1");
    root.put("case", "high-availability-cex");
    root.put("project", "matching");
    root.put("unit", "M05");
    root.put("unitTag", UNIT_TAG);
    root.putNull("productRelease");
    root.put("planVersion", "0.7");
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
        "m00-m04-semantic-regression",
        "correctness",
        "The frozen M04 fixed and generated semantics, coverage, mutants, and counterexample replay still pass without rebinding historical source-identity gates.",
        check.path("inheritedM04"),
        staging,
        List.of("reports/m00-m04-regression.json"));
    addClaim(
        claims,
        "content-addressed-rule-artifact",
        "correctness",
        "M05RS1 canonical bytes reproduce four frozen SHA-256 vectors and mismatched content fails closed.",
        check.path("boundaries"),
        staging,
        List.of(
            "inputs/versioned-price-band-v1.json",
            "reports/ruleset-hash-vectors.json",
            "reports/boundaries.json"));
    addClaim(
        claims,
        "prepare-activate-application-fence",
        "correctness",
        "Prepare and Activate form an ordered, monotonic control transition with an exact application-sequence fence and failure atomicity.",
        check.path("fixedCorpus"),
        staging,
        List.of(
            "reports/fixed-scenario-pack.json",
            "reports/fixed-event-batches.json",
            "reports/fixed-history.canonical.utf8"));
    addClaim(
        claims,
        "inclusive-order-entry-price-band",
        "correctness",
        "Both sides accept the inclusive lower and upper ticks and reject new orders outside the active band before policy prechecks.",
        check.path("coverage"),
        staging,
        List.of("reports/coverage.json", "reports/invariants.json"));
    addClaim(
        claims,
        "grandfathered-rule-attribution",
        "correctness",
        "Activation leaves resting orders untouched while Accepted, Trade, Rested, Cancel, and rejection events retain admission and execution rule identity.",
        check.path("invariants"),
        staging,
        List.of("reports/check.json"));
    ObjectNode generated = JsonSupport.MAPPER.createObjectNode();
    generated.set("generator", check.path("generator"));
    generated.set("properties", check.path("properties"));
    addClaim(
        claims,
        "generated-property-suite",
        "correctness",
        "Two fresh SplitMix64 generations produce identical M05H1 bytes and production agrees with the independent model and event ledger at all 10,240 command boundaries.",
        generated,
        staging,
        List.of(
            "inputs/property-suite-v1.json",
            "reports/generated-history.canonical.utf8",
            "reports/generated-properties.json"));
    ObjectNode counterexample = JsonSupport.MAPPER.createObjectNode();
    counterexample.set("counterexamples", check.path("counterexamples"));
    counterexample.set("mutants", check.path("mutants"));
    addClaim(
        claims,
        "minimal-counterexamples",
        "correctness",
        "All eight required semantic faults are killed as STUDENT_FAILURE by schema-valid, replayed, one-minimal M05X1 counterexamples; the throwing control remains SYSTEM_ERROR.",
        counterexample,
        staging,
        List.of(
            "inputs/counterexamples-v1.json",
            "reports/counterexamples.json",
            "reports/counterexamples.canonical.utf8",
            "reports/replay.json",
            "reports/mutants.json"));
    addClaim(
        claims,
        "architecture-boundary",
        "architecture",
        "The production core and independent reference remain free of file, network, database, time, concurrency, and Aeron dependencies.",
        check.path("architecture"),
        staging,
        List.of("reports/architecture.json"));
    ArrayNode limitations = root.putArray("limitations");
    LIMITATIONS.forEach(limitations::add);
    root.putNull("supersedes");
    root.put("generatedAt", Instant.now().toString());
    return root;
  }

  private static void addClaim(
      ArrayNode claims,
      String id,
      String category,
      String statement,
      JsonNode observations,
      Path staging,
      List<String> artifacts) {
    ObjectNode claim = claims.addObject();
    claim.put("id", id);
    claim.put("category", category);
    claim.put("statement", statement);
    claim.put("status", "pass");
    claim.put("command", "./gradlew m05Check --no-daemon");
    claim.set("observations", observations.deepCopy());
    ArrayNode artifactNodes = claim.putArray("artifacts");
    for (String path : artifacts) {
      Path file = staging.resolve(path);
      require(
          Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS), "missing claim artifact " + path);
      ObjectNode artifact = artifactNodes.addObject();
      artifact.put("path", path);
      artifact.put("sha256", Hashing.sha256Hex(readBytes(file)));
    }
  }

  private static void verifyCourseContract(Path root) {
    Properties properties = new Properties();
    try (var input = Files.newInputStream(root.resolve("course.properties"))) {
      properties.load(input);
    } catch (IOException failure) {
      throw new IllegalStateException("cannot read course.properties", failure);
    }
    require("M05".equals(properties.getProperty("unit")), "course unit is not M05");
    require("0.7".equals(properties.getProperty("planVersion")), "course plan is not 0.7");
    require("COMPLETE".equals(properties.getProperty("lifecycle")), "course is not complete");
    require(
        "PASS".equals(properties.getProperty("m05Check.expectedStatus")),
        "course does not require M05 PASS");
    require(UNIT_TAG.equals(properties.getProperty("completeRef")), "course completeRef changed");
  }

  private static void verifyAnnotatedTag(Path root, String sourceCommit) {
    require(
        "tag".equals(git(root, "cat-file", "-t", UNIT_TAG).strip()),
        "M05 complete ref is not an annotated tag");
    require(
        sourceCommit.equals(git(root, "rev-parse", UNIT_TAG + "^{}").strip()),
        "M05 complete tag does not peel to HEAD");
  }

  private static void requireClean(Path root) {
    require(
        git(root, "status", "--porcelain=v1", "--untracked-files=all").isBlank(),
        "repository must be clean before M05 evidence generation");
  }

  private static void copy(Path source, Path target) throws IOException {
    require(Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS), "missing artifact " + source);
    Files.createDirectories(target.getParent());
    Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
  }

  private static void publish(Path staging, Path destination) throws IOException {
    deleteTree(destination);
    Files.createDirectories(destination.getParent());
    try {
      Files.move(staging, destination, StandardCopyOption.ATOMIC_MOVE);
    } catch (AtomicMoveNotSupportedException failure) {
      Files.move(staging, destination);
    }
  }

  private static String git(Path root, String... arguments) {
    List<String> command = new ArrayList<>();
    command.add("git");
    command.addAll(List.of(arguments));
    try {
      Process process = new ProcessBuilder(command).directory(root.toFile()).start();
      String standard = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
      String error = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
      int exit = process.waitFor();
      require(exit == 0, "git command failed: " + error.strip());
      return standard;
    } catch (IOException failure) {
      throw new IllegalStateException("cannot execute git", failure);
    } catch (InterruptedException failure) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("git command interrupted", failure);
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
    try {
      return Files.readString(path);
    } catch (IOException failure) {
      throw new IllegalStateException("cannot read " + path, failure);
    }
  }

  private static void deleteTree(Path path) {
    if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
      return;
    }
    try (var paths = Files.walk(path)) {
      for (Path current : paths.sorted(Comparator.reverseOrder()).toList()) {
        Files.deleteIfExists(current);
      }
    } catch (IOException failure) {
      throw new IllegalStateException("cannot clear " + path, failure);
    }
  }

  private static void require(boolean condition, String message) {
    if (!condition) {
      throw new IllegalStateException(message);
    }
  }

  interface CheckExecutor {
    M05CheckRunner.Result run(Path root, Path reports);
  }

  public record Result(Path manifestPath, String sourceCommit, String manifestSha256) {}
}
