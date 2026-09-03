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
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Publishes bounded, clean-tree, release-bound M12 correctness evidence atomically. */
public final class M12EvidenceWriter {
  static final String UNIT_TAG = "course/m12-complete";
  static final String PRODUCT_RELEASE = "matching-0.8.0";
  static final String START_TAG = "course/m12-start";
  static final String INHERITED_TAG = "course/m11-complete";
  static final String START_COMMIT = "43b0bbf853a1ffefeb1d5a87d791f4eb387b1cbf";
  static final String INHERITED_COMMIT = M12StartCheckRunner.BASELINE_COMMIT;
  private static final String CHECK_DIRECTORY = "build/reports/m12";
  private static final String EVIDENCE_DIRECTORY = "build/lab-evidence/M12";
  private static final String EVIDENCE_SCHEMA = "schemas/cex.lab-evidence.v2.schema.json";
  private static final long MAX_FILE_BYTES = 2L * 1024 * 1024;
  private static final long MAX_TREE_BYTES = 10L * 1024 * 1024;
  private static final int MAX_FILES = 64;
  private static final Pattern FULL_COMMIT = Pattern.compile("^[a-f0-9]{40}(?:[a-f0-9]{24})?$");
  private static final String CHECK_COMMAND = "./gradlew m12Check --no-daemon --max-workers=1";
  private static final String EVIDENCE_COMMAND =
      "./gradlew m12Evidence --no-daemon --max-workers=1";
  private static final String CONTRACT_CORRECTION_REASON =
      "Aeron appointedLeaderId disables automatic election and prevents the required Leader"
          + " failover.";

  static final List<String> REPORT_ARTIFACTS = List.copyOf(M12CheckRunner.OUTPUTS);

  static final List<String> EVIDENCE_SCHEMAS =
      List.of(
          M12StartCheckRunner.WORKLOAD_SCHEMA_PATH,
          M12CheckRunner.CHECK_SCHEMA_PATH,
          M12CheckRunner.COVERAGE_SCHEMA_PATH,
          M12CheckRunner.MUTANTS_SCHEMA_PATH,
          M12CheckRunner.COUNTEREXAMPLES_SCHEMA_PATH,
          M12CheckRunner.REPLAY_SCHEMA_PATH,
          M12StrictReports.INHERITED_SCHEMA_PATH,
          M12StrictReports.HISTORY_SCHEMA_PATH,
          M12StrictReports.TOPOLOGY_SCHEMA_PATH,
          M12StrictReports.LEADERSHIP_SCHEMA_PATH,
          M12StrictReports.QUORUM_SCHEMA_PATH,
          M12StrictReports.CATCHUP_SCHEMA_PATH,
          M12StrictReports.STATE_SCHEMA_PATH,
          M12StrictReports.ARCHITECTURE_SCHEMA_PATH,
          M12StrictReports.ENVIRONMENT_SCHEMA_PATH,
          EVIDENCE_SCHEMA);

  static final List<String> REQUIRED_CLAIMS =
      List.of(
          "inherited-m11",
          "three-real-members",
          "invocation-boundary",
          "leader-failover",
          "same-identity-one-effect",
          "former-leader-catchup",
          "no-quorum-recovery",
          "final-state-equivalence",
          "semantic-mutants",
          "architecture-release");

  static final List<String> LIMITATIONS =
      List.of(
          "M12 runs three independent Aeron Cluster child JVMs on one host; it does not demonstrate"
              + " host, rack, availability-zone, or region isolation.",
          "M12 covers one statically configured three-member Cluster and one matching shard; it"
              + " does not demonstrate dynamic membership.",
          "The frozen 14 scenarios, 85 invocations, 25 executed obligations, and 8 semantic mutants"
              + " are finite evidence, not exhaustive exploration or formal proof.",
          "M12 makes no throughput, latency, capacity, resource-efficiency, RTO, RPO, or production"
              + " SLO claim.",
          "M12 does not exercise Cluster Backup, backup restore, disk loss, filesystem corruption,"
              + " machine loss, or power loss.",
          "Fault injection is bounded to the frozen fail-stop and no-quorum sequence; it does not"
              + " cover arbitrary, asymmetric, delayed, Byzantine, or exhaustive partition"
              + " schedules.",
          "M12 does not exercise rolling deployment, mixed-version membership, online migration,"
              + " downgrade, or rollback safety.",
          "M12 does not exercise multiple shards, shard routing, resharding, cross-shard ordering,"
              + " or rebalancing.",
          "M12 excludes the Counter, REST/OpenAPI, WebSocket, gateway, database synchronization,"
              + " and public-reference-data services.",
          "M12 proves no exactly-once guarantee for external side effects, settlement, accounts,"
              + " balances, positions, ledgers, or downstream consumers.",
          "UNKNOWN recovery is bounded to same-durable-identity retries in the frozen corpus;"
              + " resumable output delivery and later recovery protocols remain outside M12.",
          "The eight mutants execute only against the deterministic semantic history model; they"
              + " are not real Aeron Cluster fault executions and never qualify as Cluster"
              + " evidence.",
          "Localhost UDP, the operating-system scheduler, and local storage do not model cross-host"
              + " network, clock, or storage behavior.",
          "TLS, authentication, authorization, rate limiting, abuse prevention, secrets, audit"
              + " retention, and regulatory controls are outside this evidence boundary.",
          "The frozen M11 response wire carries no Leader/member or leadership-term provenance;"
              + " M12 does not inject delayed old-session egress, so this run is not a general"
              + " delayed-response fencing proof.");

  private final CheckExecutor checkExecutor;
  private final BoundaryHook boundaryHook;
  private final ReleaseExpectations releaseExpectations;

  public M12EvidenceWriter() {
    this(
        (root, reports) -> new M12CheckRunner().run(root, reports),
        BoundaryHook.NOOP,
        ReleaseExpectations.production());
  }

  M12EvidenceWriter(CheckExecutor checkExecutor) {
    this(checkExecutor, BoundaryHook.NOOP, ReleaseExpectations.production());
  }

  M12EvidenceWriter(CheckExecutor checkExecutor, BoundaryHook boundaryHook) {
    this(checkExecutor, boundaryHook, ReleaseExpectations.production());
  }

