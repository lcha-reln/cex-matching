package io.github.lchareln.cex.matching.testkit;

import io.github.lchareln.cex.matching.benchmark.EnvironmentFingerprint;
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

/** Publishes bounded, clean-tree, annotated-tag-bound M11 correctness evidence. */
public final class M11EvidenceWriter {
  static final String UNIT_TAG = "course/m11-complete";
  private static final String START_TAG = "course/m11-start";
  private static final String INHERITED_TAG = "course/m10-complete";
  private static final String INHERITED_PRODUCT_TAG = "matching-0.5.0";
  private static final String CHECK_DIRECTORY = "build/reports/m11";
  private static final String EVIDENCE_DIRECTORY = "build/lab-evidence/M11";
  private static final String CHECK_SCHEMA = "schemas/matching.m11.check.v2.schema.json";
  private static final String COUNTEREXAMPLE_SCHEMA =
      "schemas/matching.m11.counterexamples.v1.schema.json";
  private static final String EVIDENCE_SCHEMA = "schemas/cex.lab-evidence.v2.schema.json";
  private static final long MAX_FILE_BYTES = 2L * 1024 * 1024;
  private static final long MAX_TREE_BYTES = 10L * 1024 * 1024;
  private static final int MAX_FILES = 64;
  private static final Pattern FULL_COMMIT = Pattern.compile("^[a-f0-9]{40}(?:[a-f0-9]{24})?$");
  private static final String CHECK_COMMAND = "./gradlew m11Check --no-daemon";
  private static final String EVIDENCE_COMMAND = "./gradlew m11Evidence --no-daemon";

  static final List<String> REPORT_ARTIFACTS = List.copyOf(M11CheckRunner.OUTPUTS);

  static final List<String> REQUIRED_CLAIMS =
      List.of(
          "m00-m10-semantic-regression",
          "single-node-clustered-service",
          "correlated-apply-response",
          "direct-cluster-business-equivalence",
          "cluster-snapshot-restart",
          "protocol-compatibility-and-mutants",
          "architecture-and-unit-identity");

  static final List<String> LIMITATIONS =
      List.of(
          "M11 runs one Aeron Cluster member with an appointed leader. It is not a replicated or high-availability deployment and makes no quorum, election, or failover claim.",
          "The fixed 22 scenarios, one continuous 4,096-action generated corpus, 28 obligations, and 10 executable mutants are finite evidence, not exhaustive exploration or formal verification.",
          "The snapshot witness covers an accepted administrative request, observed snapshot completion, preserved directories, and a controlled same-machine restart; it is not a process-crash, host-loss, disk-loss, or power-loss test.",
          "M11 makes no throughput, latency, capacity, resource-efficiency, or production performance claim; environment fields scope this correctness run only.",
          "M11 does not run a three-node cluster, inject member or network faults, prove leader failover, run Cluster Backup, or exercise disaster recovery.",
          "M11 uses no external service and excludes Rest, WebSocket, TLS, load balancers, accounts, balances, positions, settlement, database synchronization, and external side effects.",
          "Protocol evidence is limited to the frozen current and N-1 request, response, and snapshot fixtures; it does not claim arbitrary-version compatibility, rollback safety, or online mixed-version upgrade safety.",
          "The evidence manifest keeps the shared walRoot field for schema compatibility; in M11 it identifies the owned Aeron Cluster runtime root and does not imply a standalone application WAL.");

  private final CheckExecutor checkExecutor;
  private final BoundaryHook boundaryHook;

  public M11EvidenceWriter() {
    this((root, reports) -> new M11CheckRunner().run(root, reports), BoundaryHook.NOOP);
  }

  M11EvidenceWriter(CheckExecutor checkExecutor) {
    this(checkExecutor, BoundaryHook.NOOP);
  }

  M11EvidenceWriter(CheckExecutor checkExecutor, BoundaryHook boundaryHook) {
    this.checkExecutor = java.util.Objects.requireNonNull(checkExecutor, "checkExecutor");
    this.boundaryHook = java.util.Objects.requireNonNull(boundaryHook, "boundaryHook");
  }

