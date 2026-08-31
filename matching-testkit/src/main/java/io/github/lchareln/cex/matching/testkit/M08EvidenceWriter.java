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

/** Generates clean-tree, annotated-tag-bound M08 evidence with explicit durability limits. */
public final class M08EvidenceWriter {
  static final String UNIT_TAG = "course/m08-complete";
  static final List<String> LIMITATIONS =
      List.of(
          "This unit is one caller-serialized process and one shard; it is not replicated and is not a high-availability deployment.",
          "The M08C1 envelope and M08W1 WAL are internal local-journal formats, not a frozen Rest, WebSocket, Aeron, or public exchange protocol.",
          "FileChannel.force(true) is evidence only for the documented JDK and operating-system barrier; no physical-media or real power-loss guarantee is claimed.",
          "The deployment must pre-provision an existing real non-symlink WAL directory and durably publish its ancestor directory entry before runtime open; the runtime refuses missing or symlink paths and does not prove that external provisioning step.",
          "ENOSPC and read-only observations are named deterministic FileSystemException injections with actualFilesystem=false; write, force, rename, directory-force, lock, and apply failures are likewise code-level injections unless an artifact explicitly says otherwise.",
          "No real disk exhaustion, read-only mount, device-cache failure, power cut, or filesystem crash-consistency matrix was exercised by this finite judge.",
          "The fixed 20 scenarios and 96 histories by 48 operations are bounded tests, not exhaustive exploration or formal verification.",
          "PRODUCER_SEQUENCE_STALE is a reserved code without a direct witness: strict continuity and no eviction mean a prior active-epoch slot resolves as exact duplicate or slot conflict first.",
          "Recovery replays from genesis; there is no snapshot, retention, compaction, bounded recovery time, online repair, or WAL truncation policy.",
          "There is no Aeron, Raft, leader election, quorum, replication, failover, multi-shard routing, group commit, database double-write, or external side-effect idempotency.",
          "The evidence makes no throughput, latency, capacity, operational security, upgrade, or production-readiness claim.");
  private static final Pattern FULL_COMMIT = Pattern.compile("^[a-f0-9]{40}(?:[a-f0-9]{24})?$");

  public Result write(
      Path repositoryRoot, Path checkDirectory, Path evidenceDirectory, String unitTag) {
    Path root = repositoryRoot.toAbsolutePath().normalize();
    Path reports = checkDirectory.toAbsolutePath().normalize();
    Path destination = evidenceDirectory.toAbsolutePath().normalize();
    require(UNIT_TAG.equals(unitTag), "invalid M08 complete tag: " + unitTag);
    require(
        reports.equals(root.resolve("build/reports/m08")),
        "check directory must be build/reports/m08");
    require(
        destination.equals(root.resolve("build/lab-evidence/M08")),
        "evidence directory must be build/lab-evidence/M08");
    requireClean(root);
    String sourceCommit = git(root, "rev-parse", "HEAD").strip();
    require(FULL_COMMIT.matcher(sourceCommit).matches(), "HEAD is not a full commit");
    verifyCourse(root);
    verifyAnnotatedTag(root, sourceCommit);
    require(
        git(root, "tag", "--points-at", "HEAD", "--list", "matching-*").isBlank(),
        "M08 must not create a product release tag");

    M08CheckRunner.Result fresh = new M08CheckRunner().run(root, reports);
    require(M08CheckRunner.PASS.equals(fresh.status()), "fresh M08 check is not PASS");
    JsonNode check = JsonSupport.parse(readBytes(fresh.reportPath()));
    JsonSupport.validate(check, readString(root.resolve(M08CheckRunner.CHECK_SCHEMA_PATH)), false);
    require(
        "matching.m08.check.v2".equals(check.path("schemaVersion").stringValue()),
        "M08 check schema changed");
    require("0.10".equals(check.path("contractPlanVersion").stringValue()), "M08 plan changed");
    require(
        check.path("releaseTarget").path("productRelease").isNull(),
        "M08 claims a product release");

    Path staging = destination.resolveSibling(".M08-staging-" + sourceCommit.substring(0, 12));
    deleteTree(staging);
    try {
      Files.createDirectories(staging.resolve("inputs"));
      Files.createDirectories(staging.resolve("reports"));
      copy(
          root.resolve(M08StartCheckRunner.FIXED_CORPUS_PATH),
          staging.resolve("inputs/local-wal-durability-v1.json"));
      copy(
          root.resolve(M08StartCheckRunner.GENERATOR_PATH),
          staging.resolve("inputs/property-suite-v1.json"));
      for (String name : M08CheckRunner.OUTPUTS) {
        copy(reports.resolve(name), staging.resolve("reports").resolve(name));
      }
      ObjectNode manifest = manifest(sourceCommit, check, staging);
      verifyArtifactBindings(manifest, staging);
      JsonSupport.validate(
          manifest, readString(root.resolve("schemas/cex.lab-evidence.v1.schema.json")), true);
      AtomicFiles.write(staging.resolve("manifest.json"), JsonSupport.prettyBytes(manifest));
      requireClean(root);
      require(
          sourceCommit.equals(git(root, "rev-parse", "HEAD").strip()),
          "HEAD changed during M08 evidence generation");
      publish(staging, destination);
      requireClean(root);
      Path manifestPath = destination.resolve("manifest.json");
      return new Result(manifestPath, sourceCommit, Hashing.sha256Hex(readBytes(manifestPath)));
    } catch (IOException failure) {
      deleteTree(staging);
      throw new IllegalStateException("cannot generate M08 evidence", failure);
    }
  }