  M12EvidenceWriter(
      CheckExecutor checkExecutor,
      BoundaryHook boundaryHook,
      ReleaseExpectations releaseExpectations) {
    this.checkExecutor = java.util.Objects.requireNonNull(checkExecutor, "checkExecutor");
    this.boundaryHook = java.util.Objects.requireNonNull(boundaryHook, "boundaryHook");
    this.releaseExpectations =
        java.util.Objects.requireNonNull(releaseExpectations, "releaseExpectations");
  }

  public Result write(
      Path repositoryRoot,
      Path checkDirectory,
      Path evidenceDirectory,
      String unitTag,
      String productRelease) {
    Path root = repositoryRoot.toAbsolutePath().normalize();
    Path reports = checkDirectory.toAbsolutePath().normalize();
    Path destination = evidenceDirectory.toAbsolutePath().normalize();
    require(UNIT_TAG.equals(unitTag), "invalid M12 complete tag: " + unitTag);
    require(
        PRODUCT_RELEASE.equals(productRelease), "invalid M12 product release: " + productRelease);
    require(reports.equals(root.resolve(CHECK_DIRECTORY)), "invalid M12 report directory");
    require(destination.equals(root.resolve(EVIDENCE_DIRECTORY)), "invalid M12 evidence directory");
    require(!reports.startsWith(destination), "M12 report and evidence roots overlap");
    SafeOutputPaths.requireNoSymlinkComponents(root, reports);
    SafeOutputPaths.requireNoSymlinkComponents(root, destination);

    requireClean(root);
    String sourceCommit = git(root, "rev-parse", "HEAD").strip();
    require(FULL_COMMIT.matcher(sourceCommit).matches(), "HEAD is not a full Git commit");
    FrozenInputs frozen = FrozenInputs.capture(root);
    SchemaSnapshot schemas = SchemaSnapshot.capture(root);
    verifyCourse(root);
    verifyReleaseState(root, sourceCommit, frozen, schemas, releaseExpectations);

    M12CheckRunner.Result fresh = checkExecutor.run(root, reports);
    require(M12CheckRunner.PASS.equals(fresh.status()), "fresh M12 check is not strict PASS");
    require(
        fresh.reportPath().toAbsolutePath().normalize().equals(reports.resolve("check.json")),
        "fresh M12 check returned an unexpected report path");
    requireClean(root);
    JsonNode check = validateCheck(root, fresh.reportPath(), reports, sourceCommit);
    List<SourceArtifact> artifacts = sourceArtifacts(root, reports);
    verifySourceArtifacts(root, artifacts);
    verifyReleaseState(root, sourceCommit, frozen, schemas, releaseExpectations);

    Path staging = createStaging(root, destination);
    try {
      prepare(staging, artifacts);
      verifyCopiedArtifacts(staging, artifacts);
      validateCheck(
          staging,
          staging.resolve("reports/check/check.json"),
          staging.resolve("reports/check"),
          sourceCommit);
      ObjectNode expectedManifest =
          (ObjectNode)
              JsonSupport.parse(
                  JsonSupport.prettyBytes(manifest(sourceCommit, check, staging, artifacts)));
      ObjectNode manifest = expectedManifest.deepCopy();
      verifyManifest(staging, manifest, artifacts, sourceCommit, expectedManifest);
      AtomicFiles.write(staging.resolve("manifest.json"), JsonSupport.prettyBytes(manifest));
      verifyBudget(staging);

      boundaryHook.beforeFinalVerification(root);
      verifyReleaseState(root, sourceCommit, frozen, schemas, releaseExpectations);
      verifySourceArtifacts(root, artifacts);
      verifyEvidenceTree(
          root,
          staging,
          artifacts,
          sourceCommit,
          frozen,
          schemas,
          releaseExpectations,
          expectedManifest);
      Publication publication = publishAtomically(root, staging, destination);
      try {
        boundaryHook.beforePostPublishVerification(root);
        verifyReleaseState(root, sourceCommit, frozen, schemas, releaseExpectations);
        verifySourceArtifacts(root, artifacts);
        verifyEvidenceTree(
            root,
            destination,
            artifacts,
            sourceCommit,
            frozen,
            schemas,
            releaseExpectations,
            expectedManifest);
        Path manifestPath = destination.resolve("manifest.json");
        Result result =
            new Result(
                manifestPath,
                sourceCommit,
                Hashing.sha256Hex(readBytes(manifestPath)),
                artifacts.size());
        publication.complete();
        return result;
      } catch (RuntimeException failure) {
        publication.restore(staging, destination, failure);
        throw failure;
      }
    } catch (RuntimeException failure) {
      deleteTree(staging);
      throw failure;
    }
  }

  private static List<SourceArtifact> sourceArtifacts(Path root, Path reports) {
    List<SourceArtifact> artifacts = new ArrayList<>();
    artifacts.add(
        SourceArtifact.capture(
            root,
            root.resolve(M12StartCheckRunner.WORKLOAD_PATH),
            Path.of("inputs/workload-v1.json")));
    for (String schema : EVIDENCE_SCHEMAS) {
      Path path = Path.of(schema);
      artifacts.add(
          SourceArtifact.capture(
              root, root.resolve(path), Path.of("schemas").resolve(path.getFileName())));
    }
    for (String name : REPORT_ARTIFACTS) {
      artifacts.add(
          SourceArtifact.capture(
              root, reports.resolve(name), Path.of("reports/check").resolve(name)));
    }
    artifacts.add(
        SourceArtifact.capture(
            root, reports.resolve("check.json"), Path.of("reports/check/check.json")));
    require(artifacts.size() == 33, "M12 evidence source inventory changed");
    Set<Path> unique = new LinkedHashSet<>();
    artifacts.forEach(
        artifact -> require(unique.add(artifact.evidencePath()), "duplicate M12 evidence path"));
    return List.copyOf(artifacts);
  }