  public Result write(
      Path repositoryRoot, Path checkDirectory, Path evidenceDirectory, String unitTag) {
    Path root = repositoryRoot.toAbsolutePath().normalize();
    Path reports = checkDirectory.toAbsolutePath().normalize();
    Path destination = evidenceDirectory.toAbsolutePath().normalize();
    require(UNIT_TAG.equals(unitTag), "invalid M11 complete tag: " + unitTag);
    require(reports.equals(root.resolve(CHECK_DIRECTORY)), "invalid M11 report directory");
    require(destination.equals(root.resolve(EVIDENCE_DIRECTORY)), "invalid M11 evidence directory");
    require(!reports.startsWith(destination), "M11 report and evidence roots overlap");
    SafeOutputPaths.requireNoSymlinkComponents(root, reports);
    SafeOutputPaths.requireNoSymlinkComponents(root, destination);

    requireClean(root);
    String sourceCommit = git(root, "rev-parse", "HEAD").strip();
    require(FULL_COMMIT.matcher(sourceCommit).matches(), "HEAD is not a full Git commit");
    FrozenInputs frozen = FrozenInputs.capture(root);
    SchemaSnapshot schemas = SchemaSnapshot.capture(root);
    verifyCourse(root);
    verifyReleaseState(root, sourceCommit, frozen, schemas);

    M11CheckRunner.Result fresh = checkExecutor.run(root, reports);
    require("PASS".equals(fresh.status()), "fresh M11 check is not strict PASS");
    require(
        fresh.reportPath().toAbsolutePath().normalize().equals(reports.resolve("check.json")),
        "fresh M11 check returned an unexpected report path");
    JsonNode check = validateCheck(root, fresh.reportPath(), reports, sourceCommit);
    List<SourceArtifact> artifacts = sourceArtifacts(root, reports, frozen);
    verifySourceArtifacts(artifacts);
    verifyReleaseState(root, sourceCommit, frozen, schemas);

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
      verifyReleaseState(root, sourceCommit, frozen, schemas);
      verifySourceArtifacts(artifacts);
      verifyEvidenceTree(root, staging, artifacts, sourceCommit, frozen, schemas, expectedManifest);
      publishAtomically(root, staging, destination);
      try {
        boundaryHook.beforePostPublishVerification(root);
        verifyReleaseState(root, sourceCommit, frozen, schemas);
        verifySourceArtifacts(artifacts);
        verifyEvidenceTree(
            root, destination, artifacts, sourceCommit, frozen, schemas, expectedManifest);
      } catch (RuntimeException failure) {
        deleteTree(destination);
        throw failure;
      }

      Path manifestPath = destination.resolve("manifest.json");
      return new Result(
          manifestPath, sourceCommit, Hashing.sha256Hex(readBytes(manifestPath)), artifacts.size());
    } catch (RuntimeException failure) {
      deleteTree(staging);
      throw failure;
    }
  }

  private static List<SourceArtifact> sourceArtifacts(
      Path root, Path reports, FrozenInputs frozen) {
    List<SourceArtifact> artifacts = new ArrayList<>();
    artifacts.add(
        SourceArtifact.capture(
            root,
            root.resolve(M11StartCheckRunner.WORKLOAD_PATH),
            Path.of("inputs/workload-v1.json")));
    for (Golden golden : frozen.goldens()) {
      artifacts.add(
          SourceArtifact.capture(
              root,
              root.resolve(golden.path()),
              Path.of("inputs/goldens").resolve(golden.path().getFileName())));
    }
    for (String schema :
        List.of(
            M11StartCheckRunner.WORKLOAD_SCHEMA_PATH,
            CHECK_SCHEMA,
            COUNTEREXAMPLE_SCHEMA,
            EVIDENCE_SCHEMA)) {
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
    require(
        artifacts.size() == 12 + REPORT_ARTIFACTS.size(), "M11 evidence source inventory changed");
    Set<Path> unique = new LinkedHashSet<>();
    artifacts.forEach(
        artifact -> require(unique.add(artifact.evidencePath()), "duplicate M11 evidence path"));
    return List.copyOf(artifacts);
  }

  private static ObjectNode manifest(
      String sourceCommit, JsonNode check, Path staging, List<SourceArtifact> artifacts) {
    ObjectNode manifest = JsonSupport.MAPPER.createObjectNode();
    manifest.put("schemaVersion", "cex.lab-evidence.v2");
    manifest.put("case", "high-availability-cex");
    manifest.put("project", "matching");
    manifest.put("unit", "M11");
    manifest.put("unitTag", UNIT_TAG);
    manifest.putNull("productRelease");
    manifest.put("planVersion", "0.14");
    ObjectNode source = manifest.putObject("source");
    source.put("commit", sourceCommit);
    source.put("dirty", false);
    manifest.set("environment", manifestEnvironment(check.path("environment")));

    Map<String, SourceArtifact> byPath = new LinkedHashMap<>();
    artifacts.forEach(artifact -> byPath.put(portable(artifact.evidencePath()), artifact));
    ArrayNode claims = manifest.putArray("claims");
    addClaim(
        claims,
        "m00-m10-semantic-regression",
        "correctness",
        "The current compiled M10 fixed, generated, mutant, and admission-method regression suites remain PASS at the M11 source boundary.",
        object("inheritedM10", check.path("inheritedM10")),
        staging,
        byPath,
        List.of("reports/check/inherited-m10.json"),
        CHECK_COMMAND);
    addClaim(
        claims,
        "single-node-clustered-service",
        "correctness",
        "A real fresh single-member Aeron Cluster accepts and correlates 4,096 commands in each of two independent runs without component errors or external services.",
        object("clusterRuntime", check.path("clusterRuntime")),
        staging,
        byPath,
        List.of("reports/check/cluster-runtime.json", "reports/check/environment.json"),
        CHECK_COMMAND);
    addClaim(
        claims,
        "correlated-apply-response",
        "correctness",
        "The fixed scenarios witness that ingress publication is not business success, log callbacks exclusively apply business state, and responses correlate only after the result is bound.",
        object("fixed", check.path("fixed"), "coverage", check.path("coverage")),
        staging,
        byPath,
        List.of("reports/check/fixed-scenarios.json", "reports/check/coverage.json"),
        CHECK_COMMAND);
    addClaim(
        claims,
        "direct-cluster-business-equivalence",
        "correctness",
        "Two fresh SplitMix64 generations reproduce one continuous 4,096-action corpus byte-for-byte, and the direct, uninterrupted Cluster, and snapshot-restart Cluster paths agree on every business result and final semantic state.",
        object("generator", check.path("generator")),
        staging,
        byPath,
        List.of(
            "inputs/workload-v1.json",
            "reports/check/generated-differential.json",
            "reports/check/generated-requests.canonical.bin"),
        CHECK_COMMAND);
    addClaim(
        claims,
        "cluster-snapshot-restart",
        "correctness",
        "After global action 2,048, the administrative acceptance and RecordingLog completion are separately witnessed before close; restart loads the exact application snapshot identity, digests, and next sequence.",
        object("snapshotRestart", check.path("snapshotRestart")),
        staging,
        byPath,
        List.of("reports/check/check.json"),
        CHECK_COMMAND);
    addClaim(
        claims,
        "protocol-compatibility-and-mutants",
        "mutation-testing",
        "Six byte-exact current and N-1 fixtures fail closed outside the bounded protocol contract, while all ten executable semantic candidates produce persisted STUDENT_FAILURE witnesses and three SYSTEM_ERROR controls never count as kills.",
        object(
            "protocol", check.path("protocol"),
            "mutants", check.path("mutants")),
        staging,
        byPath,
        List.of(
            "inputs/goldens/request-v1.bin",
            "inputs/goldens/request-v2.bin",
            "inputs/goldens/response-v1.bin",
            "inputs/goldens/response-v2.bin",
            "inputs/goldens/snapshot-v1.bin",
            "inputs/goldens/snapshot-v2.bin",
            "schemas/matching.m11.workload.v1.schema.json",
            "schemas/matching.m11.check.v2.schema.json",
            "schemas/matching.m11.counterexamples.v1.schema.json",
            "reports/check/protocol-goldens.json",
            "reports/check/mutants.json",
            "reports/check/counterexamples.json"),
        CHECK_COMMAND);
    addClaim(
        claims,
        "architecture-and-unit-identity",
        "architecture",
        "matching-core remains byte-identical to M10 and infrastructure-free; Aeron is confined to matching-cluster-runtime, the Cluster service writes no standalone WAL, and the annotated M11 unit tag identifies this exact clean commit without a product release.",
        object(
            "architecture", check.path("architecture"),
            "releaseTarget", check.path("releaseTarget")),
        staging,
        byPath,
        List.of("schemas/cex.lab-evidence.v2.schema.json", "reports/check/architecture.json"),
        EVIDENCE_COMMAND);

    ArrayNode limitations = manifest.putArray("limitations");
    LIMITATIONS.forEach(limitations::add);
    manifest.putNull("supersedes");
    manifest.put("generatedAt", Instant.now().toString());
    return manifest;
  }

  private static ObjectNode manifestEnvironment(JsonNode environment) {
    if (positiveLong(environment, "physicalMemoryBytes")
        && environment.path("garbageCollectorNames").isArray()
        && environment.path("garbageCollectorNames").size() > 0
        && positiveLong(environment, "walFileStoreTotalSpaceBytes")) {
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
      result.set("jvmArguments", environment.path("jvmArguments").deepCopy());
      result.put("osName", osName);
      result.put("osVersion", osVersion);
      result.put("osArchitecture", requiredText(environment, "osArchitecture"));
      result.put("availableProcessors", environment.path("availableProcessors").intValue());
      result.put("physicalMemoryBytes", environment.path("physicalMemoryBytes").longValue());
      result.put("maximumHeapBytes", environment.path("maximumHeapBytes").longValue());
      result.set("garbageCollectorNames", environment.path("garbageCollectorNames").deepCopy());
      for (String field : List.of("cpuModel", "storageDevice", "filesystem", "powerPolicy")) {
        result.put(field, requiredText(environment, field));
      }
      copyText(environment, result, "walRoot");
      copyText(environment, result, "walRootUri");
      copyText(environment, result, "walFileStoreName");
      copyText(environment, result, "walFileStoreType");
      result.put(
          "walFileStoreTotalSpaceBytes",
          environment.path("walFileStoreTotalSpaceBytes").longValue());
      result.put(
          "walFileStoreUsableSpaceBytes",
          environment.path("walFileStoreUsableSpaceBytes").longValue());
      result.put(
          "walFileStoreUnallocatedSpaceBytes",
          environment.path("walFileStoreUnallocatedSpaceBytes").longValue());
      copyText(environment, result, "runStartedAt");
      copyText(environment, result, "runFinishedAt");
      return result;
    }

    Path clusterRoot = Path.of(requiredText(environment, "clusterRoot"));
    Instant started = Instant.parse(requiredText(environment, "runStartedAt"));
    Instant finished = Instant.parse(requiredText(environment, "runFinishedAt"));
    try {
      EnvironmentFingerprint captured =
          EnvironmentFingerprint.capture(
              clusterRoot,
              "M11_CORRECTNESS_CPU_NOT_PROFILED",
              requiredText(environment, "fileStoreName"),
              requiredText(environment, "fileStoreType"),
              "M11_CORRECTNESS_POWER_POLICY_NOT_PROFILED",
              started,
              finished);
      return fromFingerprint(captured);
    } catch (IOException failure) {
      throw new IllegalStateException("cannot capture M11 evidence environment", failure);
    }
  }

  private static ObjectNode fromFingerprint(EnvironmentFingerprint value) {
    ObjectNode result = JsonSupport.MAPPER.createObjectNode();
    result.put("java", value.javaVersion());
    result.put("os", value.osName() + " " + value.osVersion());
    result.put("arch", value.osArchitecture());
    result.put("javaRuntime", value.javaRuntime());
    result.put("javaVersion", value.javaVersion());
    result.put("javaVendor", value.javaVendor());
    result.put("vmName", value.vmName());
    value.jvmArguments().forEach(result.putArray("jvmArguments")::add);
    result.put("osName", value.osName());
    result.put("osVersion", value.osVersion());
    result.put("osArchitecture", value.osArchitecture());
    result.put("availableProcessors", value.availableProcessors());
    result.put("physicalMemoryBytes", value.physicalMemoryBytes());
    result.put("maximumHeapBytes", value.maximumHeapBytes());
    value.garbageCollectorNames().forEach(result.putArray("garbageCollectorNames")::add);
    result.put("cpuModel", value.cpuModel());
    result.put("storageDevice", value.storageDevice());
    result.put("filesystem", value.filesystem());
    result.put("powerPolicy", value.powerPolicy());
    result.put("walRoot", value.walRoot());
    result.put("walRootUri", value.walRootUri());
    result.put("walFileStoreName", value.walFileStoreName());
    result.put("walFileStoreType", value.walFileStoreType());
    result.put("walFileStoreTotalSpaceBytes", value.walFileStoreTotalSpaceBytes());
    result.put("walFileStoreUsableSpaceBytes", value.walFileStoreUsableSpaceBytes());
    result.put("walFileStoreUnallocatedSpaceBytes", value.walFileStoreUnallocatedSpaceBytes());
    result.put("runStartedAt", value.runStartedAt().toString());
    result.put("runFinishedAt", value.runFinishedAt().toString());
    return result;
  }

  private static boolean positiveLong(JsonNode object, String field) {
    JsonNode value = object.path(field);
    return value.isIntegralNumber() && value.longValue() > 0;
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
      require(expected != null, "claim names unknown M11 artifact: " + path);
      Path file = staging.resolve(path).normalize();
      require(file.startsWith(staging), "claim artifact escapes M11 evidence root");
      require(
          expected.sha256().equals(Hashing.sha256Hex(readBytes(file))),
          "M11 artifact changed before binding: " + path);
      ObjectNode binding = bindings.addObject();
      binding.put("path", path);
      binding.put("sha256", expected.sha256());
    }
  }

  private static ObjectNode object(Object... values) {
    require(values.length % 2 == 0, "object requires key/value pairs");
    ObjectNode object = JsonSupport.MAPPER.createObjectNode();
    for (int index = 0; index < values.length; index += 2) {
      String key = (String) values[index];
      JsonNode value = (JsonNode) values[index + 1];
      require(value.isObject(), "M11 check observation is missing: " + key);
      object.set(key, value.deepCopy());
    }
    return object;
  }

  private static JsonNode validateCheck(
      Path schemaRoot, Path checkPath, Path reports, String sourceCommit) {
    JsonNode check = JsonSupport.parse(readBytes(checkPath));
    JsonSupport.validate(check, readString(schemaRoot.resolve(CHECK_SCHEMA)), false);
    require(
        "matching.m11.check.v2".equals(check.path("schemaVersion").stringValue()),
        "M11 check schema changed");
    require("PASS".equals(check.path("status").stringValue()), "M11 check is not strict PASS");
    require("0.14".equals(check.path("contractPlanVersion").stringValue()), "M11 plan changed");
    require(
        sourceCommit.equals(check.path("source").path("commit").stringValue()),
        "M11 check source commit changed");
    require(!check.path("source").path("dirty").booleanValue(), "M11 check reports dirty source");
    require(
        check.path("releaseTarget").path("productRelease").isNull(),
        "M11 check claims a product release");
    JsonNode cluster = check.path("clusterRuntime");
    require(
        cluster.path("singleMemberOnly").booleanValue()
            && !cluster.path("highAvailabilityClaim").booleanValue()
            && !cluster.path("performanceClaim").booleanValue()
            && !cluster.path("externalServices").booleanValue(),
        "M11 check crossed its single-member correctness boundary");
    JsonNode snapshot = check.path("snapshotRestart");
    require(
        snapshot.path("adminRequestAccepted").booleanValue()
            && snapshot.path("completionBounded").booleanValue()
            && snapshot.path("recordingLogNewSnapshotEntry").booleanValue()
            && snapshot.path("closedOnlyAfterCompletion").booleanValue()
            && snapshot.path("loadedSnapshot").booleanValue(),
        "M11 check lacks separate snapshot acceptance, completion, and load evidence");
    require(
        !check.path("mutants").path("systemErrorCountedAsKill").booleanValue(),
        "M11 SYSTEM_ERROR was counted as a mutant kill");

    JsonNode bindings = check.path("artifactBindings");
    require(bindings.size() == REPORT_ARTIFACTS.size(), "M11 check binding count changed");
    for (int index = 0; index < REPORT_ARTIFACTS.size(); index++) {
      String name = REPORT_ARTIFACTS.get(index);
      JsonNode binding = bindings.get(index);
      require(name.equals(binding.path("path").stringValue()), "M11 check binding order changed");
      Path file = reports.resolve(name).normalize();
      require(file.startsWith(reports), "M11 report binding escapes report root");
      require(Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS), "missing M11 report " + name);
      require(size(file) == binding.path("bytes").longValue(), "M11 report size mismatch: " + name);
      require(
          Hashing.sha256Hex(readBytes(file)).equals(binding.path("sha256").stringValue()),
          "M11 report hash mismatch: " + name);
    }
    return check;
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
            Map.entry("planVersion", "0.14"),
            Map.entry("project", "matching"),
            Map.entry("unit", "M11"),
            Map.entry("lifecycle", "COMPLETE"),
            Map.entry("designDepth", "IMPLEMENTED"),
            Map.entry("startRef", START_TAG),
            Map.entry("completeRef", UNIT_TAG),
            Map.entry("m11Check.expectedStatus", "PASS"),
            Map.entry("evidencePath", EVIDENCE_DIRECTORY + "/manifest.json"));
    require(properties.size() == expected.size(), "M11 course declaration field set changed");
    expected.forEach(
        (key, value) ->
            require(value.equals(properties.getProperty(key)), "M11 course changed: " + key));
  }

  private static void verifyReleaseState(
      Path root, String sourceCommit, FrozenInputs frozen, SchemaSnapshot schemas) {
    requireClean(root);
    require(
        sourceCommit.equals(git(root, "rev-parse", "HEAD").strip()),
        "HEAD changed during M11 evidence generation");
    verifyAnnotatedExact(root, UNIT_TAG, sourceCommit);
    verifyAnnotatedAncestor(root, START_TAG, sourceCommit);
    verifyAnnotatedAncestor(root, INHERITED_TAG, sourceCommit);
    verifyAnnotatedAncestor(root, INHERITED_PRODUCT_TAG, sourceCommit);
    String inheritedCommit = peeledAnnotated(root, INHERITED_TAG);
    String productCommit = peeledAnnotated(root, INHERITED_PRODUCT_TAG);
    require(
        inheritedCommit.equals(productCommit),
        "course/m10-complete and matching-0.5.0 do not identify the same baseline");
    String startCommit = peeledAnnotated(root, START_TAG);
    require(
        isAncestor(root, inheritedCommit, startCommit),
        "course/m10-complete is not an ancestor of course/m11-start");
    require(
        git(root, "tag", "--points-at", sourceCommit, "--list", "matching-*").isBlank(),
        "M11 must not carry a matching-* product release tag");
    schemas.verify(root);
    frozen.verify(root);
  }

  private static void verifyAnnotatedExact(Path root, String tag, String sourceCommit) {
    require("tag".equals(git(root, "cat-file", "-t", tag).strip()), tag + " is not annotated");
    require(
        sourceCommit.equals(git(root, "rev-parse", tag + "^{}").strip()),
        tag + " does not peel to HEAD");
  }

  private static void verifyAnnotatedAncestor(Path root, String tag, String sourceCommit) {
    String commit = peeledAnnotated(root, tag);
    require(isAncestor(root, commit, sourceCommit), tag + " is not an ancestor of M11");
  }

  private static String peeledAnnotated(Path root, String tag) {
    require("tag".equals(git(root, "cat-file", "-t", tag).strip()), tag + " is not annotated");
    return git(root, "rev-parse", tag + "^{}").strip();
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
        require(destination.startsWith(staging), "artifact escapes M11 staging root");
        Files.createDirectories(destination.getParent());
        Files.copy(artifact.source(), destination, StandardCopyOption.REPLACE_EXISTING);
      }
    } catch (IOException failure) {
      throw new IllegalStateException("cannot prepare M11 evidence artifacts", failure);
    }
  }

  private static void verifyEvidenceTree(
      Path repositoryRoot,
      Path evidence,
      List<SourceArtifact> artifacts,
      String sourceCommit,
      FrozenInputs frozen,
      SchemaSnapshot schemas,
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
    require(manifest instanceof ObjectNode, "M11 evidence manifest is not an object");
    verifyManifest(evidence, (ObjectNode) manifest, artifacts, sourceCommit, expectedManifest);
    Set<String> expected = new LinkedHashSet<>();
    artifacts.forEach(artifact -> expected.add(portable(artifact.evidencePath())));
    expected.add("manifest.json");
    require(expected.equals(fileInventory(evidence)), "M11 evidence file inventory changed");
    require("PASS".equals(check.path("status").stringValue()), "published M11 check is not PASS");
    verifyBudget(evidence);
    verifyReleaseState(repositoryRoot, sourceCommit, frozen, schemas);
  }

  private static void verifyManifest(
      Path evidence,
      ObjectNode manifest,
      List<SourceArtifact> artifacts,
      String sourceCommit,
      ObjectNode expectedManifest) {
    JsonSupport.validate(
        manifest, readString(evidence.resolve("schemas/cex.lab-evidence.v2.schema.json")), true);
    require("M11".equals(manifest.path("unit").stringValue()), "manifest unit changed");
    require(UNIT_TAG.equals(manifest.path("unitTag").stringValue()), "manifest tag changed");
    require(manifest.path("productRelease").isNull(), "manifest claims a product release");
    require(
        sourceCommit.equals(manifest.path("source").path("commit").stringValue()),
        "manifest source commit changed");
    require(!manifest.path("source").path("dirty").booleanValue(), "manifest reports dirty source");

    List<String> ids = new ArrayList<>();
    List<String> bound = new ArrayList<>();
    for (JsonNode claim : manifest.path("claims")) {
      require("pass".equals(claim.path("status").stringValue()), "non-pass M11 claim");
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
    require(REQUIRED_CLAIMS.equals(ids), "M11 claim set or order changed");
    require(new LinkedHashSet<>(bound).size() == bound.size(), "M11 artifact bound more than once");
    Set<String> expected = new LinkedHashSet<>();
    artifacts.forEach(artifact -> expected.add(portable(artifact.evidencePath())));
    require(expected.equals(new LinkedHashSet<>(bound)), "every M11 artifact must bind once");
    require(
        LIMITATIONS.equals(
            manifest.path("limitations").valueStream().map(JsonNode::stringValue).toList()),
        "M11 limitations changed");
    Instant.parse(requiredText(manifest, "generatedAt"));
    require(
        expectedManifest.equals(manifest),
        "M11 manifest is not the exact projection of the verified check and artifacts");
  }

  private static void verifySourceArtifacts(List<SourceArtifact> artifacts) {
    for (SourceArtifact artifact : artifacts) {
      require(
          Files.isRegularFile(artifact.source(), LinkOption.NOFOLLOW_LINKS),
          "M11 source artifact is missing: " + artifact.source());
      require(size(artifact.source()) == artifact.bytes(), "M11 source artifact size changed");
      require(
          Hashing.sha256Hex(readBytes(artifact.source())).equals(artifact.sha256()),
          "M11 source artifact hash changed");
    }
  }

  private static void verifyCopiedArtifacts(Path root, List<SourceArtifact> artifacts) {
    for (SourceArtifact artifact : artifacts) {
      Path file = root.resolve(artifact.evidencePath()).normalize();
      require(file.startsWith(root), "copied artifact escapes M11 evidence root");
      require(
          Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS),
          "copied M11 artifact is missing: " + artifact.evidencePath());
      require(size(file) == artifact.bytes(), "copied M11 artifact size changed");
      require(
          Hashing.sha256Hex(readBytes(file)).equals(artifact.sha256()),
          "copied M11 artifact hash changed");
    }
  }

  private static Path createStaging(Path root, Path destination) {
    Path parent = destination.getParent();
    require(parent != null, "M11 evidence directory has no parent");
    SafeOutputPaths.requireNoSymlinkComponents(root, parent);
    try {
      Files.createDirectories(parent);
      SafeOutputPaths.requireNoSymlinkComponents(root, parent);
      return Files.createTempDirectory(parent, ".M11-staging-");
    } catch (IOException failure) {
      throw new IllegalStateException("cannot create M11 staging directory", failure);
    }
  }

  private static void publishAtomically(Path root, Path staging, Path destination) {
    requireSafeTree(root, staging);
    SafeOutputPaths.requireNoSymlinkComponents(root, destination);
    deleteTree(destination);
    try {
      Files.move(staging, destination, StandardCopyOption.ATOMIC_MOVE);
    } catch (AtomicMoveNotSupportedException failure) {
      throw new IllegalStateException("atomic M11 evidence publication is unavailable", failure);
    } catch (IOException failure) {
      throw new IllegalStateException("cannot publish M11 evidence", failure);
    }
  }

  private static void verifyBudget(Path tree) {
    requireSafeTree(tree, tree);
    try (var paths = Files.walk(tree)) {
      List<Path> files =
          paths.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)).toList();
      require(files.size() <= MAX_FILES, "M11 evidence exceeds 64 files");
      long total = 0;
      for (Path file : files) {
        long bytes = Files.size(file);
        require(bytes > 0 && bytes <= MAX_FILE_BYTES, "M11 evidence file exceeds 2 MiB: " + file);
        total = Math.addExact(total, bytes);
      }
      require(total <= MAX_TREE_BYTES, "M11 evidence exceeds 10 MiB");
    } catch (IOException failure) {
      throw new IllegalStateException("cannot measure M11 evidence", failure);
    }
  }

  private static void requireSafeTree(Path root, Path tree) {
    SafeOutputPaths.requireNoSymlinkComponents(root, tree);
    require(Files.isDirectory(tree, LinkOption.NOFOLLOW_LINKS), "missing M11 artifact tree");
    try (var paths = Files.walk(tree)) {
      paths.forEach(path -> require(!Files.isSymbolicLink(path), "symlink in M11 evidence tree"));
    } catch (IOException failure) {
      throw new IllegalStateException("cannot inspect M11 evidence tree", failure);
    }
  }

  private static Set<String> fileInventory(Path root) {
    try (var paths = Files.walk(root)) {
      return new LinkedHashSet<>(
          paths
              .filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
              .map(root::relativize)
              .map(M11EvidenceWriter::portable)
              .sorted()
              .toList());
    } catch (IOException failure) {
      throw new IllegalStateException("cannot inventory M11 evidence", failure);
    }
  }

  private static String requiredText(JsonNode object, String field) {
    String value = object.path(field).stringValue();
    require(value != null && !value.isBlank(), "M11 environment lacks " + field);
    return value;
  }

  private static void copyText(JsonNode source, ObjectNode destination, String field) {
    destination.put(field, requiredText(source, field));
  }

  private static Path safeRelative(String value) {
    Path path = Path.of(value);
    require(!path.isAbsolute(), "absolute M11 artifact path is forbidden");
    Path normalized = path.normalize();
    require(!normalized.startsWith(".."), "parent traversal in M11 artifact path");
    require(portable(normalized).equals(value), "non-canonical M11 artifact path");
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
        "repository must be clean before M11 evidence generation");
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
    M11CheckRunner.Result run(Path root, Path reports);
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

  private record Golden(Path path, String sha256, long bytes) {}

  private record FrozenInputs(Map<Path, String> hashes, List<Golden> goldens) {
    static FrozenInputs capture(Path root) {
      byte[] workloadBytes = readBytes(root.resolve(M11StartCheckRunner.WORKLOAD_PATH));
      require(
          M11StartCheckRunner.WORKLOAD_SHA256.equals(Hashing.sha256Hex(workloadBytes)),
          "M11 workload hash changed");
      JsonNode workload = JsonSupport.parse(workloadBytes);
      JsonSupport.validate(
          workload, readString(root.resolve(M11StartCheckRunner.WORKLOAD_SCHEMA_PATH)), false);
      List<Golden> goldens = new ArrayList<>();
      for (JsonNode binding : workload.path("goldenFixtures")) {
        Path path = safeRelative(binding.path("path").stringValue());
        require(
            portable(path).startsWith("matching-testkit/src/test/resources/m11/goldens/"),
            "M11 golden path escaped its frozen directory");
        byte[] bytes = readBytes(root.resolve(path));
        require(bytes.length == binding.path("bytes").longValue(), "M11 golden length changed");
        String digest = Hashing.sha256Hex(bytes);
        require(digest.equals(binding.path("sha256").stringValue()), "M11 golden hash changed");
        goldens.add(new Golden(path, digest, bytes.length));
      }
      require(goldens.size() == 6, "M11 golden fixture count changed");
      Map<Path, String> hashes = new LinkedHashMap<>();
      hashes.put(Path.of(M11StartCheckRunner.WORKLOAD_PATH), Hashing.sha256Hex(workloadBytes));
      hashes.put(
          Path.of(M11StartCheckRunner.WORKLOAD_SCHEMA_PATH),
          Hashing.sha256Hex(readBytes(root.resolve(M11StartCheckRunner.WORKLOAD_SCHEMA_PATH))));
      goldens.forEach(golden -> hashes.put(golden.path(), golden.sha256()));
      return new FrozenInputs(Map.copyOf(hashes), List.copyOf(goldens));
    }

    void verify(Path root) {
      FrozenInputs current = capture(root);
      require(hashes.equals(current.hashes()), "M11 frozen input changed during evidence run");
      for (Path path : hashes.keySet()) {
        byte[] currentBytes = readBytes(root.resolve(path));
        byte[] tagged = gitBytes(root, "show", START_TAG + ":" + portable(path));
        require(Arrays.equals(currentBytes, tagged), START_TAG + " differs at " + path);
      }
    }
  }

  private record SourceArtifact(Path source, Path evidencePath, String sha256, long bytes) {
    static SourceArtifact capture(Path root, Path source, Path evidencePath) {
      Path normalized = source.toAbsolutePath().normalize();
      require(normalized.startsWith(root), "M11 source artifact escapes repository");
      require(
          Files.isRegularFile(normalized, LinkOption.NOFOLLOW_LINKS),
          "missing M11 source artifact: " + normalized);
      Path relative = safeRelative(portable(evidencePath.normalize()));
      long bytes = size(normalized);
      require(bytes > 0 && bytes <= MAX_FILE_BYTES, "M11 source artifact exceeds 2 MiB");
      return new SourceArtifact(
          normalized, relative, Hashing.sha256Hex(readBytes(normalized)), bytes);
    }
  }

  private record SchemaSnapshot(Map<Path, String> hashes) {
    static SchemaSnapshot capture(Path root) {
      Map<Path, String> hashes = new LinkedHashMap<>();
      for (String value :
          List.of(
              M11StartCheckRunner.WORKLOAD_SCHEMA_PATH,
              CHECK_SCHEMA,
              COUNTEREXAMPLE_SCHEMA,
              EVIDENCE_SCHEMA)) {
        Path path = Path.of(value);
        hashes.put(path, Hashing.sha256Hex(readBytes(root.resolve(path))));
      }
      return new SchemaSnapshot(Map.copyOf(hashes));
    }

    void verify(Path root) {
      hashes.forEach(
          (path, digest) ->
              require(
                  digest.equals(Hashing.sha256Hex(readBytes(root.resolve(path)))),
                  "M11 evidence schema changed: " + path));
    }
  }

  public record Result(
      Path manifestPath, String sourceCommit, String manifestSha256, int artifactCount) {}
}
