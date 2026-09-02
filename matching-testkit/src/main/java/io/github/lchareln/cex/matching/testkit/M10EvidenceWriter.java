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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Publishes clean-tree M10 correctness and full release-qualification evidence. */
public final class M10EvidenceWriter {
  static final String UNIT_TAG = "course/m10-complete";
  static final String PRODUCT_RELEASE = "matching-0.5.0";
  private static final String START_TAG = "course/m10-start";
  private static final String INHERITED_COMPLETE_TAG = "course/m09-complete";
  private static final String CHECK_DIRECTORY = "build/reports/m10";
  private static final String RELEASE_DIRECTORY = "build/reports/m10-release";
  private static final String EVIDENCE_DIRECTORY = "build/lab-evidence/M10";
  private static final String CHECK_SCHEMA = "schemas/matching.m10.check.v2.schema.json";
  private static final String EVIDENCE_SCHEMA = "schemas/cex.lab-evidence.v2.schema.json";
  private static final Pattern FULL_COMMIT = Pattern.compile("^[a-f0-9]{40}(?:[a-f0-9]{24})?$");

  static final List<String> REQUIRED_CLAIMS =
      List.of(
          "m00-m09-semantic-regression",
          "bounded-admission-service",
          "deterministic-admission-qualification",
          "ci-smoke-method-boundary",
          "executable-performance-candidates",
          "release-open-loop-envelope",
          "architecture-and-release-identity");

  static final List<String> LIMITATIONS =
      List.of(
          "matching-0.5.0 is one caller-serialized process, one worker, and one shard; it is not"
              + " replicated and is not a high-availability deployment.",
          "The ordinary M10 check independently verifies a real CI_SMOKE raw bundle with"
              + " resultScope METHOD_SMOKE_ONLY. It is ineligible for release throughput or"
              + " latency evidence; the additional deterministic diagnostic is MODEL_ONLY.",
          "Only the separately supplied RELEASE_QUALIFICATION bundle can support the"
              + " machine-specific performance claim; this writer verifies and copies that bundle"
              + " but never starts or abbreviates its roughly 46-minute minimum runtime. Each"
              + " saturated higher provisional candidate adds another full 30-minute soak.",
          "The published knee and qualified operating point apply only to the recorded JVM input"
              + " arguments, maximum heap, garbage-collector identity, CPU, operator storage"
              + " labels, actual WAL FileStore path/identity/space, operating system, and power"
              + " policy; they are not a portable SLA or universal production capacity.",
          "The two frozen JMH SampleTime results are required diagnostic artifacts, remain separate"
              + " from end-to-end scheduled-arrival latency, and are never used to derive the"
              + " capacity envelope.",
          "M10 uses a dedicated finite recovery suffix budget of 1,000,000 records and 1 GiB, not"
              + " the M09 default. One proactive checkpoint is scheduled 100 ms into each phase;"
              + " a 30-minute provisional candidate whose planned post-checkpoint suffix exceeds"
              + " that bound fails"
              + " before the soak rather than silently changing the qualification method.",
          "The open-loop runner covers the local admission service, M09 WAL, checkpoint pauses,"
              + " matching apply, completion accounting, resource series, and exact reopen; it does"
              + " not include Rest, WebSocket, remote clients, TLS, load balancers, or network"
              + " latency.",
          "The fixed 20 scenarios, 64 by 256 generated admission actions, 28 obligations, and 12"
              + " executable candidates are finite evidence, not exhaustive exploration or formal"
              + " verification.",
          "Resource observations use the documented JDK and operating-system counters. They do not"
              + " prove independent observability, physical-media durability, power-loss behavior,"
              + " or equivalence on another machine.",
          "M10 excludes Aeron, Cluster, Raft, quorum, leader election, failover, Cluster Backup,"
              + " multiple shards, shard routing, database synchronization, and external"
              + " side-effect idempotency.",
          "A PASS release bundle demonstrates the frozen workload and environment-specific"
              + " qualification method; it does not by itself establish security, multi-tenant"
              + " isolation, online upgrades, disaster recovery, or full exchange production"
              + " readiness.");

  static final List<String> CHECK_ARTIFACTS = M10CheckRunner.OUTPUTS;

  private final CheckExecutor checkExecutor;
  private final ReleaseExecutor releaseExecutor;
  private final BoundaryHook boundaryHook;

  public M10EvidenceWriter() {
    this(
        (root, reports) -> new M10CheckRunner().run(root, reports),
        (root, release, sourceCommit) -> {
          M10ReleaseBundleVerifier verifier = new M10ReleaseBundleVerifier();
          M10ReleaseBundleVerifier.Result result =
              release.toAbsolutePath().normalize().equals(root.resolve(RELEASE_DIRECTORY))
                  ? verifier.verify(root, release, sourceCommit)
                  : verifier.verifyCopy(root, release, sourceCommit);
          return new ReleaseBundle(
              result.qualification(), result.relativeFiles(), result.rawRecords());
        },
        BoundaryHook.NOOP);
  }

  M10EvidenceWriter(CheckExecutor checkExecutor, ReleaseExecutor releaseExecutor) {
    this(checkExecutor, releaseExecutor, BoundaryHook.NOOP);
  }

  M10EvidenceWriter(
      CheckExecutor checkExecutor, ReleaseExecutor releaseExecutor, BoundaryHook boundaryHook) {
    this.checkExecutor = Objects.requireNonNull(checkExecutor, "checkExecutor");
    this.releaseExecutor = Objects.requireNonNull(releaseExecutor, "releaseExecutor");
    this.boundaryHook = Objects.requireNonNull(boundaryHook, "boundaryHook");
  }