  private static ObjectNode manifest(
      String sourceCommit, JsonNode check, Path staging, List<SourceArtifact> artifacts) {
    ObjectNode manifest = JsonSupport.MAPPER.createObjectNode();
    manifest.put("schemaVersion", "cex.lab-evidence.v2");
    manifest.put("case", "high-availability-cex");
    manifest.put("project", "matching");
    manifest.put("unit", "M12");
    manifest.put("unitTag", UNIT_TAG);
    manifest.put("productRelease", PRODUCT_RELEASE);
    manifest.put("planVersion", "0.15");
    ObjectNode source = manifest.putObject("source");
    source.put("commit", sourceCommit);
    source.put("dirty", false);
    manifest.set("environment", manifestEnvironment(check.path("environment")));

    Map<String, SourceArtifact> byPath = new LinkedHashMap<>();
    artifacts.forEach(artifact -> byPath.put(portable(artifact.evidencePath()), artifact));
    ArrayNode claims = manifest.putArray("claims");
    addClaim(
        claims,
        "inherited-m11",
        "correctness",
        "The frozen M11 semantic, protocol, snapshot, and architecture regressions remain PASS at"
            + " the M12 source boundary.",
        object("inheritedM11", check.path("inheritedM11")),
        staging,
        byPath,
        List.of(
            "reports/check/inherited-m11.json",
            "schemas/matching.m12.inherited-m11.v1.schema.json"),
        CHECK_COMMAND);
    addClaim(
        claims,
        "three-real-members",
        "fault-tolerance",
        "Three independently owned child JVM members form the frozen static Aeron Cluster on one"
            + " explicitly disclosed host.",
        object(
            "clusterTopology", check.path("clusterTopology"),
            "contractCorrection", check.path("contractCorrection"),
            "environment", check.path("environment")),
        staging,
        byPath,
        List.of(
            "reports/check/topology.json",
            "reports/check/environment.json",
            "schemas/matching.m12.topology.v1.schema.json",
            "schemas/matching.m12.environment.v1.schema.json"),
        CHECK_COMMAND);
    addClaim(
        claims,
        "invocation-boundary",
        "correctness",
        "The frozen workload produces 66 canonical durable identities and 85 client invocations"
            + " whose offer, UNKNOWN, and ACK boundaries are recorded without treating ingress as"
            + " business success.",
        object(
            "workloadProfile", check.path("workloadProfile"),
            "commandOutcomes", check.path("commandOutcomes"),
            "judgeInspection", check.path("judgeInspection")),
        staging,
        byPath,
        List.of(
            "inputs/workload-v1.json",
            "schemas/matching.m12.workload.v1.schema.json",
            "reports/check/corpus.json",
            "reports/check/commands.canonical.bin",
            "reports/check/m12-command-history.json",
            "schemas/matching.m12.command-history.v1.schema.json"),
        CHECK_COMMAND);
    addClaim(
        claims,
        "leader-failover",
        "fault-tolerance",
        "In this frozen fail-stop schedule, the current Leader is externally stopped, a higher-term"
            + " replacement is elected, every observed post-failure ACK is bound to replacement"
            + " client authority, no old-authority ACK is observed, and application sequencing"
            + " continues; mismatch rejection is covered separately by unit and semantic-model"
            + " checks.",
        object("leadership", check.path("leadership")),
        staging,
        byPath,
        List.of("reports/check/leadership.json", "schemas/matching.m12.leadership.v1.schema.json"),
        CHECK_COMMAND);
    addClaim(
        claims,
        "same-identity-one-effect",
        "correctness",
        "Executed witness-ledger assertions recompute that UNKNOWN retries preserve byte-identical"
            + " durable identity and produce one business effect with original-result replay.",
        object(
            "coverage", check.path("coverage"),
            "judgeInspection", check.path("judgeInspection")),
        staging,
        byPath,
        List.of("reports/check/coverage.json", "schemas/matching.m12.coverage.v1.schema.json"),
        CHECK_COMMAND);
    addClaim(
        claims,
        "former-leader-catchup",
        "fault-tolerance",
        "The former Leader restarts from preserved state only after the dependency-pinned Archive"
            + " mark-file activity age is observed beyond its liveness timeout, then returns as a"
            + " follower and catches up all frozen durable-identity bindings and business state.",
        object("catchup", check.path("catchup")),
        staging,
        byPath,
        List.of("reports/check/catchup.json", "schemas/matching.m12.catchup.v1.schema.json"),
        CHECK_COMMAND);
    addClaim(
        claims,
        "no-quorum-recovery",
        "fault-tolerance",
        "A bounded minority interval produces no ACK; after quorum restoration the same durable"
            + " identity converges through either the explicitly allowed NEW or DUPLICATE branch.",
        object(
            "quorum", check.path("quorum"),
            "judgeInspection", check.path("judgeInspection")),
        staging,
        byPath,
        List.of("reports/check/quorum.json", "schemas/matching.m12.quorum.v1.schema.json"),
        CHECK_COMMAND);
    addClaim(
        claims,
        "final-state-equivalence",
        "correctness",
        "All three members converge to the direct deterministic oracle's full business state after"
            + " the frozen failover and recovery sequence, excluding runtime term metadata from the"
            + " semantic digest.",
        object("stateEquivalence", check.path("stateEquivalence")),
        staging,
        byPath,
        List.of(
            "reports/check/state-equivalence.json",
            "schemas/matching.m12.state-equivalence.v1.schema.json"),
        CHECK_COMMAND);
    addClaim(
        claims,
        "semantic-mutants",
        "mutation-testing",
        "Eight frozen single-fault candidates are killed with exact replayable STUDENT_FAILURE"
            + " fingerprints and three SYSTEM_ERROR controls never count as kills; this is"
            + " semantic-model-only evidence, not real Cluster fault execution.",
        object(
            "mutants", check.path("mutants"),
            "replay", check.path("replay")),
        staging,
        byPath,
        List.of(
            "reports/check/mutants.json",
            "reports/check/counterexamples.json",
            "reports/check/replay.json",
            "schemas/matching.m12.mutants.v1.schema.json",
            "schemas/matching.m12.counterexamples.v1.schema.json",
            "schemas/matching.m12.replay.v1.schema.json"),
        CHECK_COMMAND);
    addClaim(
        claims,
        "architecture-release",
        "architecture",
        "The infrastructure-free matching core and bounded M12 runtime architecture pass at a clean"
            + " commit identified by both annotated course/m12-complete and annotated"
            + " matching-0.8.0 release tags.",
        object(
            "architecture", check.path("architecture"),
            "releaseTarget", check.path("releaseTarget")),
        staging,
        byPath,
        List.of(
            "reports/check/architecture.json",
            "schemas/matching.m12.architecture.v1.schema.json",
            "reports/check/check.json",
            "schemas/matching.m12.check.v2.schema.json",
            "schemas/cex.lab-evidence.v2.schema.json"),
        EVIDENCE_COMMAND);

    ArrayNode limitations = manifest.putArray("limitations");
    LIMITATIONS.forEach(limitations::add);
    manifest.putNull("supersedes");
    manifest.put("generatedAt", Instant.now().toString());
    return manifest;
  }