  private static ObjectNode manifest(String sourceCommit, JsonNode check, Path staging) {
    ObjectNode manifest = JsonSupport.MAPPER.createObjectNode();
    manifest.put("schemaVersion", "cex.lab-evidence.v1");
    manifest.put("case", "high-availability-cex");
    manifest.put("project", "matching");
    manifest.put("unit", "M08");
    manifest.put("unitTag", UNIT_TAG);
    manifest.putNull("productRelease");
    manifest.put("planVersion", "0.10");
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
        "m00-m07-semantic-regression",
        "correctness",
        "The inherited finite M07 semantic judge remains green without rebinding historical source evidence.",
        check.path("inheritedM07"),
        staging,
        List.of("reports/inherited-m07.json"));
    addClaim(
        claims,
        "canonical-journal-and-recovery",
        "correctness",
        "The frozen M08C1/M08W1 scenarios journal all deterministic application commands, durable business rejections, identity bindings, and genesis recovery outcomes.",
        check.path("durability"),
        staging,
        List.of(
            "inputs/local-wal-durability-v1.json",
            "reports/fixed-scenarios.json",
            "reports/fixed-history.canonical.utf8"));
    addClaim(
        claims,
        "finite-independent-durability-model",
        "correctness",
        "Two fresh SplitMix64 generations reproduce exact bytes across 96 histories by 48 operations while the no-I/O identity model and third ledger check every boundary.",
        check.path("generator"),
        staging,
        List.of(
            "inputs/property-suite-v1.json",
            "reports/generated-properties.json",
            "reports/generated-history.canonical.utf8",
            "reports/durability-ledger.json"));
    addClaim(
        claims,
        "code-level-fault-boundaries",
        "correctness",
        "Twenty-four obligations plus seven BEFORE_OPERATION histories cover append/force/apply/ACK, rollover, lock, torn-tail, corruption, and fail-closed behavior using explicitly labeled code-level injection; typed ENOSPC/read-only witnesses are not real filesystem tests.",
        check.path("faultEvidence"),
        staging,
        List.of(
            "reports/coverage.json",
            "reports/fault-windows.json",
            "reports/operation-failures.json"));
    addClaim(
        claims,
        "minimal-semantic-mutants",
        "correctness",
        "All ten required executable runtime/file faults are STUDENT_FAILURE with fresh, strict-replayed, one-minimal M08X2 histories that preserve submit/close/restart/retry grammar; SYSTEM_ERROR is excluded from kills.",
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
        "The matching core remains infrastructure-free and matching-local-runtime depends only on matching-core and the JDK; no Aeron or HA claim is made.",
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
    claim.put("command", "./gradlew m08Check --no-daemon");
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
        "every M08 evidence artifact must be bound exactly once: files="
            + files
            + ", bindings="
            + bound.stream().sorted().toList());
  }

  private static void verifyCourse(Path root) {
    Properties properties = new Properties();
    try (var input = Files.newInputStream(root.resolve("course.properties"))) {
      properties.load(input);
    } catch (IOException failure) {
      throw new IllegalStateException("cannot read course.properties", failure);
    }
    require("M08".equals(properties.getProperty("unit")), "course unit is not M08");
    require("0.10".equals(properties.getProperty("planVersion")), "course plan is not 0.10");
    require("COMPLETE".equals(properties.getProperty("lifecycle")), "course is not complete");
    require("IMPLEMENTED".equals(properties.getProperty("designDepth")), "M08 is not implemented");
    require(
        "PASS".equals(properties.getProperty("m08Check.expectedStatus")),
        "course does not require M08 PASS");
    require(UNIT_TAG.equals(properties.getProperty("completeRef")), "M08 completeRef changed");
  }

  private static void verifyAnnotatedTag(Path root, String sourceCommit) {
    require(
        "tag".equals(git(root, "cat-file", "-t", UNIT_TAG).strip()),
        "M08 complete ref is not annotated");
    require(
        sourceCommit.equals(git(root, "rev-parse", UNIT_TAG + "^{}").strip()),
        "M08 complete tag does not peel to HEAD");
  }

  private static void requireClean(Path root) {
    require(
        git(root, "status", "--porcelain=v1", "--untracked-files=all").isBlank(),
        "repository must be clean before M08 evidence generation");
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

  public record Result(Path manifestPath, String sourceCommit, String manifestSha256) {}
}