  public Result write(
      Path repositoryRoot,
      Path checkDirectory,
      Path releaseDirectory,
      Path evidenceDirectory,
      String unitTag,
      String productRelease) {
    Path root = repositoryRoot.toAbsolutePath().normalize();
    Path reports = checkDirectory.toAbsolutePath().normalize();
    Path release = releaseDirectory.toAbsolutePath().normalize();
    Path destination = evidenceDirectory.toAbsolutePath().normalize();
    require(UNIT_TAG.equals(unitTag), "invalid M10 complete tag: " + unitTag);
    require(
        PRODUCT_RELEASE.equals(productRelease),
        "invalid M10 product release tag: " + productRelease);
    require(reports.equals(root.resolve(CHECK_DIRECTORY)), "invalid M10 report directory");
    require(release.equals(root.resolve(RELEASE_DIRECTORY)), "invalid M10 release directory");
    require(destination.equals(root.resolve(EVIDENCE_DIRECTORY)), "invalid M10 evidence directory");
    require(!reports.startsWith(destination), "M10 report and evidence roots overlap");
    require(!release.startsWith(destination), "M10 release and evidence roots overlap");
    SafeOutputPaths.requireNoSymlinkComponents(root, reports);
    SafeOutputPaths.requireNoSymlinkComponents(root, release);
    SafeOutputPaths.requireNoSymlinkComponents(root, destination);

    requireClean(root);
    String sourceCommit = git(root, "rev-parse", "HEAD").strip();
    require(FULL_COMMIT.matcher(sourceCommit).matches(), "HEAD is not a full Git commit");
    SchemaSnapshot schemas = SchemaSnapshot.capture(root);
    verifyCourse(root);
    verifyReleaseState(root, sourceCommit, schemas);

    M10CheckRunner.Result fresh = checkExecutor.run(root, reports);
    require(M10CheckRunner.PASS.equals(fresh.status()), "fresh M10 check is not PASS");
    require(
        fresh.reportPath().toAbsolutePath().normalize().equals(reports.resolve("check.json")),
        "fresh M10 check returned an unexpected report path");
    JsonNode check = validateCheck(root, reports.resolve("check.json"), sourceCommit);
    ReleaseBundle verifiedRelease = verifyReleaseBundle(root, release, sourceCommit);
    List<SourceArtifact> artifacts =
        sourceArtifacts(root, reports, release, verifiedRelease.relativeFiles());
    verifySourceArtifacts(artifacts);
    verifyReleaseState(root, sourceCommit, schemas);

    Path staging = createStaging(root, destination);
    try {
      prepare(staging, artifacts);
      verifyCopiedArtifacts(staging, artifacts);
      validateWorkload(root, staging.resolve("inputs/workload-v1.json"));
      validateCheck(root, staging.resolve("reports/check/check.json"), sourceCommit);
      verifyReleaseState(root, sourceCommit, schemas);
      verifySourceArtifacts(artifacts);

      ObjectNode manifest = manifest(sourceCommit, check, verifiedRelease, staging, artifacts);
      verifyManifest(
          root, manifest, staging, artifacts, verifiedRelease.qualification().path("environment"));
      AtomicFiles.write(staging.resolve("manifest.json"), JsonSupport.prettyBytes(manifest));

      boundaryHook.beforeFinalVerification(root);
      verifyReleaseState(root, sourceCommit, schemas);
      verifySourceArtifacts(artifacts);
      verifyEvidenceTree(root, staging, artifacts, sourceCommit, verifiedRelease);
      publishAtomically(root, staging, destination);

      try {
        boundaryHook.beforePostPublishVerification(root);
        verifyReleaseState(root, sourceCommit, schemas);
        verifySourceArtifacts(artifacts);
        verifyEvidenceTree(root, destination, artifacts, sourceCommit, verifiedRelease);
      } catch (RuntimeException failure) {
        deleteTree(destination);
        throw failure;
      }

      Path manifestPath = destination.resolve("manifest.json");
      return new Result(
          manifestPath,
          sourceCommit,
          Hashing.sha256Hex(readBytes(manifestPath)),
          verifiedRelease.rawRecords());
    } catch (RuntimeException failure) {
      deleteTree(staging);
      throw failure;
    }
  }

  private static List<SourceArtifact> sourceArtifacts(
      Path root, Path reports, Path release, List<Path> releaseFiles) {
    List<SourceArtifact> artifacts = new ArrayList<>();
    artifacts.add(
        SourceArtifact.capture(
            root.resolve(M10StartCheckRunner.WORKLOAD_PATH), Path.of("inputs/workload-v1.json")));
    for (String name : CHECK_ARTIFACTS) {
      artifacts.add(
          SourceArtifact.capture(reports.resolve(name), Path.of("reports/check").resolve(name)));
    }
    for (Path relative : releaseFiles) {
      artifacts.add(
          SourceArtifact.capture(
              release.resolve(relative), Path.of("reports/release").resolve(relative)));
    }
    Set<Path> destinations = new LinkedHashSet<>();
    for (SourceArtifact artifact : artifacts) {
      require(
          destinations.add(artifact.evidencePath()),
          "duplicate M10 evidence path: " + artifact.evidencePath());
    }
    return List.copyOf(artifacts);
  }

  private static void prepare(Path staging, List<SourceArtifact> artifacts) {
    try {
      for (SourceArtifact artifact : artifacts) {
        Path destination = staging.resolve(artifact.evidencePath()).normalize();
        require(destination.startsWith(staging), "artifact escapes M10 staging root");
        Files.createDirectories(destination.getParent());
        Files.copy(artifact.source(), destination, StandardCopyOption.REPLACE_EXISTING);
      }
    } catch (IOException failure) {
      throw new IllegalStateException("cannot prepare M10 evidence artifacts", failure);
    }
  }

