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

/** Generates clean-tree, annotated-tag-bound M09 evidence without a product release. */
public final class M09EvidenceWriter {
  static final String UNIT_TAG = "course/m09-complete";
  private static final String START_TAG = "course/m09-start";
  private static final String INHERITED_COMPLETE_TAG = "course/m08-complete";
  static final List<String> REQUIRED_CLAIMS =
      List.of(
          "m00-m08-semantic-regression",
          "snapshot-state-and-suffix-recovery",
          "finite-generated-storage-oracle",
          "publication-retirement-fault-boundaries",
          "executable-storage-candidates",
          "local-runtime-boundary");
  static final List<String> LIMITATIONS =
      List.of(
          "This unit is one caller-serialized process and one shard; it is not replicated and is not a high-availability deployment.",
          "M09S1 and M08W1 are internal local-storage formats, not a Rest, WebSocket, Aeron, or public exchange protocol; M09 supports one format only and unknown versions fail closed.",
          "The snapshot candidate and retained-genesis-WAL oracle both use the production WAL parser and inherited matching core; only the no-I/O storage ledger is independent, so there is no third complete M00-M08 business model.",
          "The production recovery budget is 64 suffix records and 1 MiB; the fixed multi-segment mechanism fixture uses a test-only 4 MiB byte budget to create a crossing suffix shape that the default minimum segment size cannot create.",
          "Retaining the latest two published snapshots is an implementation policy, not an N-1 format-evolution or rolling-upgrade contract.",
          "Retirement evidence covers runtime-created non-terminal missing-prefix gaps and retention of active or crossing segments; detection of an externally deleted final active segment is explicitly not claimed.",
          "Runtime.halt(86) children prove process termination at seven forced markers without normal close; they do not prove power-loss or physical-media durability.",
          "The eight operation failures are deterministic code-level injections, not real disk-full, read-only-mount, controller, device-cache, or filesystem crash-consistency tests.",
          "FileChannel.force(true) is evidence only for the documented JDK and operating-system barrier; no physical-media guarantee is claimed.",
          "The fixed 22 scenarios and 96 histories by 40 operations are finite tests, not exhaustive exploration or formal verification.",
          "The twelve executable candidates are nine storage/state mutants plus three invalid-latest acceptance candidates; killing them does not establish the absence of unknown snapshot, WAL, retention, or recovery defects, and INVALID_HISTORY never counts as a kill.",
          "There is no Aeron, Raft, leader election, quorum, replication, failover, multi-shard routing, database restore, or external side-effect idempotency.",
          "The evidence makes no throughput, latency, capacity, security, online-upgrade, operational, or production-readiness claim.");
  static final List<String> REPORT_ARTIFACTS = M09CheckRunner.OUTPUTS;
  static final Set<String> EXPECTED_ARTIFACT_PATHS = expectedArtifactPaths();
  private static final Pattern FULL_COMMIT = Pattern.compile("^[a-f0-9]{40}(?:[a-f0-9]{24})?$");
  private final CheckExecutor checkExecutor;
  private final BoundaryHook boundaryHook;

  public M09EvidenceWriter() {
    this((root, reports) -> new M09CheckRunner().run(root, reports), BoundaryHook.NOOP);
  }

  M09EvidenceWriter(CheckExecutor checkExecutor) {
    this(checkExecutor, BoundaryHook.NOOP);
  }

  M09EvidenceWriter(CheckExecutor checkExecutor, BoundaryHook boundaryHook) {
    this.checkExecutor = java.util.Objects.requireNonNull(checkExecutor, "checkExecutor");
    this.boundaryHook = java.util.Objects.requireNonNull(boundaryHook, "boundaryHook");
  }

