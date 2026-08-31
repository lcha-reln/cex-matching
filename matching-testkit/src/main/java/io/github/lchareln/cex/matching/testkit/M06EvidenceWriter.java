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

/** Generates clean-tree, annotated-tag-bound M06 lab evidence without a product claim. */
public final class M06EvidenceWriter {
  static final String UNIT_TAG = "course/m06-complete";
  static final List<String> LIMITATIONS =
      List.of(
          "Only one in-memory BTC-USDT limit-order book is implemented; this unit adds OPEN, CANCEL_ONLY, HALTED, and operator Mass Cancel.",
          "OperatorId is retained audit attribution for an already authorized caller; the matching core does not authenticate or authorize operators.",
          "Mass Cancel is whole-book and HALTED-only; there are no account, user, symbol-set, or risk-filtered cancel scopes.",
          "Only GTC, IOC, FOK, and Post-only limit orders are implemented; STP and later order types remain deferred.",
          "The fixed 15-scenario corpus and 160 histories by 64 commands are bounded tests, not exhaustive exploration or formal verification.",
          "Accepted terminal identities remain retained without pruning, compaction, snapshot persistence, or recovery.",
          "There is no account, asset, position, fee, margin, settlement, reservation, liquidation, or risk logic.",
          "There is no networking, database, thread, Aeron, Raft, replication, failover, or high availability in this unit.",
          "The evidence makes no throughput, latency, durability, recovery, security, or production-readiness claim.");
  private static final Pattern FULL_COMMIT = Pattern.compile("^[a-f0-9]{40}(?:[a-f0-9]{24})?$");
  private final CheckExecutor checkExecutor;

  public M06EvidenceWriter() {
    this((root, reports) -> new M06CheckRunner().run(root, reports));
  }

  M06EvidenceWriter(CheckExecutor checkExecutor) {
    this.checkExecutor = java.util.Objects.requireNonNull(checkExecutor, "checkExecutor");
  }

  public Result write(
      Path repositoryRoot, Path checkDirectory, Path evidenceDirectory, String unitTag) {
    Path root = repositoryRoot.toAbsolutePath().normalize();
    Path reports = checkDirectory.toAbsolutePath().normalize();
    Path destination = evidenceDirectory.toAbsolutePath().normalize();
    require(UNIT_TAG.equals(unitTag), "invalid M06 complete tag: " + unitTag);
    require(
        reports.equals(root.resolve("build/reports/m06")),
        "check directory must be build/reports/m06");
    require(
        destination.equals(root.resolve("build/lab-evidence/M06")),
        "evidence directory must be build/lab-evidence/M06");
    requireClean(root);
    String sourceCommit = git(root, "rev-parse", "HEAD").strip();
    require(FULL_COMMIT.matcher(sourceCommit).matches(), "HEAD is not a full commit");
    verifyCourseContract(root);
    verifyAnnotatedTag(root, sourceCommit);
    require(
        git(root, "tag", "--points-at", "HEAD", "--list", "matching-*").isBlank(),
        "M06 must not create a product release tag");

    M06CheckRunner.Result fresh = checkExecutor.run(root, reports);
    require(M06CheckRunner.PASS.equals(fresh.status()), "fresh M06 check is not PASS");
    JsonNode check = JsonSupport.parse(readBytes(fresh.reportPath()));
    JsonSupport.validate(check, readString(root.resolve(M06CheckRunner.CHECK_SCHEMA_PATH)), false);
    require(
        "matching.m06.check.v2".equals(check.path("schemaVersion").stringValue()),
        "M06 check schema changed");
    require("M06".equals(check.path("unit").stringValue()), "M06 check unit changed");
    require(
        "0.8".equals(check.path("contractPlanVersion").stringValue()), "M06 plan version changed");
    require(
        check.path("releaseTarget").path("productRelease").isNull(),
        "M06 unexpectedly claims a product release");

    Path staging = destination.resolveSibling(".M06-staging-" + sourceCommit.substring(0, 12));
    deleteTree(staging);
    try {
      Files.createDirectories(staging.resolve("inputs"));
      Files.createDirectories(staging.resolve("reports"));
      copy(
          root.resolve(M06Corpus.FIXED_PATH),
          staging.resolve("inputs/market-mode-mass-cancel-v1.json"));
      copy(root.resolve(M06Corpus.PROFILE_PATH), staging.resolve("inputs/property-suite-v1.json"));
      copy(
          reports.resolve("counterexamples-v1.json"),
          staging.resolve("inputs/counterexamples-v1.json"));
      for (String name : M06CheckRunner.OUTPUTS) {
        if (!"counterexamples-v1.json".equals(name)) {
          copy(reports.resolve(name), staging.resolve("reports").resolve(name));
        }
      }
      ObjectNode manifest = manifest(sourceCommit, check, staging);
      verifyArtifactBindings(manifest, staging);
      JsonSupport.validate(
          manifest, readString(root.resolve("schemas/cex.lab-evidence.v1.schema.json")), true);
      AtomicFiles.write(staging.resolve("manifest.json"), JsonSupport.prettyBytes(manifest));
      requireClean(root);
      require(
          git(root, "rev-parse", "HEAD").strip().equals(sourceCommit),
          "HEAD changed during M06 evidence generation");
      publish(staging, destination);
      requireClean(root);
      Path manifestPath = destination.resolve("manifest.json");
      return new Result(manifestPath, sourceCommit, Hashing.sha256Hex(readBytes(manifestPath)));
    } catch (IOException failure) {
      deleteTree(staging);
      throw new IllegalStateException("cannot generate M06 evidence", failure);
    }
  }