  private static ObjectNode manifestEnvironment(JsonNode environment) {
    ObjectNode result = JsonSupport.MAPPER.createObjectNode();
    String javaVersion = requiredText(environment, "javaVersion");
    String osName = requiredText(environment, "osName");
    String osVersion = requiredText(environment, "osVersion");
    result.put("java", javaVersion);
    result.put("os", osName + " " + osVersion);
    result.put("arch", requiredText(environment, "osArchitecture"));
    for (String field : List.of("javaRuntime", "javaVersion", "javaVendor", "vmName")) {
      result.put(field, requiredText(environment, field));
    }
    JsonNode arguments = environment.path("jvmArguments");
    require(arguments.isArray(), "M12 environment lacks jvmArguments");
    result.set("jvmArguments", arguments.deepCopy());
    result.put("osName", osName);
    result.put("osVersion", osVersion);
    result.put("osArchitecture", requiredText(environment, "osArchitecture"));
    copyPositiveInteger(environment, result, "availableProcessors");
    copyPositiveLong(environment, result, "physicalMemoryBytes");
    copyPositiveLong(environment, result, "maximumHeapBytes");
    JsonNode collectors = environment.path("garbageCollectorNames");
    require(collectors.isArray() && collectors.size() > 0, "M12 environment lacks GC names");
    result.set("garbageCollectorNames", collectors.deepCopy());
    for (String field : List.of("cpuModel", "storageDevice", "filesystem", "powerPolicy")) {
      result.put(field, requiredText(environment, field));
    }
    for (String field : List.of("walRoot", "walRootUri", "walFileStoreName", "walFileStoreType")) {
      result.put(field, requiredText(environment, field));
    }
    copyPositiveLong(environment, result, "walFileStoreTotalSpaceBytes");
    copyNonNegativeLong(environment, result, "walFileStoreUsableSpaceBytes");
    copyNonNegativeLong(environment, result, "walFileStoreUnallocatedSpaceBytes");
    Instant.parse(requiredText(environment, "runStartedAt"));
    Instant.parse(requiredText(environment, "runFinishedAt"));
    result.put("runStartedAt", environment.path("runStartedAt").stringValue());
    result.put("runFinishedAt", environment.path("runFinishedAt").stringValue());
    return result;
  }

  private static void copyPositiveInteger(JsonNode source, ObjectNode target, String field) {
    JsonNode value = source.path(field);
    require(value.isIntegralNumber() && value.intValue() > 0, "M12 environment lacks " + field);
    target.put(field, value.intValue());
  }

  private static void copyPositiveLong(JsonNode source, ObjectNode target, String field) {
    JsonNode value = source.path(field);
    require(value.isIntegralNumber() && value.longValue() > 0, "M12 environment lacks " + field);
    target.put(field, value.longValue());
  }

  private static void copyNonNegativeLong(JsonNode source, ObjectNode target, String field) {
    JsonNode value = source.path(field);
    require(value.isIntegralNumber() && value.longValue() >= 0, "M12 environment lacks " + field);
    target.put(field, value.longValue());
  }

  private static void addClaim(
      ArrayNode claims,
      String id,
      String category,
      String statement,
      ObjectNode observations,
      Path staging,
      Map<String, SourceArtifact> artifacts,
      List<String> paths,
      String command) {
    ObjectNode claim = claims.addObject();
    claim.put("id", id);
    claim.put("category", category);
    claim.put("statement", statement);
    claim.put("status", "pass");
    claim.put("command", command);
    claim.set("observations", observations);
    ArrayNode bindings = claim.putArray("artifacts");
    for (String path : paths) {
      SourceArtifact expected = artifacts.get(path);
      require(expected != null, "claim names unknown M12 artifact: " + path);
      Path file = staging.resolve(path).normalize();
      require(file.startsWith(staging), "claim artifact escapes M12 evidence root");
      require(
          expected.sha256().equals(Hashing.sha256Hex(readBytes(file))),
          "M12 artifact changed before binding: " + path);
      bindings.addObject().put("path", path).put("sha256", expected.sha256());
    }
  }

  private static ObjectNode object(Object... values) {
    require(values.length % 2 == 0, "object requires key/value pairs");
    ObjectNode object = JsonSupport.MAPPER.createObjectNode();
    for (int index = 0; index < values.length; index += 2) {
      String key = (String) values[index];
      JsonNode value = (JsonNode) values[index + 1];
      require(value.isObject(), "M12 check observation is missing: " + key);
      object.set(key, value.deepCopy());
    }
    return object;
  }