  public Result write(
      Path repositoryRoot, Path checkDirectory, Path evidenceDirectory, String unitTag) {
    Path root = repositoryRoot.toAbsolutePath().normalize();
    Path reports = checkDirectory.toAbsolutePath().normalize();
    Path destination = evidenceDirectory.toAbsolutePath().normalize();
    require(UNIT_TAG.equals(unitTag), "invalid M09 complete tag: " + unitTag);
    require(reports.equals(root.resolve("build/reports/m09")), "invalid M09 report directory");
    require(
        destination.equals(root.resolve("build/lab-evidence/M09")),
        "invalid M09 evidence directory");
    SafeOutputPaths.requireNoSymlinkComponents(root, reports);
    SafeOutputPaths.requireNoSymlinkComponents(root, destination);
    requireClean(root);
    String sourceCommit = git(root, "rev-parse", "HEAD").strip();
    require(FULL_COMMIT.matcher(sourceCommit).matches(), "HEAD is not a full Git commit");
    verifyCourse(root);
    verifyReleaseState(root, sourceCommit);

    M09CheckRunner.Result fresh = checkExecutor.run(root, reports);
    require(M09CheckRunner.PASS.equals(fresh.status()), "fresh M09 check is not PASS");
    require(
        fresh.reportPath().toAbsolutePath().normalize().equals(reports.resolve("check.json")),
        "fresh M09 check returned an unexpected report path");
    JsonNode check = JsonSupport.parse(readBytes(fresh.reportPath()));
    JsonSupport.validate(check, readString(root.resolve(M09CheckRunner.CHECK_SCHEMA_PATH)), false);
    require(
        "matching.m09.check.v2".equals(check.path("schemaVersion").stringValue()),
        "M09 check schema changed");
    require("0.11".equals(check.path("contractPlanVersion").stringValue()), "M09 plan changed");
    require(
        check.path("releaseTarget").path("productRelease").isNull(),
        "M09 claims a product release");
    require(
        !check
            .path("snapshotRecovery")
            .path("externalTerminalSegmentDeletionDetection")
            .booleanValue(),
        "M09 check overclaims external final-segment deletion detection");

    Path staging = destination.resolveSibling(".M09-staging-" + sourceCommit.substring(0, 12));
    SafeOutputPaths.requireNoSymlinkComponents(root, staging);
    deleteTree(staging);
    try {
      prepare(root, reports, staging);
      ObjectNode manifest = manifest(sourceCommit, check, staging);
      verifyArtifactBindings(manifest, staging);
      JsonSupport.validate(
          manifest, readString(root.resolve("schemas/cex.lab-evidence.v1.schema.json")), true);
      AtomicFiles.write(staging.resolve("manifest.json"), JsonSupport.prettyBytes(manifest));
      boundaryHook.beforeFinalVerification(root);
      verifyReleaseState(root, sourceCommit);
      publish(root, staging, destination);
      try {
        boundaryHook.beforePostPublishVerification(root);
        verifyReleaseState(root, sourceCommit);
      } catch (RuntimeException failure) {
        deleteTree(destination);
        throw failure;
      }
      Path manifestPath = destination.resolve("manifest.json");
      return new Result(manifestPath, sourceCommit, Hashing.sha256Hex(readBytes(manifestPath)));
    } catch (IOException failure) {
      deleteTree(staging);
      throw new IllegalStateException("cannot generate M09 evidence", failure);
    } catch (RuntimeException failure) {
      deleteTree(staging);
      throw failure;
    }
  }

  private static void prepare(Path root, Path reports, Path staging) throws IOException {
    Files.createDirectories(staging.resolve("inputs"));
    Files.createDirectories(staging.resolve("reports"));
    copy(
        root.resolve(M09StartCheckRunner.FIXED_CORPUS_PATH),
        staging.resolve("inputs/snapshot-recovery-v1.json"));
    copy(
        root.resolve(M09StartCheckRunner.GENERATOR_PATH),
        staging.resolve("inputs/property-suite-v1.json"));
    for (String name : REPORT_ARTIFACTS) {
      copy(reports.resolve(name), staging.resolve("reports").resolve(name));
    }
  }