  private static ObjectNode manifest(
      String sourceCommit,
      JsonNode check,
      ReleaseBundle release,
      Path staging,
      List<SourceArtifact> artifacts) {
    ObjectNode root = JsonSupport.MAPPER.createObjectNode();
    root.put("schemaVersion", "cex.lab-evidence.v2");
    root.put("case", "high-availability-cex");
    root.put("project", "matching");
    root.put("unit", "M10");
    root.put("unitTag", UNIT_TAG);
    root.put("productRelease", PRODUCT_RELEASE);
    root.put("planVersion", "0.13");
    ObjectNode source = root.putObject("source");
    source.put("commit", sourceCommit);
    source.put("dirty", false);
    root.set(
        "environment", projectManifestEnvironment(release.qualification().path("environment")));

    Map<String, SourceArtifact> byPath = new LinkedHashMap<>();
    artifacts.forEach(artifact -> byPath.put(portable(artifact.evidencePath()), artifact));
    ArrayNode claims = root.putArray("claims");
    addClaim(
        claims,
        "m00-m09-semantic-regression",
        "correctness",
        "The inherited M00-M09 finite semantic, durability, and snapshot-recovery judges remain"
            + " PASS at the M10 source boundary.",
        object("inheritedM09", check.path("inheritedM09")),
        staging,
        byPath,
        List.of("reports/check/inherited-m09.json"),
        CHECK_COMMAND);
    addClaim(
        claims,
        "bounded-admission-service",
        "correctness",
        "Twenty real-service scenarios and all 28 obligations cover bounded non-blocking admission,"
            + " pre-WAL overload, one-owner FIFO, exact SubmissionResult pass-through, checkpoint"
            + " retry, explicit failure, and drain accounting.",
        object(
            "fixed", check.path("fixed"),
            "admissionService", check.path("admissionService"),
            "coverage", check.path("coverage")),
        staging,
        byPath,
        List.of(
            "reports/check/fixed-scenarios.json",
            "reports/check/coverage.json",
            "reports/check/admission-service.json"),
        CHECK_COMMAND);
    addClaim(
        claims,
        "deterministic-admission-qualification",
        "correctness",
        "The content-addressed workload and two fresh SplitMix64 generations reproduce 64 by 256"
            + " admission actions byte-for-byte across the four frozen overload lanes.",
        object(
            "workloadProfile", check.path("workloadProfile"),
            "qualificationRuntime", check.path("qualificationRuntime"),
            "generator", check.path("generator")),
        staging,
        byPath,
        List.of(
            "inputs/workload-v1.json",
            "reports/check/generated-admission.json",
            "reports/check/generated-actions.canonical.utf8"),
        CHECK_COMMAND);
    addClaim(
        claims,
        "ci-smoke-method-boundary",
        "correctness",
        "CI_SMOKE reconciles the frozen open-loop arithmetic, scheduled-arrival percentiles,"
            + " resource dimensions, above-knee retention, and exact recovery, while explicitly"
            + " making no release throughput claim.",
        object(
            "methodSmoke", check.path("methodSmoke"),
            "loadRecovery", check.path("loadRecovery"),
            "releaseBoundary", check.path("releaseBoundary")),
        staging,
        byPath,
        List.of(
            "reports/check/method-smoke.json",
            "reports/check/raw-arrivals.jsonl",
            "reports/check/raw-completions.jsonl",
            "reports/check/raw-queue.jsonl",
            "reports/check/resources.jsonl",
            "reports/check/reconciliation.json",
            "reports/check/load-recovery.json",
            "reports/check/micro-boundary.json"),
        CHECK_COMMAND);
    addClaim(
        claims,
        "executable-performance-candidates",
        "mutation-testing",
        "All twelve frozen admission and qualification-method candidates reproduce persisted"
            + " one-minimal STUDENT_FAILURE witnesses; SYSTEM_ERROR controls never count as kills.",
        object("mutants", check.path("mutants")),
        staging,
        byPath,
        List.of(
            "reports/check/counterexamples-v1.json",
            "reports/check/counterexamples.json",
            "reports/check/counterexamples.canonical.utf8",
            "reports/check/replay.json",
            "reports/check/mutants.json"),
        CHECK_COMMAND);

    List<String> releasePaths =
        release.relativeFiles().stream().map(path -> "reports/release/" + portable(path)).toList();
    ObjectNode releaseObservation = JsonSupport.MAPPER.createObjectNode();
    releaseObservation.set("qualification", release.qualification().deepCopy());
    releaseObservation.put("decompressedRawRecords", release.rawRecords());
    releaseObservation.put("verifiedBundleFiles", release.relativeFiles().size());
    releaseObservation.put("jmhDiagnosticBound", true);
    releaseObservation.put("jmhMode", "sample");
    releaseObservation.put("jmhResultScope", "DIAGNOSTIC_ONLY");
    releaseObservation.put("jmhUsedForCapacityEnvelope", false);
    addClaim(
        claims,
        "release-open-loop-envelope",
        "performance",
        "A separately completed RELEASE_QUALIFICATION run binds the full open-loop calibration,"
            + " three sweeps, and deterministic provisional candidates; it retains every"
            + " preceding saturated 1,800-second attempt and promotes the first full-duration"
            + " PASS as the QOP. It also binds resource series,"
            + " decompressed raw reconciliation, exact load-then-reopen evidence, and a separate"
            + " full JMH SampleTime diagnostic for its recorded machine.",
        releaseObservation,
        staging,
        byPath,
        releasePaths,
        RELEASE_COMMAND);
    addClaim(
        claims,
        "architecture-and-release-identity",
        "architecture",
        "matching-core remains infrastructure-free. Relative to the amended M10 start, the"
            + " architecture gate admits exactly the frozen semantics-preserving hot-path audit"
            + " split and its dedicated terminal-history growth test; both annotated M10 release"
            + " refs identify this exact clean source commit.",
        object(
            "environment", check.path("environment"),
            "architecture", check.path("architecture"),
            "releaseTarget", check.path("releaseTarget"),
            "releaseIdentity",
                objectValue(
                    "unitTag", UNIT_TAG,
                    "productRelease", PRODUCT_RELEASE,
                    "sameCommit", true)),
        staging,
        byPath,
        List.of(
            "reports/check/environment.json",
            "reports/check/architecture.json",
            "reports/check/check.json"),
        EVIDENCE_COMMAND);

    ArrayNode limitations = root.putArray("limitations");
    LIMITATIONS.forEach(limitations::add);
    root.putNull("supersedes");
    root.put("generatedAt", Instant.now().toString());
    return root;
  }