  private static JsonNode validateCheck(
      Path schemaRoot, Path checkPath, Path reports, String sourceCommit) {
    JsonNode check = JsonSupport.parse(readBytes(checkPath));
    JsonSupport.validate(
        check, readString(schemaRoot.resolve(M12CheckRunner.CHECK_SCHEMA_PATH)), false);
    require(
        "matching.m12.check.v2".equals(check.path("schemaVersion").stringValue()),
        "M12 check schema changed");
    require(
        M12CheckRunner.PASS.equals(check.path("status").stringValue()), "M12 check is not PASS");
    require("0.15".equals(check.path("contractPlanVersion").stringValue()), "M12 plan changed");
    require(
        sourceCommit.equals(check.path("source").path("commit").stringValue()),
        "M12 check source commit changed");
    require(!check.path("source").path("dirty").booleanValue(), "M12 check reports dirty source");
    JsonNode release = check.path("releaseTarget");
    require(UNIT_TAG.equals(release.path("unitTag").stringValue()), "M12 check unit tag changed");
    require(
        PRODUCT_RELEASE.equals(release.path("productRelease").stringValue()),
        "M12 check product release changed");
    require(
        release.path("singleShard").booleanValue()
            && !release.path("performanceQualified").booleanValue()
            && !release.path("backupRestoreQualified").booleanValue()
            && !release.path("externalServices").booleanValue(),
        "M12 check crossed its bounded release profile");
    JsonNode coverage = check.path("coverage");
    require(
        "REAL_AERON_CHILD_PROCESSES".equals(coverage.path("executionScope").stringValue())
            && coverage.path("clusterEvidenceQualified").booleanValue()
            && !coverage.path("labelsAcceptedWithoutWitness").booleanValue()
            && coverage.path("allAssertionsPassed").booleanValue()
            && coverage.path("ledgerVerifiedAgainstExecutionReplay").booleanValue(),
        "M12 coverage lacks real executed witness evidence");
    JsonNode mutants = check.path("mutants");
    requireSemanticModelBoundary(mutants, "mutants");
    JsonNode replay = check.path("replay");
    require(
        replay.path("semanticModelOnly").booleanValue()
            && !replay.path("systemErrorCountedAsKill").booleanValue(),
        "M12 replay crossed its semantic-model boundary");
    require(
        check.path("judgeInspection").path("realAeronChildProcesses").booleanValue(),
        "M12 judge inspection is not real Cluster evidence");
    require(
        check.path("environment").path("correctnessOnly").booleanValue()
            && !check.path("environment").path("performanceQualified").booleanValue()
            && check.path("environment").path("singleHost").booleanValue(),
        "M12 environment boundary changed");
    verifyContractCorrection(check);
    JsonNode stateEquivalence = check.path("stateEquivalence");
    String expectedIdentityResultDigest =
        requiredSha256(stateEquivalence, "expectedIdentityResultDigest");
    require(
        stateEquivalence.path("stateEquivalent").booleanValue()
            && stateEquivalence.path("identityCount").intValue() == 66
            && stateEquivalence.path("allMembersIdentityCountExact").booleanValue()
            && stateEquivalence
                .path("allMembersIdentityResultDigestMatchDirectOracle")
                .booleanValue()
            && expectedIdentityResultDigest.equals(
                requiredSha256(stateEquivalence, "identityResultDigest")),
        "M12 member identity state is not bound to the direct oracle");
    JsonNode memberStates = stateEquivalence.path("members");
    require(memberStates.isArray() && memberStates.size() == 3, "M12 final member set changed");
    for (JsonNode member : memberStates) {
      require(
          member.path("identityCount").intValue() == 66
              && expectedIdentityResultDigest.equals(
                  requiredSha256(member, "identityResultDigest")),
          "M12 member identity table differs from the direct oracle");
    }

    Map<String, JsonNode> strictReports = M12StrictReports.validateAll(schemaRoot, reports, check);
    Map<String, String> embedded =
        Map.ofEntries(
            Map.entry("inherited-m11.json", "inheritedM11"),
            Map.entry("corpus.json", "workloadProfile"),
            Map.entry("m12-command-history.json", "commandOutcomes"),
            Map.entry("topology.json", "clusterTopology"),
            Map.entry("leadership.json", "leadership"),
            Map.entry("quorum.json", "quorum"),
            Map.entry("catchup.json", "catchup"),
            Map.entry("state-equivalence.json", "stateEquivalence"),
            Map.entry("coverage.json", "coverage"),
            Map.entry("mutants.json", "mutants"),
            Map.entry("replay.json", "replay"),
            Map.entry("architecture.json", "architecture"),
            Map.entry("environment.json", "environment"));
    embedded.forEach(
        (name, field) ->
            require(
                check
                    .path(field)
                    .equals(
                        strictReports.containsKey(name)
                            ? strictReports.get(name)
                            : JsonSupport.parse(readBytes(reports.resolve(name)))),
                "M12 check projection differs from " + name));
    validateReport(
        schemaRoot,
        reports.resolve("coverage.json"),
        M12CheckRunner.COVERAGE_SCHEMA_PATH,
        "coverage");
    validateReport(
        schemaRoot, reports.resolve("mutants.json"), M12CheckRunner.MUTANTS_SCHEMA_PATH, "mutants");
    validateReport(
        schemaRoot,
        reports.resolve("counterexamples.json"),
        M12CheckRunner.COUNTEREXAMPLES_SCHEMA_PATH,
        "counterexamples");
    validateReport(
        schemaRoot, reports.resolve("replay.json"), M12CheckRunner.REPLAY_SCHEMA_PATH, "replay");

    JsonNode bindings = check.path("artifactBindings");
    require(bindings.size() == REPORT_ARTIFACTS.size(), "M12 check binding count changed");
    for (int index = 0; index < REPORT_ARTIFACTS.size(); index++) {
      String name = REPORT_ARTIFACTS.get(index);
      JsonNode binding = bindings.get(index);
      require(name.equals(binding.path("path").stringValue()), "M12 check binding order changed");
      Path file = reports.resolve(name).normalize();
      require(file.startsWith(reports), "M12 report binding escapes report root");
      require(Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS), "missing M12 report " + name);
      require(size(file) == binding.path("bytes").longValue(), "M12 report size mismatch: " + name);
      require(
          Hashing.sha256Hex(readBytes(file)).equals(binding.path("sha256").stringValue()),
          "M12 report hash mismatch: " + name);
    }
    return check;
  }

  private static void verifyContractCorrection(JsonNode check) {
    JsonNode correction = check.path("contractCorrection");
    require(correction.isObject(), "M12 check lacks the automatic-election contract correction");
    require(
        correction.equals(check.path("clusterTopology").path("contractCorrection")),
        "M12 contract correction differs from the topology observation");
    int initialLeaderId = correction.path("initialLeaderId").intValue();
    require(
        "matching.m12.contract-correction.v1".equals(correction.path("schemaVersion").stringValue())
            && M12CheckRunner.PASS.equals(correction.path("status").stringValue())
            && M12StartCheckRunner.WORKLOAD_SHA256.equals(
                correction.path("frozenWorkloadSha256").stringValue())
            && "realClusterProfile.appointedInitialLeaderId"
                .equals(correction.path("frozenField").stringValue())
            && correction.path("frozenAppointedInitialLeaderId").intValue() == 0
            && !correction.path("frozenAppointmentCompatibleWithThreeMemberHa").booleanValue()
            && correction.path("effectiveAeronAppointedLeaderId").intValue() == -1
            && correction.path("automaticElection").booleanValue()
            && "OBSERVED_AUTOMATIC_ELECTION"
                .equals(correction.path("initialLeaderSelection").stringValue())
            && initialLeaderId >= 0
            && initialLeaderId <= 2
            && CONTRACT_CORRECTION_REASON.equals(correction.path("reason").stringValue()),
        "M12 automatic-election contract correction changed");
  }

  private static void validateReport(
      Path schemaRoot, Path report, String schemaPath, String label) {
    require(
        Files.isRegularFile(report, LinkOption.NOFOLLOW_LINKS), "missing M12 " + label + " report");
    JsonSupport.validate(
        JsonSupport.parse(readBytes(report)), readString(schemaRoot.resolve(schemaPath)), false);
  }

  private static void requireSemanticModelBoundary(JsonNode mutants, String label) {
    require(
        mutants.path("semanticModelOnly").booleanValue()
            && !mutants.path("realClusterExecuted").booleanValue()
            && !mutants.path("eligibleAsClusterEvidence").booleanValue()
            && !mutants.path("systemErrorCountedAsKill").booleanValue(),
        "M12 " + label + " crossed its semantic-model boundary");
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
            Map.entry("planVersion", "0.15"),
            Map.entry("project", "matching"),
            Map.entry("unit", "M12"),
            Map.entry("lifecycle", "COMPLETE"),
            Map.entry("designDepth", "IMPLEMENTED"),
            Map.entry("startRef", START_TAG),
            Map.entry("completeRef", UNIT_TAG),
            Map.entry("productRelease", PRODUCT_RELEASE),
            Map.entry("m12Check.expectedStatus", M12CheckRunner.PASS),
            Map.entry("evidencePath", EVIDENCE_DIRECTORY + "/manifest.json"));
    require(properties.size() == expected.size(), "M12 course declaration field set changed");
    expected.forEach(
        (key, value) ->
            require(value.equals(properties.getProperty(key)), "M12 course changed: " + key));
  }

  private static void verifyReleaseState(
      Path root,
      String sourceCommit,
      FrozenInputs frozen,
      SchemaSnapshot schemas,
      ReleaseExpectations expectations) {
    requireClean(root);
    require(
        sourceCommit.equals(git(root, "rev-parse", "HEAD").strip()),
        "HEAD changed during M12 evidence generation");
    verifyAnnotatedExact(root, UNIT_TAG, sourceCommit);
    verifyAnnotatedExact(root, PRODUCT_RELEASE, sourceCommit);
    String startCommit = peeledAnnotated(root, START_TAG);
    require(
        expectations.startCommit().equals(startCommit),
        START_TAG + " does not peel to the frozen M12 start commit");
    require(isAncestor(root, startCommit, sourceCommit), START_TAG + " is not an ancestor of M12");
    String inheritedCommit = peeledAnnotated(root, INHERITED_TAG);
    require(
        expectations.inheritedCommit().equals(inheritedCommit),
        INHERITED_TAG + " does not peel to the frozen M11 commit");
    require(
        isAncestor(root, inheritedCommit, startCommit),
        INHERITED_TAG + " is not an ancestor of " + START_TAG);
    require(
        isAncestor(root, inheritedCommit, sourceCommit),
        INHERITED_TAG + " is not an ancestor of M12");
    schemas.verify(root);
    frozen.verify(root);
  }

  private static void verifyAnnotatedExact(Path root, String tag, String sourceCommit) {
    require("tag".equals(git(root, "cat-file", "-t", tag).strip()), tag + " is not annotated");
    require(
        sourceCommit.equals(git(root, "rev-parse", tag + "^{}").strip()),
        tag + " does not peel to HEAD");
  }

  private static String peeledAnnotated(Path root, String tag) {
    require("tag".equals(git(root, "cat-file", "-t", tag).strip()), tag + " is not annotated");
    String commit = git(root, "rev-parse", tag + "^{}").strip();
    require(FULL_COMMIT.matcher(commit).matches(), tag + " does not peel to a commit");
    return commit;
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

  private static void prepare(Path staging, List<SourceArtifact> artifacts) {
    try {
      for (SourceArtifact artifact : artifacts) {
        Path destination = staging.resolve(artifact.evidencePath()).normalize();
        require(destination.startsWith(staging), "artifact escapes M12 staging root");
        Files.createDirectories(destination.getParent());
        Files.copy(artifact.source(), destination, StandardCopyOption.REPLACE_EXISTING);
      }
    } catch (IOException failure) {
      throw new IllegalStateException("cannot prepare M12 evidence artifacts", failure);
    }
  }

  private static void verifyEvidenceTree(
      Path repositoryRoot,
      Path evidence,
      List<SourceArtifact> artifacts,
      String sourceCommit,
      FrozenInputs frozen,
      SchemaSnapshot schemas,
      ReleaseExpectations expectations,
      ObjectNode expectedManifest) {
    requireSafeTree(repositoryRoot, evidence);
    verifyCopiedArtifacts(evidence, artifacts);
    JsonNode check =
        validateCheck(
            evidence,
            evidence.resolve("reports/check/check.json"),
            evidence.resolve("reports/check"),
            sourceCommit);
    JsonNode manifest = JsonSupport.parse(readBytes(evidence.resolve("manifest.json")));
    require(manifest instanceof ObjectNode, "M12 evidence manifest is not an object");
    verifyManifest(evidence, (ObjectNode) manifest, artifacts, sourceCommit, expectedManifest);
    Set<String> expected = new LinkedHashSet<>();
    artifacts.forEach(artifact -> expected.add(portable(artifact.evidencePath())));
    expected.add("manifest.json");
    require(expected.equals(fileInventory(evidence)), "M12 evidence file inventory changed");
    require(
        M12CheckRunner.PASS.equals(check.path("status").stringValue()), "M12 check is not PASS");
    verifyBudget(evidence);
    verifyReleaseState(repositoryRoot, sourceCommit, frozen, schemas, expectations);
  }

  private static void verifyManifest(
      Path evidence,
      ObjectNode manifest,
      List<SourceArtifact> artifacts,
      String sourceCommit,
      ObjectNode expectedManifest) {
    JsonSupport.validate(
        manifest, readString(evidence.resolve("schemas/cex.lab-evidence.v2.schema.json")), true);
    require("M12".equals(manifest.path("unit").stringValue()), "manifest unit changed");
    require(UNIT_TAG.equals(manifest.path("unitTag").stringValue()), "manifest tag changed");
    require(
        PRODUCT_RELEASE.equals(manifest.path("productRelease").stringValue()),
        "manifest product release changed");
    require(
        sourceCommit.equals(manifest.path("source").path("commit").stringValue()),
        "manifest source commit changed");
    require(!manifest.path("source").path("dirty").booleanValue(), "manifest reports dirty source");

    List<String> ids = new ArrayList<>();
    List<String> bound = new ArrayList<>();
    for (JsonNode claim : manifest.path("claims")) {
      require("pass".equals(claim.path("status").stringValue()), "non-pass M12 claim");
      ids.add(claim.path("id").stringValue());
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
    require(REQUIRED_CLAIMS.equals(ids), "M12 claim set or order changed");
    require(new LinkedHashSet<>(bound).size() == bound.size(), "M12 artifact bound more than once");
    Set<String> expected = new LinkedHashSet<>();
    artifacts.forEach(artifact -> expected.add(portable(artifact.evidencePath())));
    require(expected.equals(new LinkedHashSet<>(bound)), "every M12 artifact must bind once");
    require(
        LIMITATIONS.equals(
            manifest.path("limitations").valueStream().map(JsonNode::stringValue).toList()),
        "M12 limitations changed");
    require(
        manifest
            .path("claims")
            .path(8)
            .path("statement")
            .stringValue()
            .contains("semantic-model-only"),
        "M12 mutant claim does not disclose its semantic-model-only scope");
    Instant.parse(requiredText(manifest, "generatedAt"));
    require(
        expectedManifest.equals(manifest),
        "M12 manifest is not the exact projection of the verified check and artifacts");
  }

  private static void verifySourceArtifacts(Path root, List<SourceArtifact> artifacts) {
    for (SourceArtifact artifact : artifacts) {
      SafeOutputPaths.requireNoSymlinkComponents(root, artifact.source());
      require(
          Files.isRegularFile(artifact.source(), LinkOption.NOFOLLOW_LINKS),
          "M12 source artifact is missing: " + artifact.source());
      require(size(artifact.source()) == artifact.bytes(), "M12 source artifact size changed");
      require(
          Hashing.sha256Hex(readBytes(artifact.source())).equals(artifact.sha256()),
          "M12 source artifact hash changed");
    }
  }

  private static void verifyCopiedArtifacts(Path root, List<SourceArtifact> artifacts) {
    for (SourceArtifact artifact : artifacts) {
      Path file = root.resolve(artifact.evidencePath()).normalize();
      require(file.startsWith(root), "copied artifact escapes M12 evidence root");
      require(
          Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS),
          "copied M12 artifact is missing: " + artifact.evidencePath());
      require(size(file) == artifact.bytes(), "copied M12 artifact size changed");
      require(
          Hashing.sha256Hex(readBytes(file)).equals(artifact.sha256()),
          "copied M12 artifact hash changed");
    }
  }

  private static Path createStaging(Path root, Path destination) {
    Path parent = destination.getParent();
    require(parent != null, "M12 evidence directory has no parent");
    SafeOutputPaths.requireNoSymlinkComponents(root, parent);
    try {
      Files.createDirectories(parent);
      SafeOutputPaths.requireNoSymlinkComponents(root, parent);
      return Files.createTempDirectory(parent, ".M12-staging-");
    } catch (IOException failure) {
      throw new IllegalStateException("cannot create M12 staging directory", failure);
    }
  }

  private static Publication publishAtomically(Path root, Path staging, Path destination) {
    requireSafeTree(root, staging);
    SafeOutputPaths.requireNoSymlinkComponents(root, destination);
    Path backup = null;
    if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
      requireSafeTree(root, destination);
      backup = createBackupReservation(root, destination);
      moveAtomically(destination, backup, "cannot safeguard prior M12 evidence");
    }
    try {
      moveAtomically(staging, destination, "cannot publish M12 evidence");
      return new Publication(backup);
    } catch (RuntimeException failure) {
      if (backup != null) {
        try {
          moveAtomically(backup, destination, "cannot restore prior M12 evidence");
        } catch (RuntimeException restoreFailure) {
          failure.addSuppressed(restoreFailure);
        }
      }
      throw failure;
    }
  }

  private static Path createBackupReservation(Path root, Path destination) {
    Path parent = destination.getParent();
    require(parent != null, "M12 evidence directory has no parent");
    SafeOutputPaths.requireNoSymlinkComponents(root, parent);
    try {
      Path reservation = Files.createTempDirectory(parent, ".M12-backup-");
      Files.delete(reservation);
      return reservation;
    } catch (IOException failure) {
      throw new IllegalStateException("cannot reserve prior M12 evidence backup", failure);
    }
  }

  private static void moveAtomically(Path source, Path destination, String message) {
    try {
      Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE);
    } catch (AtomicMoveNotSupportedException failure) {
      throw new IllegalStateException("atomic M12 evidence publication is unavailable", failure);
    } catch (IOException failure) {
      throw new IllegalStateException(message, failure);
    }
  }

  private static void verifyBudget(Path tree) {
    requireSafeTree(tree, tree);
    try (var paths = Files.walk(tree)) {
      List<Path> files =
          paths.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)).toList();
      require(files.size() <= MAX_FILES, "M12 evidence exceeds 64 files");
      long total = 0;
      for (Path file : files) {
        long bytes = Files.size(file);
        require(bytes > 0 && bytes <= MAX_FILE_BYTES, "M12 evidence file exceeds 2 MiB: " + file);
        total = Math.addExact(total, bytes);
      }
      require(total <= MAX_TREE_BYTES, "M12 evidence exceeds 10 MiB");
    } catch (IOException failure) {
      throw new IllegalStateException("cannot measure M12 evidence", failure);
    }
  }

  private static void requireSafeTree(Path root, Path tree) {
    SafeOutputPaths.requireNoSymlinkComponents(root, tree);
    require(Files.isDirectory(tree, LinkOption.NOFOLLOW_LINKS), "missing M12 artifact tree");
    try (var paths = Files.walk(tree)) {
      paths.forEach(path -> require(!Files.isSymbolicLink(path), "symlink in M12 evidence tree"));
    } catch (IOException failure) {
      throw new IllegalStateException("cannot inspect M12 evidence tree", failure);
    }
  }

  private static Set<String> fileInventory(Path root) {
    try (var paths = Files.walk(root)) {
      return new LinkedHashSet<>(
          paths
              .filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
              .map(root::relativize)
              .map(M12EvidenceWriter::portable)
              .sorted()
              .toList());
    } catch (IOException failure) {
      throw new IllegalStateException("cannot inventory M12 evidence", failure);
    }
  }

  private static String requiredText(JsonNode object, String field) {
    String value = object.path(field).stringValue();
    require(value != null && !value.isBlank(), "M12 value lacks " + field);
    return value;
  }

  private static String requiredSha256(JsonNode object, String field) {
    String value = requiredText(object, field);
    require(value.matches("^[a-f0-9]{64}$"), "M12 value is not SHA-256: " + field);
    return value;
  }

  private static Path safeRelative(String value) {
    Path path = Path.of(value);
    require(!path.isAbsolute(), "absolute M12 artifact path is forbidden");
    Path normalized = path.normalize();
    require(!normalized.startsWith(".."), "parent traversal in M12 artifact path");
    require(portable(normalized).equals(value), "non-canonical M12 artifact path");
    return normalized;
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
    return new String(readBytes(path), StandardCharsets.UTF_8);
  }

  private static String git(Path root, String... arguments) {
    return new String(gitBytes(root, arguments), StandardCharsets.UTF_8);
  }

  private static byte[] gitBytes(Path root, String... arguments) {
    List<String> command = new ArrayList<>();
    command.add("git");
    command.addAll(List.of(arguments));
    try {
      Process process = new ProcessBuilder(command).directory(root.toFile()).start();
      byte[] output = process.getInputStream().readAllBytes();
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
        "repository must be clean before M12 evidence generation");
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

  private record Publication(Path backup) {
    void complete() {
      if (backup != null) deleteTree(backup);
    }

    void restore(Path staging, Path destination, RuntimeException originalFailure) {
      try {
        if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
          require(!Files.exists(staging, LinkOption.NOFOLLOW_LINKS), "M12 staging path reappeared");
          moveAtomically(destination, staging, "cannot quarantine failed M12 evidence");
        }
        if (backup != null) {
          moveAtomically(backup, destination, "cannot restore prior M12 evidence");
        }
      } catch (RuntimeException restoreFailure) {
        originalFailure.addSuppressed(restoreFailure);
      }
    }
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new IllegalStateException(message);
  }

  @FunctionalInterface
  interface CheckExecutor {
    M12CheckRunner.Result run(Path root, Path reports);
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

  record ReleaseExpectations(String startCommit, String inheritedCommit) {
    ReleaseExpectations {
      require(FULL_COMMIT.matcher(startCommit).matches(), "invalid expected M12 start commit");
      require(FULL_COMMIT.matcher(inheritedCommit).matches(), "invalid expected M11 commit");
    }

    static ReleaseExpectations production() {
      return new ReleaseExpectations(START_COMMIT, INHERITED_COMMIT);
    }
  }

  private record FrozenInputs(byte[] workloadBytes, String workloadSha256) {
    static FrozenInputs capture(Path root) {
      Path path = root.resolve(M12StartCheckRunner.WORKLOAD_PATH);
      SafeOutputPaths.requireNoSymlinkComponents(root, path);
      byte[] bytes = readBytes(path);
      String digest = Hashing.sha256Hex(bytes);
      require(M12StartCheckRunner.WORKLOAD_SHA256.equals(digest), "M12 workload hash changed");
      JsonNode workload = JsonSupport.parse(bytes);
      JsonSupport.validate(
          workload, readString(root.resolve(M12StartCheckRunner.WORKLOAD_SCHEMA_PATH)), false);
      return new FrozenInputs(bytes.clone(), digest);
    }

    @Override
    public byte[] workloadBytes() {
      return workloadBytes.clone();
    }

    void verify(Path root) {
      FrozenInputs current = capture(root);
      require(
          workloadSha256.equals(current.workloadSha256()),
          "M12 workload changed during evidence run");
      require(Arrays.equals(workloadBytes, current.workloadBytes), "M12 workload bytes changed");
      byte[] tagged = gitBytes(root, "show", START_TAG + ":" + M12StartCheckRunner.WORKLOAD_PATH);
      require(Arrays.equals(workloadBytes, tagged), START_TAG + " differs at the frozen workload");
    }
  }

  private record SourceArtifact(Path source, Path evidencePath, String sha256, long bytes) {
    static SourceArtifact capture(Path root, Path source, Path evidencePath) {
      Path normalized = source.toAbsolutePath().normalize();
      require(normalized.startsWith(root), "M12 source artifact escapes repository");
      SafeOutputPaths.requireNoSymlinkComponents(root, normalized);
      require(
          Files.isRegularFile(normalized, LinkOption.NOFOLLOW_LINKS),
          "missing M12 source artifact: " + normalized);
      Path relative = safeRelative(portable(evidencePath.normalize()));
      long bytes = size(normalized);
      require(bytes > 0 && bytes <= MAX_FILE_BYTES, "M12 source artifact exceeds 2 MiB");
      return new SourceArtifact(
          normalized, relative, Hashing.sha256Hex(readBytes(normalized)), bytes);
    }
  }

  private record SchemaSnapshot(Map<Path, String> hashes) {
    static SchemaSnapshot capture(Path root) {
      Map<Path, String> hashes = new LinkedHashMap<>();
      for (String value : EVIDENCE_SCHEMAS) {
        Path path = Path.of(value);
        SafeOutputPaths.requireNoSymlinkComponents(root, root.resolve(path));
        hashes.put(path, Hashing.sha256Hex(readBytes(root.resolve(path))));
      }
      return new SchemaSnapshot(Map.copyOf(hashes));
    }

    void verify(Path root) {
      hashes.forEach(
          (path, digest) ->
              require(
                  digest.equals(Hashing.sha256Hex(readBytes(root.resolve(path)))),
                  "M12 evidence schema changed: " + path));
    }
  }

  public record Result(
      Path manifestPath, String sourceCommit, String manifestSha256, int artifactCount) {}
}