  private static ObjectNode manifest(String sourceCommit, JsonNode check, Path staging) {
    ObjectNode manifest = JsonSupport.MAPPER.createObjectNode();
    manifest.put("schemaVersion", "cex.lab-evidence.v1");
    manifest.put("case", "high-availability-cex");
    manifest.put("project", "matching");
    manifest.put("unit", "M09");
    manifest.put("unitTag", UNIT_TAG);
    manifest.putNull("productRelease");
    manifest.put("planVersion", "0.11");
    ObjectNode source = manifest.putObject("source");
    source.put("commit", sourceCommit);
    source.put("dirty", false);
    ObjectNode environment = manifest.putObject("environment");
    environment.put("java", System.getProperty("java.runtime.version"));
    environment.put("os", System.getProperty("os.name"));
    environment.put("arch", System.getProperty("os.arch"));
    ArrayNode claims = manifest.putArray("claims");
    addClaim(
        claims,
        "m00-m08-semantic-regression",
        "correctness",
        "The inherited finite M08 completion judge remains PASS; M09 adds storage-state evidence without rebinding the historical source gate.",
        check.path("inheritedM08"),
        staging,
        List.of("reports/inherited-m08.json"));
    addClaim(
        claims,
        "snapshot-state-and-suffix-recovery",
        "correctness",
        "All 22 fixed scenarios witness the frozen complete-state, published-snapshot, contiguous-suffix, bounded-recovery, and runtime-retirement contract within the stated non-claims.",
        check.path("snapshotRecovery"),
        staging,
        List.of(
            "inputs/snapshot-recovery-v1.json",
            "reports/fixed-scenarios.json",
            "reports/fixed-history.canonical.utf8"));
    addClaim(
        claims,
        "finite-generated-storage-oracle",
        "correctness",
        "Two fresh SplitMix64 generations reproduce exact bytes for 96 by 40 declared operations, separate from a 65-operation budget prelude; a retained-genesis-WAL runtime supplies the semantic comparison while a no-I/O ledger independently checks fresh-append budget predictions, cuts, and exact whole-segment inventory.",
        check.path("generator"),
        staging,
        List.of(
            "inputs/property-suite-v1.json",
            "reports/generated-properties.json",
            "reports/generated-history.canonical.utf8",
            "reports/recovery-ledger.json",
            "reports/storage-inventory.json"));
    addClaim(
        claims,
        "publication-retirement-fault-boundaries",
        "correctness",
        "Thirty-two executable witnesses include a real-JDK StorageOperations trace for publication and retirement order. Eight failures are injected at declared pre-operation hooks, while seven child Runtime.halt(86) windows bind the declared hook to namespace and fresh-reopen observations; neither hook suite claims independent observation of the underlying operation order or real power loss.",
        check.path("faultEvidence"),
        staging,
        List.of(
            "reports/coverage.json",
            "reports/crash-windows.json",
            "reports/operation-failures.json"));
    addClaim(
        claims,
        "executable-storage-candidates",
        "correctness",
        "Nine storage/state mutants and three invalid-latest acceptance candidates produce STUDENT_FAILURE under fresh strict replay. One-minimal means no single deletion reproduces the same fingerprint; PASS, INVALID_HISTORY, and a different failure are allowed, while INVALID_HISTORY and the throwing SYSTEM_ERROR control are excluded from kills.",
        check.path("mutants"),
        staging,
        List.of(
            "reports/counterexamples-v1.json",
            "reports/counterexamples.json",
            "reports/counterexamples.canonical.utf8",
            "reports/replay.json",
            "reports/mutants.json"));
    addClaim(
        claims,
        "local-runtime-boundary",
        "architecture",
        "The matching core remains infrastructure-free, the local runtime remains JDK-and-core only, and the testkit-only bridge is compiled only in matching-testkit and is absent from matching-local-runtime production sources and dependencies.",
        check.path("architecture"),
        staging,
        List.of("reports/architecture.json", "reports/check.json"));
    ArrayNode limitations = manifest.putArray("limitations");
    LIMITATIONS.forEach(limitations::add);
    manifest.putNull("supersedes");
    manifest.put("generatedAt", Instant.now().toString());
    return manifest;
  }

  private static void addClaim(
      ArrayNode claims,
      String id,
      String category,
      String statement,
      JsonNode observations,
      Path staging,
      List<String> artifactPaths) {
    ObjectNode claim = claims.addObject();
    claim.put("id", id);
    claim.put("category", category);
    claim.put("statement", statement);
    claim.put("status", "pass");
    claim.put("command", "./gradlew m09Check --no-daemon");
    claim.set("observations", observations.deepCopy());
    ArrayNode artifacts = claim.putArray("artifacts");
    for (String value : artifactPaths) {
      Path file = staging.resolve(value);
      require(
          Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS), "missing claim artifact " + value);
      ObjectNode artifact = artifacts.addObject();
      artifact.put("path", value);
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
    require(
        bound.stream().sorted().toList().equals(files),
        "every M09 evidence artifact must be bound exactly once");
    require(
        new LinkedHashSet<>(bound).size() == bound.size(),
        "M09 evidence artifact was bound more than once");
  }

  private static void verifyCourse(Path root) {
    Properties properties = new Properties();
    try (var input = Files.newInputStream(root.resolve("course.properties"))) {
      properties.load(input);
    } catch (IOException failure) {
      throw new IllegalStateException("cannot read course.properties", failure);
    }
    require("M09".equals(properties.getProperty("unit")), "course unit is not M09");
    require("0.11".equals(properties.getProperty("planVersion")), "course plan is not 0.11");
    require("COMPLETE".equals(properties.getProperty("lifecycle")), "course is not complete");
    require("IMPLEMENTED".equals(properties.getProperty("designDepth")), "M09 is not implemented");
    require(
        M09CheckRunner.PASS.equals(properties.getProperty("m09Check.expectedStatus")),
        "course does not require M09 PASS");
    require(UNIT_TAG.equals(properties.getProperty("completeRef")), "M09 completeRef changed");
  }