  private static final String CHECK_COMMAND = "./gradlew m10Check --no-daemon";
  private static final String RELEASE_COMMAND =
      "Run the frozen RELEASE_QUALIFICATION profile into build/reports/m10-release, then run"
          + " ./gradlew m10Evidence --no-daemon";
  private static final String EVIDENCE_COMMAND = "./gradlew m10Evidence --no-daemon";

  private static void addClaim(
      ArrayNode claims,
      String id,
      String category,
      String statement,
      ObjectNode observations,
      Path staging,
      Map<String, SourceArtifact> artifacts,
      List<String> artifactPaths,
      String command) {
    ObjectNode claim = claims.addObject();
    claim.put("id", id);
    claim.put("category", category);
    claim.put("statement", statement);
    claim.put("status", "pass");
    claim.put("command", command);
    claim.set("observations", observations);
    ArrayNode bindings = claim.putArray("artifacts");
    for (String path : artifactPaths) {
      SourceArtifact expected = artifacts.get(path);
      require(expected != null, "claim names an unknown M10 artifact: " + path);
      Path file = staging.resolve(path).normalize();
      require(file.startsWith(staging), "claim artifact escapes M10 evidence root");
      require(
          Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS), "missing M10 artifact: " + path);
      require(
          expected.sha256().equals(Hashing.sha256Hex(readBytes(file))),
          "M10 artifact changed before manifest binding: " + path);
      ObjectNode binding = bindings.addObject();
      binding.put("path", path);
      binding.put("sha256", expected.sha256());
    }
  }

  private void verifyEvidenceTree(
      Path root,
      Path evidence,
      List<SourceArtifact> artifacts,
      String sourceCommit,
      ReleaseBundle expectedRelease) {
    requireSafeTree(root, evidence);
    verifyCopiedArtifacts(evidence, artifacts);
    validateWorkload(root, evidence.resolve("inputs/workload-v1.json"));
    validateCheck(root, evidence.resolve("reports/check/check.json"), sourceCommit);
    ReleaseBundle release =
        verifyReleaseBundle(root, evidence.resolve("reports/release"), sourceCommit);
    requireSameRelease(expectedRelease, release, "published release bundle changed");
    JsonNode manifest = JsonSupport.parse(readBytes(evidence.resolve("manifest.json")));
    require(manifest instanceof ObjectNode, "M10 evidence manifest is not an object");
    verifyManifest(
        root,
        (ObjectNode) manifest,
        evidence,
        artifacts,
        release.qualification().path("environment"));
    Set<String> expected = new LinkedHashSet<>();
    artifacts.forEach(artifact -> expected.add(portable(artifact.evidencePath())));
    expected.add("manifest.json");
    require(expected.equals(fileInventory(evidence)), "M10 evidence file inventory changed");
  }

  private static void verifyManifest(
      Path root,
      ObjectNode manifest,
      Path evidence,
      List<SourceArtifact> artifacts,
      JsonNode qualificationEnvironment) {
    JsonSupport.validate(manifest, readString(root.resolve(EVIDENCE_SCHEMA)), true);
    require(
        jsonRoundTrip(projectManifestEnvironment(qualificationEnvironment))
            .equals(jsonRoundTrip(manifest.path("environment"))),
        "evidence environment is not the exact qualification projection");
    require("M10".equals(manifest.path("unit").stringValue()), "evidence unit changed");
    require(UNIT_TAG.equals(manifest.path("unitTag").stringValue()), "evidence unit tag changed");
    require(
        PRODUCT_RELEASE.equals(manifest.path("productRelease").stringValue()),
        "evidence product release changed");
    require("0.13".equals(manifest.path("planVersion").stringValue()), "evidence plan changed");
    List<String> claimIds = new ArrayList<>();
    List<String> bound = new ArrayList<>();
    for (JsonNode claim : manifest.path("claims")) {
      require("pass".equals(claim.path("status").stringValue()), "non-pass M10 claim");
      claimIds.add(claim.path("id").stringValue());
      for (JsonNode binding : claim.path("artifacts")) {
        String relative = binding.path("path").stringValue();
        Path path = safeRelative(relative);
        Path file = evidence.resolve(path).normalize();
        require(file.startsWith(evidence), "manifest artifact escapes evidence root");
        require(
            Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS),
            "manifest artifact is missing: " + relative);
        require(
            binding.path("sha256").stringValue().equals(Hashing.sha256Hex(readBytes(file))),
            "manifest artifact hash mismatch: " + relative);
        bound.add(relative);
      }
    }
    require(REQUIRED_CLAIMS.equals(claimIds), "M10 evidence claim set or order changed");
    require(
        new LinkedHashSet<>(bound).size() == bound.size(),
        "M10 evidence artifact was bound more than once");
    Set<String> expected = new LinkedHashSet<>();
    artifacts.forEach(artifact -> expected.add(portable(artifact.evidencePath())));
    require(expected.equals(new LinkedHashSet<>(bound)), "every M10 artifact must bind once");
    List<String> limitations =
        manifest.path("limitations").valueStream().map(JsonNode::stringValue).toList();
    require(LIMITATIONS.equals(limitations), "M10 evidence limitations changed");
  }

  private static JsonNode validateCheck(Path root, Path path, String sourceCommit) {
    JsonNode check = JsonSupport.parse(readBytes(path));
    JsonSupport.validate(check, readString(root.resolve(CHECK_SCHEMA)), false);
    require(
        "matching.m10.check.v2".equals(check.path("schemaVersion").stringValue()),
        "M10 check schema changed");
    require(
        M10CheckRunner.PASS.equals(check.path("status").stringValue()), "M10 check is not PASS");
    require("0.13".equals(check.path("contractPlanVersion").stringValue()), "M10 plan changed");
    require(
        sourceCommit.equals(check.path("source").path("commit").stringValue()),
        "M10 check source commit changed");
    require(!check.path("source").path("dirty").booleanValue(), "M10 check reports dirty source");
    require(
        "CI_SMOKE".equals(check.path("methodSmoke").path("profileId").stringValue())
            && "METHOD_SMOKE_ONLY"
                .equals(check.path("methodSmoke").path("resultScope").stringValue())
            && !check.path("methodSmoke").path("eligibleForReleaseEvidence").booleanValue()
            && "REAL_CI_SMOKE_BUNDLE"
                .equals(check.path("methodSmoke").path("evidenceMode").stringValue())
            && "MODEL_ONLY"
                .equals(
                    check
                        .path("methodSmoke")
                        .path("deterministicDiagnosticEvidenceMode")
                        .stringValue())
            && !check
                .path("methodSmoke")
                .path("deterministicDiagnosticMethodIsomorphic")
                .booleanValue()
            && !check.path("methodSmoke").path("releaseThroughputClaim").booleanValue(),
        "ordinary M10 check crossed the release-evidence boundary");
    require(
        UNIT_TAG.equals(check.path("releaseTarget").path("unitTag").stringValue())
            && PRODUCT_RELEASE.equals(
                check.path("releaseTarget").path("productRelease").stringValue()),
        "M10 check release target changed");
    return check;
  }

  private ReleaseBundle verifyReleaseBundle(Path root, Path release, String sourceCommit) {
    requireSafeTree(root, release);
    ReleaseBundle result = releaseExecutor.verify(root, release, sourceCommit);
    require(result != null, "M10 release verifier returned null");
    require(result.qualification().isObject(), "M10 qualification is not an object");
    require(result.rawRecords() > 0, "M10 release bundle has no decompressed raw records");
    JsonNode qualification = result.qualification();
    require(
        "matching.m10.qualification.v2".equals(qualification.path("schemaVersion").stringValue()),
        "M10 qualification schema changed");
    require(
        "PASS".equals(qualification.path("status").stringValue()), "M10 qualification is not PASS");
    require(
        "RELEASE_QUALIFICATION".equals(qualification.path("profileId").stringValue())
            && "RELEASE_QUALIFICATION".equals(qualification.path("resultScope").stringValue())
            && qualification.path("eligibleForReleaseEvidence").booleanValue(),
        "M10 qualification is not release eligible");
    require(
        sourceCommit.equals(qualification.path("source").path("commit").stringValue()),
        "M10 qualification source commit changed");
    require(
        M10CheckRunner.WORKLOAD_SHA256.equals(
            qualification.path("source").path("workloadSha256").stringValue()),
        "M10 qualification workload hash changed");
    JsonNode raw = qualification.path("rawRecomputation");
    int soakAttempts = qualification.path("soak").path("attempts").size();
    require(
        "PASS".equals(raw.path("status").stringValue())
            && raw.path("fromDecompressedRaw").booleanValue()
            && raw.path("percentilesRecomputed").booleanValue()
            && raw.path("accountingReconciled").booleanValue()
            && raw.path("capacityEnvelopeRecomputed").booleanValue()
            && raw.path("rawRecords").longValue() == result.rawRecords()
            && soakAttempts > 0
            && raw.path("rawPoints").intValue() == 48 + soakAttempts,
        "M10 qualification lacks successful decompressed-raw recomputation");

    List<Path> relativeFiles = normalizeRelativeFiles(result.relativeFiles());
    Set<String> returned = new LinkedHashSet<>();
    relativeFiles.forEach(path -> returned.add(portable(path)));
    require(
        returned.equals(fileInventory(release)), "release verifier did not bind the whole bundle");
    require(returned.contains("qualification.json"), "release bundle lacks qualification.json");
    require(returned.contains("recovery.json"), "release bundle lacks recovery.json");
    require(
        returned.contains("diagnostics/core-sample-time.json"),
        "release bundle lacks the full JMH SampleTime diagnostic");
    require(
        returned.stream().anyMatch(value -> value.endsWith(".jsonl.gz")),
        "release bundle lacks gzip raw shards");
    return new ReleaseBundle(qualification.deepCopy(), relativeFiles, result.rawRecords());
  }

  private static List<Path> normalizeRelativeFiles(List<Path> values) {
    require(values != null && !values.isEmpty(), "release verifier returned no files");
    List<Path> normalized = new ArrayList<>();
    Set<String> unique = new LinkedHashSet<>();
    for (Path value : values) {
      require(value != null && !value.isAbsolute(), "release artifact path must be relative");
      Path path = value.normalize();
      require(!path.toString().isBlank() && !".".equals(path.toString()), "empty release path");
      require(!path.startsWith(".."), "release artifact escapes bundle root");
      String portable = portable(path);
      require(unique.add(portable), "duplicate release artifact: " + portable);
      normalized.add(path);
    }
    normalized.sort(Comparator.comparing(M10EvidenceWriter::portable));
    return List.copyOf(normalized);
  }

  private static void requireSameRelease(
      ReleaseBundle expected, ReleaseBundle actual, String message) {
    require(expected.qualification().equals(actual.qualification()), message + ": qualification");
    require(expected.relativeFiles().equals(actual.relativeFiles()), message + ": inventory");
    require(expected.rawRecords() == actual.rawRecords(), message + ": raw record count");
  }

  private static void validateWorkload(Path root, Path workloadPath) {
    byte[] bytes = readBytes(workloadPath);
    require(
        M10CheckRunner.WORKLOAD_SHA256.equals(Hashing.sha256Hex(bytes)),
        "M10 workload hash changed");
    JsonNode workload = JsonSupport.parse(bytes);
    JsonSupport.validate(
        workload, readString(root.resolve(M10StartCheckRunner.WORKLOAD_SCHEMA_PATH)), false);
    require(
        "matching.m10.workload.v1".equals(workload.path("schemaVersion").stringValue()),
        "M10 workload schema changed");
  }

  private static void verifyCourse(Path root) {
    Properties properties = new Properties();
    try (var input = Files.newInputStream(root.resolve("course.properties"))) {
      properties.load(input);
    } catch (IOException failure) {
      throw new IllegalStateException("cannot read course.properties", failure);
    }
    Map<String, String> expected =
        Map.ofEntries(
            Map.entry("case", "high-availability-cex"),
            Map.entry("profile", "SPOT-CEX-1.0"),
            Map.entry("planVersion", "0.13"),
            Map.entry("project", "matching"),
            Map.entry("unit", "M10"),
            Map.entry("lifecycle", "COMPLETE"),
            Map.entry("designDepth", "IMPLEMENTED"),
            Map.entry("startRef", START_TAG),
            Map.entry("completeRef", UNIT_TAG),
            Map.entry("m10Check.expectedStatus", M10CheckRunner.PASS),
            Map.entry("evidencePath", EVIDENCE_DIRECTORY + "/manifest.json"));
    require(properties.size() == expected.size(), "M10 course declaration field set changed");
    expected.forEach(
        (key, value) ->
            require(value.equals(properties.getProperty(key)), "M10 course changed: " + key));
  }

  private static void verifyReleaseState(Path root, String sourceCommit, SchemaSnapshot schemas) {
    requireClean(root);
    require(
        sourceCommit.equals(git(root, "rev-parse", "HEAD").strip()),
        "HEAD changed during M10 evidence generation");
    verifyAnnotatedExact(root, UNIT_TAG, sourceCommit);
    verifyAnnotatedExact(root, PRODUCT_RELEASE, sourceCommit);
    verifyAnnotatedAncestor(root, START_TAG, sourceCommit);
    verifyAnnotatedAncestor(root, INHERITED_COMPLETE_TAG, sourceCommit);
    verifyStartWorkload(root);
    schemas.verify(root);
    validateWorkload(root, root.resolve(M10StartCheckRunner.WORKLOAD_PATH));
  }

  private static void verifyAnnotatedExact(Path root, String tag, String sourceCommit) {
    require("tag".equals(git(root, "cat-file", "-t", tag).strip()), tag + " is not annotated");
    require(
        sourceCommit.equals(git(root, "rev-parse", tag + "^{}").strip()),
        tag + " does not peel to HEAD");
  }

  private static void verifyAnnotatedAncestor(Path root, String tag, String sourceCommit) {
    require("tag".equals(git(root, "cat-file", "-t", tag).strip()), tag + " is not annotated");
    String taggedCommit = git(root, "rev-parse", tag + "^{}").strip();
    require(isAncestor(root, taggedCommit, sourceCommit), tag + " is not an ancestor of M10");
  }

  private static void verifyStartWorkload(Path root) {
    String taggedWorkload = git(root, "show", START_TAG + ":" + M10StartCheckRunner.WORKLOAD_PATH);
    require(
        M10CheckRunner.WORKLOAD_SHA256.equals(
            Hashing.sha256Hex(taggedWorkload.getBytes(StandardCharsets.UTF_8))),
        "course/m10-start workload hash differs from the completed contract");
    String taggedSchema =
        git(root, "show", START_TAG + ":" + M10StartCheckRunner.WORKLOAD_SCHEMA_PATH);
    require(
        taggedSchema.equals(readString(root.resolve(M10StartCheckRunner.WORKLOAD_SCHEMA_PATH))),
        "course/m10-start workload schema differs from the completed contract");
  }

  private static boolean isAncestor(Path root, String ancestor, String descendant) {
    try {
      Process process =
          new ProcessBuilder("git", "merge-base", "--is-ancestor", ancestor, descendant)
              .directory(root.toFile())
              .start();
      String error = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
      int exit = process.waitFor();
      if (exit == 0) return true;
      if (exit == 1) return false;
      throw new IllegalStateException("git ancestor check failed: " + error.strip());
    } catch (IOException failure) {
      throw new IllegalStateException("cannot execute git ancestor check", failure);
    } catch (InterruptedException failure) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("git ancestor check interrupted", failure);
    }
  }

  private static void verifySourceArtifacts(List<SourceArtifact> artifacts) {
    for (SourceArtifact artifact : artifacts) {
      require(
          Files.isRegularFile(artifact.source(), LinkOption.NOFOLLOW_LINKS),
          "M10 source artifact is missing: " + artifact.source());
      require(
          artifact.bytes() == size(artifact.source()),
          "M10 source artifact size changed: " + artifact.source());
      require(
          artifact.sha256().equals(Hashing.sha256Hex(readBytes(artifact.source()))),
          "M10 source artifact hash changed: " + artifact.source());
    }
  }

  private static void verifyCopiedArtifacts(Path root, List<SourceArtifact> artifacts) {
    for (SourceArtifact artifact : artifacts) {
      Path file = root.resolve(artifact.evidencePath()).normalize();
      require(file.startsWith(root), "M10 copied artifact escapes evidence root");
      require(
          Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS),
          "M10 copied artifact is missing: " + artifact.evidencePath());
      require(
          artifact.bytes() == size(file),
          "M10 copied artifact size changed: " + artifact.evidencePath());
      require(
          artifact.sha256().equals(Hashing.sha256Hex(readBytes(file))),
          "M10 copied artifact hash changed: " + artifact.evidencePath());
    }
  }

  private static Path createStaging(Path root, Path destination) {
    Path parent = destination.getParent();
    require(parent != null, "M10 evidence directory has no parent");
    SafeOutputPaths.requireNoSymlinkComponents(root, parent);
    try {
      Files.createDirectories(parent);
      SafeOutputPaths.requireNoSymlinkComponents(root, parent);
      return Files.createTempDirectory(parent, ".M10-staging-");
    } catch (IOException failure) {
      throw new IllegalStateException("cannot create M10 staging directory", failure);
    }
  }

  private static void publishAtomically(Path root, Path staging, Path destination) {
    requireSafeTree(root, staging);
    SafeOutputPaths.requireNoSymlinkComponents(root, destination);
    deleteTree(destination);
    try {
      Files.move(staging, destination, StandardCopyOption.ATOMIC_MOVE);
    } catch (AtomicMoveNotSupportedException failure) {
      throw new IllegalStateException("atomic M10 evidence publication is unavailable", failure);
    } catch (IOException failure) {
      throw new IllegalStateException("cannot publish M10 evidence", failure);
    }
  }

  private static void requireSafeTree(Path root, Path tree) {
    SafeOutputPaths.requireNoSymlinkComponents(root, tree);
    require(
        Files.isDirectory(tree, LinkOption.NOFOLLOW_LINKS), "missing M10 artifact tree: " + tree);
    try (var paths = Files.walk(tree)) {
      paths.forEach(
          path -> require(!Files.isSymbolicLink(path), "symlink in M10 artifact tree: " + path));
    } catch (IOException failure) {
      throw new IllegalStateException("cannot inspect M10 artifact tree", failure);
    }
  }

  private static Set<String> fileInventory(Path root) {
    require(
        Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS), "missing artifact directory: " + root);
    try (var paths = Files.walk(root)) {
      return new LinkedHashSet<>(
          paths
              .filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
              .map(root::relativize)
              .map(M10EvidenceWriter::portable)
              .sorted()
              .toList());
    } catch (IOException failure) {
      throw new IllegalStateException("cannot inventory artifact directory", failure);
    }
  }

  private static Path safeRelative(String value) {
    Path path = Path.of(value);
    require(!path.isAbsolute(), "absolute artifact path is forbidden");
    Path normalized = path.normalize();
    require(!normalized.startsWith(".."), "parent traversal is forbidden in artifact path");
    require(portable(normalized).equals(value), "artifact path is not canonical: " + value);
    return normalized;
  }

  private static ObjectNode object(Object... values) {
    require(values.length % 2 == 0, "object requires key/value pairs");
    ObjectNode object = JsonSupport.MAPPER.createObjectNode();
    for (int index = 0; index < values.length; index += 2) {
      String key = (String) values[index];
      JsonNode value = (JsonNode) values[index + 1];
      require(value.isObject(), "M10 check observation is missing: " + key);
      object.set(key, value.deepCopy());
    }
    return object;
  }

  private static ObjectNode objectValue(Object... values) {
    ObjectNode object = JsonSupport.MAPPER.createObjectNode();
    for (int index = 0; index < values.length; index += 2) {
      String key = (String) values[index];
      Object value = values[index + 1];
      if (value instanceof String text) object.put(key, text);
      else if (value instanceof Boolean flag) object.put(key, flag);
      else throw new IllegalArgumentException("unsupported observation value");
    }
    return object;
  }

  private static String requiredText(JsonNode object, String field) {
    String value = object.path(field).stringValue();
    require(value != null && !value.isBlank(), "qualification environment lacks " + field);
    return value;
  }

  static ObjectNode projectManifestEnvironment(JsonNode releaseEnvironment) {
    M10ReleaseBundleVerifier.verifyEnvironment(releaseEnvironment);
    ObjectNode environment = JsonSupport.MAPPER.createObjectNode();
    environment.put("java", requiredText(releaseEnvironment, "javaVersion"));
    environment.put(
        "os",
        requiredText(releaseEnvironment, "osName")
            + " "
            + requiredText(releaseEnvironment, "osVersion"));
    environment.put("arch", requiredText(releaseEnvironment, "osArchitecture"));
    environment.put("javaRuntime", requiredText(releaseEnvironment, "javaRuntime"));
    environment.put("javaVersion", requiredText(releaseEnvironment, "javaVersion"));
    environment.put("javaVendor", requiredText(releaseEnvironment, "javaVendor"));
    environment.put("vmName", requiredText(releaseEnvironment, "vmName"));
    environment.set("jvmArguments", releaseEnvironment.path("jvmArguments").deepCopy());
    environment.put("osName", requiredText(releaseEnvironment, "osName"));
    environment.put("osVersion", requiredText(releaseEnvironment, "osVersion"));
    environment.put("osArchitecture", requiredText(releaseEnvironment, "osArchitecture"));
    environment.put(
        "availableProcessors", releaseEnvironment.path("availableProcessors").intValue());
    environment.put(
        "physicalMemoryBytes", releaseEnvironment.path("physicalMemoryBytes").longValue());
    environment.put("maximumHeapBytes", releaseEnvironment.path("maximumHeapBytes").longValue());
    environment.set(
        "garbageCollectorNames", releaseEnvironment.path("garbageCollectorNames").deepCopy());
    environment.put("cpuModel", requiredText(releaseEnvironment, "cpuModel"));
    environment.put("storageDevice", requiredText(releaseEnvironment, "storageDevice"));
    environment.put("filesystem", requiredText(releaseEnvironment, "filesystem"));
    environment.put("powerPolicy", requiredText(releaseEnvironment, "powerPolicy"));
    environment.put("walRoot", requiredText(releaseEnvironment, "walRoot"));
    environment.put("walRootUri", requiredText(releaseEnvironment, "walRootUri"));
    environment.put("walFileStoreName", requiredText(releaseEnvironment, "walFileStoreName"));
    environment.put("walFileStoreType", requiredText(releaseEnvironment, "walFileStoreType"));
    environment.put(
        "walFileStoreTotalSpaceBytes",
        releaseEnvironment.path("walFileStoreTotalSpaceBytes").longValue());
    environment.put(
        "walFileStoreUsableSpaceBytes",
        releaseEnvironment.path("walFileStoreUsableSpaceBytes").longValue());
    environment.put(
        "walFileStoreUnallocatedSpaceBytes",
        releaseEnvironment.path("walFileStoreUnallocatedSpaceBytes").longValue());
    environment.put("runStartedAt", requiredText(releaseEnvironment, "runStartedAt"));
    environment.put("runFinishedAt", requiredText(releaseEnvironment, "runFinishedAt"));
    return environment;
  }

  private static JsonNode jsonRoundTrip(JsonNode value) {
    return JsonSupport.parse(JsonSupport.prettyBytes(value));
  }

  private static String portable(Path path) {
    return path.toString().replace(java.io.File.separatorChar, '/');
  }

  private static long size(Path path) {
    try {
      return Files.size(path);
    } catch (IOException failure) {
      throw new IllegalStateException("cannot size " + path, failure);
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
      return Files.readString(path, StandardCharsets.UTF_8);
    } catch (IOException failure) {
      throw new IllegalStateException("cannot read " + path, failure);
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
      int exit = process.waitFor();
      require(exit == 0, "git command failed: " + error.strip());
      return output;
    } catch (IOException failure) {
      throw new IllegalStateException("cannot execute git", failure);
    } catch (InterruptedException failure) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("git command interrupted", failure);
    }
  }

  private static void requireClean(Path root) {
    require(
        git(root, "status", "--porcelain=v1", "--untracked-files=all").isBlank(),
        "repository must be clean before M10 evidence generation");
  }

  private static void deleteTree(Path path) {
    if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return;
    try (var paths = Files.walk(path)) {
      for (Path current : paths.sorted(Comparator.reverseOrder()).toList()) {
        Files.deleteIfExists(current);
      }
    } catch (IOException failure) {
      throw new IllegalStateException("cannot clear " + path, failure);
    }
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new IllegalStateException(message);
  }

  @FunctionalInterface
  interface CheckExecutor {
    M10CheckRunner.Result run(Path root, Path reports);
  }

  @FunctionalInterface
  interface ReleaseExecutor {
    ReleaseBundle verify(Path root, Path release, String sourceCommit);
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

  record ReleaseBundle(JsonNode qualification, List<Path> relativeFiles, long rawRecords) {
    ReleaseBundle {
      Objects.requireNonNull(qualification, "qualification");
      relativeFiles = List.copyOf(Objects.requireNonNull(relativeFiles, "relativeFiles"));
    }
  }

  private record SourceArtifact(Path source, Path evidencePath, String sha256, long bytes) {
    static SourceArtifact capture(Path source, Path evidencePath) {
      require(
          Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS),
          "missing M10 source artifact: " + source);
      return new SourceArtifact(
          source.toAbsolutePath().normalize(),
          evidencePath.normalize(),
          Hashing.sha256Hex(readBytes(source)),
          size(source));
    }
  }

  private record SchemaSnapshot(Map<Path, String> hashes) {
    static SchemaSnapshot capture(Path root) {
      Map<Path, String> hashes = new LinkedHashMap<>();
      List.of(
              Path.of(M10StartCheckRunner.WORKLOAD_SCHEMA_PATH),
              Path.of(CHECK_SCHEMA),
              Path.of(M10ReleaseBundleVerifier.SCHEMA_PATH),
              Path.of(EVIDENCE_SCHEMA))
          .forEach(path -> hashes.put(path, Hashing.sha256Hex(readBytes(root.resolve(path)))));
      return new SchemaSnapshot(Map.copyOf(hashes));
    }

    void verify(Path root) {
      hashes.forEach(
          (path, digest) ->
              require(
                  digest.equals(Hashing.sha256Hex(readBytes(root.resolve(path)))),
                  "M10 evidence schema changed: " + path));
    }
  }

  public record Result(
      Path manifestPath, String sourceCommit, String manifestSha256, long rawRecords) {}
}