  private static ObjectNode manifest(String sourceCommit, JsonNode check, Path staging) {
    ObjectNode root = JsonSupport.MAPPER.createObjectNode();
    root.put("schemaVersion", "cex.lab-evidence.v1");
    root.put("case", "high-availability-cex");
    root.put("project", "matching");
    root.put("unit", "M06");
    root.put("unitTag", UNIT_TAG);
    root.putNull("productRelease");
    root.put("planVersion", "0.8");
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
        "m00-m05-semantic-regression",
        "correctness",
        "The complete M05 finite semantic judge still passes without rebinding historical source-identity evidence.",
        check.path("inheritedM05"),
        staging,
        List.of("reports/m00-m05-regression.json"));
    addClaim(
        claims,
        "operating-mode-contract",
        "correctness",
        "OPEN, CANCEL_ONLY, and HALTED transitions, application fences, revisions, and permission precedence match the frozen M06 contract.",
        check.path("boundaries"),
        staging,
        List.of(
            "inputs/market-mode-mass-cancel-v1.json",
            "reports/fixed-scenario-pack.json",
            "reports/fixed-event-batches.json",
            "reports/boundaries.json",
            "reports/check.json"));
    addClaim(
        claims,
        "deterministic-mass-cancel",
        "correctness",
        "HALTED-only Mass Cancel terminates the frozen resting set atomically in global ascending acceptance-sequence order while retaining terminal and rule attribution.",
        check.path("coverage"),
        staging,
        List.of(
            "reports/coverage.json",
            "reports/invariants.json",
            "reports/fixed-history.canonical.utf8"));
    ObjectNode generated = JsonSupport.MAPPER.createObjectNode();
    generated.set("generator", check.path("generator"));
    generated.set("properties", check.path("properties"));
    addClaim(
        claims,
        "finite-three-oracle-suite",
        "correctness",
        "Two fresh SplitMix64 generations reproduce identical M06H1 bytes, and production, the independent reference, and event-derived ledger agree at 10,240 command boundaries.",
        generated,
        staging,
        List.of(
            "inputs/property-suite-v1.json",
            "reports/generated-history.canonical.utf8",
            "reports/generated-properties.json"));
    ObjectNode mutation = JsonSupport.MAPPER.createObjectNode();
    mutation.set("counterexamples", check.path("counterexamples"));
    mutation.set("mutants", check.path("mutants"));
    addClaim(
        claims,
        "minimal-semantic-counterexamples",
        "correctness",
        "All ten required faults are killed as STUDENT_FAILURE by persisted, strict-replayed, one-minimal M06X1 counterexamples; SYSTEM_ERROR is excluded from kills.",
        mutation,
        staging,
        List.of(
            "inputs/counterexamples-v1.json",
            "reports/counterexamples.json",
            "reports/counterexamples.canonical.utf8",
            "reports/replay.json",
            "reports/mutants.json"));
    addClaim(
        claims,
        "infrastructure-free-boundary",
        "architecture",
        "The production core remains free of test/reference and infrastructure dependencies, while the independent reference remains free of production-core dependencies.",
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
    claim.put("command", "./gradlew m06Check --no-daemon");
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

  private static void verifyArtifactBindings(ObjectNode manifest, Path staging) throws IOException {
    List<String> bound = new ArrayList<>();
    manifest
        .path("claims")
        .forEach(
            claim ->
                claim
                    .path("artifacts")
                    .forEach(artifact -> bound.add(artifact.path("path").stringValue())));
    List<String> files;
    try (var paths = Files.walk(staging)) {
      files =
          paths
              .filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
              .map(staging::relativize)
              .map(Path::toString)
              .map(value -> value.replace(java.io.File.separatorChar, '/'))
              .sorted()
              .toList();
    }
    List<String> sortedBindings = bound.stream().sorted().toList();
    require(
        sortedBindings.equals(files),
        "every M06 evidence artifact must be bound exactly once: files="
            + files
            + ", bindings="
            + sortedBindings);
  }

  private static void verifyCourseContract(Path root) {
    Properties properties = new Properties();
    try (var input = Files.newInputStream(root.resolve("course.properties"))) {
      properties.load(input);
    } catch (IOException failure) {
      throw new IllegalStateException("cannot read course.properties", failure);
    }
    require("M06".equals(properties.getProperty("unit")), "course unit is not M06");
    require("0.8".equals(properties.getProperty("planVersion")), "course plan is not 0.8");
    require("COMPLETE".equals(properties.getProperty("lifecycle")), "course is not complete");
    require(
        "IMPLEMENTED".equals(properties.getProperty("designDepth")),
        "M06 design is not implemented");
    require(
        "PASS".equals(properties.getProperty("m06Check.expectedStatus")),
        "course does not require M06 PASS");
    require(UNIT_TAG.equals(properties.getProperty("completeRef")), "course completeRef changed");
  }

  private static void verifyAnnotatedTag(Path root, String sourceCommit) {
    require(
        "tag".equals(git(root, "cat-file", "-t", UNIT_TAG).strip()),
        "M06 complete ref is not an annotated tag");
    require(
        sourceCommit.equals(git(root, "rev-parse", UNIT_TAG + "^{}").strip()),
        "M06 complete tag does not peel to HEAD");
  }

  private static void requireClean(Path root) {
    require(
        git(root, "status", "--porcelain=v1", "--untracked-files=all").isBlank(),
        "repository must be clean before M06 evidence generation");
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
    M06CheckRunner.Result run(Path root, Path reports);
  }

  public record Result(Path manifestPath, String sourceCommit, String manifestSha256) {}
}