  private static void verifyAnnotatedTag(Path root, String sourceCommit) {
    require(
        "tag".equals(git(root, "cat-file", "-t", UNIT_TAG).strip()),
        "M09 complete ref is not annotated");
    require(
        sourceCommit.equals(git(root, "rev-parse", UNIT_TAG + "^{}").strip()),
        "M09 complete tag does not peel to HEAD");
  }

  private static void verifyReleaseState(Path root, String sourceCommit) {
    requireClean(root);
    require(
        sourceCommit.equals(git(root, "rev-parse", "HEAD").strip()),
        "HEAD changed during M09 evidence generation");
    verifyAnnotatedTag(root, sourceCommit);
    verifyAnnotatedAncestorTag(root, START_TAG, sourceCommit);
    verifyAnnotatedAncestorTag(root, INHERITED_COMPLETE_TAG, sourceCommit);
    require(
        M09StartCheckRunner.FIXED_CORPUS_SHA256.equals(
            Hashing.sha256Hex(readBytes(root.resolve(M09StartCheckRunner.FIXED_CORPUS_PATH)))),
        "M09 fixed corpus no longer matches the annotated start boundary");
    require(
        M09StartCheckRunner.GENERATOR_SHA256.equals(
            Hashing.sha256Hex(readBytes(root.resolve(M09StartCheckRunner.GENERATOR_PATH)))),
        "M09 generator no longer matches the annotated start boundary");
    require(
        git(root, "tag", "--points-at", sourceCommit, "--list", "matching-*").isBlank(),
        "M09 must not have a matching-* product release tag");
  }

  private static void verifyAnnotatedAncestorTag(Path root, String tag, String sourceCommit) {
    require("tag".equals(git(root, "cat-file", "-t", tag).strip()), tag + " is not annotated");
    String taggedCommit = git(root, "rev-parse", tag + "^{}").strip();
    require(
        isAncestor(root, taggedCommit, sourceCommit),
        tag + " is not an ancestor of the M09 source commit");
  }

  private static boolean isAncestor(Path root, String ancestor, String descendant) {
    try {
      Process process =
          new ProcessBuilder("git", "merge-base", "--is-ancestor", ancestor, descendant)
              .directory(root.toFile())
              .start();
      String error = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
      int exit = process.waitFor();
      if (exit == 0) {
        return true;
      }
      if (exit == 1) {
        return false;
      }
      throw new IllegalStateException("git ancestor check failed: " + error.strip());
    } catch (IOException failure) {
      throw new IllegalStateException("cannot execute git ancestor check", failure);
    } catch (InterruptedException failure) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("git ancestor check interrupted", failure);
    }
  }

  private static void requireClean(Path root) {
    require(
        git(root, "status", "--porcelain=v1", "--untracked-files=all").isBlank(),
        "repository must be clean before M09 evidence generation");
  }

  private static void copy(Path source, Path target) throws IOException {
    require(Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS), "missing artifact " + source);
    Files.createDirectories(target.getParent());
    Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
  }

  private static void publish(Path root, Path staging, Path destination) throws IOException {
    SafeOutputPaths.requireNoSymlinkComponents(root, staging);
    SafeOutputPaths.requireNoSymlinkComponents(root, destination);
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

  private static Set<String> expectedArtifactPaths() {
    Set<String> paths = new LinkedHashSet<>();
    paths.add("inputs/snapshot-recovery-v1.json");
    paths.add("inputs/property-suite-v1.json");
    REPORT_ARTIFACTS.forEach(name -> paths.add("reports/" + name));
    return Set.copyOf(paths);
  }

  private static void require(boolean condition, String message) {
    if (!condition) {
      throw new IllegalStateException(message);
    }
  }

  @FunctionalInterface
  interface CheckExecutor {
    M09CheckRunner.Result run(Path root, Path reports);
  }

  interface BoundaryHook {
    BoundaryHook NOOP =
        new BoundaryHook() {
          @Override
          public void beforeFinalVerification(Path root) {}

          @Override
          public void beforePostPublishVerification(Path root) {}
        };

    void beforeFinalVerification(Path root);

    void beforePostPublishVerification(Path root);
  }

  public record Result(Path manifestPath, String sourceCommit, String manifestSha256) {}
}
