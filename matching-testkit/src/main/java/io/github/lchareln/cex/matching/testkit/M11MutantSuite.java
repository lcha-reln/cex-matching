package io.github.lchareln.cex.matching.testkit;

import io.github.lchareln.cex.matching.cluster.DirectM11MatchingRuntime;
import io.github.lchareln.cex.matching.cluster.M11ApplicationObserver;
import io.github.lchareln.cex.matching.cluster.M11ApplicationResult;
import io.github.lchareln.cex.matching.cluster.M11ClientCompletionBoundary;
import io.github.lchareln.cex.matching.cluster.M11ClusterStartupControl;
import io.github.lchareln.cex.matching.cluster.M11CommandRequest;
import io.github.lchareln.cex.matching.cluster.M11FaultPolicy;
import io.github.lchareln.cex.matching.cluster.M11ProtocolException;
import io.github.lchareln.cex.matching.cluster.M11RequestCodec;
import io.github.lchareln.cex.matching.cluster.M11ResponseCodec;
import io.github.lchareln.cex.matching.cluster.M11ResponseStatus;
import io.github.lchareln.cex.matching.cluster.M11RuntimeState;
import io.github.lchareln.cex.matching.cluster.M11SingleNodeCluster;
import io.github.lchareln.cex.matching.cluster.M11SingleNodeConfig;
import io.github.lchareln.cex.matching.cluster.M11SnapshotCodec;
import io.github.lchareln.cex.matching.local.M08Command;
import io.github.lchareln.cex.matching.local.Slot;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Fresh production and single-fault candidates judged by one typed trace observer. */
final class M11MutantSuite {
  private static final String OBSERVER = "M11_UNIFIED_TYPED_TRACE_OBSERVER_V2";
  private static final String INTERPRETER = "M11_TYPED_STEP_INTERPRETER_V2";

  Result run(Path repositoryRoot) {
    Path root = repositoryRoot.toAbsolutePath().normalize();
    Path suiteRoot = createTemporaryDirectory();
    try {
      Map<String, Definition> definitions = definitions();
      ArrayNode candidates = JsonSupport.MAPPER.createArrayNode();
      ArrayNode witnesses = JsonSupport.MAPPER.createArrayNode();
      int killed = 0;
      int rawActions = 0;
      int minimalActions = 0;
      int shrinkTrials = 0;
      int actualMutationActions = 0;

      for (String id : M11StartCheckRunner.MUTANT_IDS) {
        Definition definition = definitions.get(id);
        systemRequire(definition != null, "missing M11 mutant definition " + id);
        List<Step> raw = new ArrayList<>();
        raw.add(Step.parse("NOOP:prefix"));
        raw.addAll(definition.history());
        raw.add(Step.parse("NOOP:suffix"));

        Outcome production = execute(root, suiteRoot, definition, raw, M11FaultPolicy.Mode.NONE);
        systemRequire(
            production.classification() == Classification.PASS,
            "production candidate did not PASS " + id + ": " + production.detail());

        Outcome mutant = execute(root, suiteRoot, definition, raw, definition.mutation());
        systemRequire(
            mutant.classification() != Classification.SYSTEM_ERROR,
            "mutant raised SYSTEM_ERROR " + id + ": " + mutant.detail());
        semanticRequire(
            mutant.classification() == Classification.STUDENT_FAILURE,
            "required executable mutant survived " + id);
        systemRequire(
            definition.fingerprint().equals(mutant.fingerprint()),
            "raw mutant fingerprint changed " + id);
        systemRequire(mutant.mutationActions() > 0, "mutant performed no fault action " + id);

        Shrink shrink = minimize(root, suiteRoot, definition, raw);
        systemRequire(shrink.steps().size() < raw.size(), "mutant witness did not shrink " + id);
        Outcome minimized =
            execute(root, suiteRoot, definition, shrink.steps(), definition.mutation());
        systemRequire(
            minimized.classification() == Classification.STUDENT_FAILURE
                && definition.fingerprint().equals(minimized.fingerprint())
                && minimized.mutationActions() > 0,
            "minimized mutant witness changed " + id);
        systemRequire(
            oneMinimal(root, suiteRoot, definition, shrink.steps()),
            "mutant witness is not one-minimal " + id);

        String witnessId = "cex-m11-" + id.substring(4).toLowerCase();
        ObjectNode candidate = candidates.addObject();
        candidate.put("id", id);
        candidate.put("productionClassification", Classification.PASS.name());
        candidate.put("classification", Classification.STUDENT_FAILURE.name());
        candidate.put("fingerprint", minimized.fingerprint());
        candidate.put("candidateDriver", "FRESH_PRODUCTION_DERIVED_SINGLE_FAULT_MACHINE");
        candidate.put("observer", OBSERVER);
        candidate.put("freshProductionControl", true);
        candidate.put("singleFaultMode", definition.mutation().name());
        candidate.put("actualMutationActions", minimized.mutationActions());
        candidate.put("systemErrorCountedAsKill", false);
        candidate.put("counterexampleId", witnessId);

        ObjectNode witness = witnesses.addObject();
        witness.put("id", witnessId);
        witness.put("mutant", id);
        witness.put("classification", Classification.STUDENT_FAILURE.name());
        witness.put("fingerprint", minimized.fingerprint());
        witness.put("rawActions", raw.size());
        witness.put("minimalActions", shrink.steps().size());
        witness.put("shrinkTrials", shrink.trials());
        witness.put("actualMutationActions", minimized.mutationActions());
        witness.put("oneMinimal", true);
        witness.put("stepCountMatchesMinimalActions", true);
        witness.put("strictFreshReplay", true);
        witness.put("serializedReplayFingerprintExact", true);
        witness.put("minimalityScope", "ONE_MINIMAL_WITHIN_TYPED_STEP_DELETION_GRAMMAR");
        witness.put("globalMinimumClaim", false);
        ArrayNode steps = witness.putArray("steps");
        shrink.steps().forEach(step -> step.write(steps.addObject()));

        killed++;
        rawActions += raw.size();
        minimalActions += shrink.steps().size();
        shrinkTrials += shrink.trials();
        actualMutationActions += minimized.mutationActions();
      }

      ArrayNode controls = executeControls(root);
      systemRequire(killed == 10, "not every M11 mutant was killed");
      systemRequire(controls.size() == 3, "M11 system control count changed");

      ObjectNode counterexamples = JsonSupport.MAPPER.createObjectNode();
      counterexamples.put("schemaVersion", "matching.m11.counterexamples.v1");
      counterexamples.put("seed", "6111");
      counterexamples.put("required", 10);
      counterexamples.put("persisted", killed);
      counterexamples.put("replayInterpreter", INTERPRETER);
      counterexamples.put("minimalityScope", "ONE_MINIMAL_WITHIN_TYPED_STEP_DELETION_GRAMMAR");
      counterexamples.put("globalMinimumClaim", false);
      counterexamples.put("invalidHistoryCountedAsKill", false);
      counterexamples.put("systemErrorCountedAsKill", false);
      counterexamples.put("serializedFreshReplay", true);
      counterexamples.put("serializedReplayCount", 10);
      counterexamples.put("serializedReplayFingerprintsExact", true);
      counterexamples.put("oneDeleteAudits", 10);
      counterexamples.set("witnesses", witnesses);

      byte[] persistedBytes = JsonSupport.prettyBytes(counterexamples);
      Path persistedPath = suiteRoot.resolve("counterexamples.json");
      AtomicFiles.write(persistedPath, persistedBytes);
      byte[] readBack = readBytes(persistedPath);
      systemRequire(
          Arrays.equals(persistedBytes, readBack), "counterexample bytes changed on disk");
      JsonNode parsed = JsonSupport.parse(readBack);
      JsonSupport.validate(
          parsed, readString(root.resolve(M11CheckRunner.COUNTEREXAMPLE_SCHEMA_PATH)), false);
      ReplayAudit replay = strictReplay(root, suiteRoot, parsed, definitions, readBack);
      JsonSupport.validate(
          replay.report(), readString(root.resolve(M11CheckRunner.REPLAY_SCHEMA_PATH)), false);

      return new Result(
          (ObjectNode) parsed.deepCopy(),
          persistedBytes,
          candidates,
          controls,
          replay.report(),
          replay.canonicalBytes(),
          replay.digest(),
          killed,
          rawActions,
          minimalActions,
          shrinkTrials,
          actualMutationActions);
    } finally {
      M09ScenarioSupport.deleteTree(suiteRoot);
    }
  }

  private static Map<String, Definition> definitions() {
    Map<String, Definition> values = new LinkedHashMap<>();
    add(
        values,
        "M11-OFFER-AS-SUCCESS",
        M11FaultPolicy.Mode.OFFER_AS_SUCCESS,
        "INGRESS_OFFER_UPGRADED_TO_BUSINESS_SUCCESS",
        "OFFER:request-1");
    add(
        values,
        "M11-SESSION-AS-IDENTITY",
        M11FaultPolicy.Mode.SESSION_AS_IDENTITY,
        "SESSION_CHANGED_BUSINESS_IDENTITY",
        "SUBMIT_SESSION:session-a",
        "SUBMIT_SESSION:session-b");
    add(
        values,
        "M11-CORRELATION-AS-IDENTITY",
        M11FaultPolicy.Mode.CORRELATION_AS_IDENTITY,
        "CORRELATION_CHANGED_BUSINESS_IDENTITY",
        "SUBMIT_CORRELATION:correlation-a",
        "SUBMIT_CORRELATION:correlation-b");
    add(
        values,
        "M11-RESPOND-BEFORE-BIND",
        M11FaultPolicy.Mode.RESPOND_BEFORE_BIND,
        "RESPONSE_OBSERVED_BEFORE_RESULT_BIND",
        "BEGIN_SUBMIT:request-1",
        "CRASH:node-1",
        "RETRY:request-1");
    add(
        values,
        "M11-DROP-IDENTITY-FROM-SNAPSHOT",
        M11FaultPolicy.Mode.DROP_IDENTITY_FROM_SNAPSHOT,
        "SNAPSHOT_LOST_IDEMPOTENCY_TABLE",
        "SUBMIT:request-1",
        "TAKE_SNAPSHOT:snapshot-1",
        "RESTART:node-1",
        "RETRY:request-1");
    add(
        values,
        "M11-CORRUPT-SNAPSHOT-TO-GENESIS",
        M11FaultPolicy.Mode.CORRUPT_SNAPSHOT_TO_GENESIS,
        "CORRUPT_SNAPSHOT_SILENTLY_BECAME_GENESIS",
        "SUBMIT:request-1",
        "TAKE_SNAPSHOT:snapshot-1",
        "CORRUPT_SNAPSHOT:middle-byte",
        "RESTART:node-1",
        "RETRY:request-1");
    add(
        values,
        "M11-REJECT-N-MINUS-ONE",
        M11FaultPolicy.Mode.REJECT_N_MINUS_ONE,
        "N_MINUS_ONE_COMPATIBILITY_REJECTED",
        "DECODE_REQUEST_V1:request-v1",
        "DECODE_RESPONSE_V1:response-v1",
        "DECODE_SNAPSHOT_V1:snapshot-v1");
    add(
        values,
        "M11-INCLUDE-RUNTIME-METADATA-IN-DIGEST",
        M11FaultPolicy.Mode.INCLUDE_RUNTIME_METADATA_IN_DIGEST,
        "RUNTIME_METADATA_CHANGED_BUSINESS_DIGEST",
        "READ_DIGEST:before",
        "SET_SESSION:session-b",
        "READ_DIGEST:after");
    add(
        values,
        "M11-DOUBLE-WRITE-LOCAL-WAL",
        M11FaultPolicy.Mode.DOUBLE_WRITE_LOCAL_WAL,
        "CLUSTER_SERVICE_WROTE_STANDALONE_WAL",
        "SUBMIT:request-1");
    add(
        values,
        "M11-ACCEPT-UNSUPPORTED-VERSION",
        M11FaultPolicy.Mode.ACCEPT_UNSUPPORTED_VERSION,
        "UNSUPPORTED_VERSION_ACCEPTED",
        "DECODE_REQUEST_V3:request-v3");
    return Map.copyOf(values);
  }

  private static void add(
      Map<String, Definition> values,
      String id,
      M11FaultPolicy.Mode mutation,
      String fingerprint,
      String... encoded) {
    values.put(
        id,
        new Definition(
            id, mutation, fingerprint, Arrays.stream(encoded).map(Step::parse).toList()));
  }

  private static Outcome execute(
      Path repositoryRoot,
      Path suiteRoot,
      Definition definition,
      List<Step> steps,
      M11FaultPolicy.Mode mutation) {
    Path scratch = createCandidateDirectory(suiteRoot);
    Classification classification = Classification.PASS;
    String fingerprint = "";
    String detail = "";
    int mutationActions = 0;
    try {
      CandidateMachine candidate = new CandidateMachine(repositoryRoot, scratch, mutation);
      for (Step step : steps) {
        candidate.execute(step);
      }
      mutationActions = candidate.mutationActions();
      Optional<String> violation = UnifiedObserver.observe(candidate.trace());
      if (violation.isPresent()) {
        classification = Classification.STUDENT_FAILURE;
        fingerprint = violation.orElseThrow();
      }
    } catch (InvalidHistory failure) {
      classification = Classification.INVALID_HISTORY;
      detail = stableDetail(failure);
    } catch (RuntimeException | Error failure) {
      classification = Classification.valueOf(M11FailureClassifier.classify(failure));
      if (classification == Classification.STUDENT_FAILURE) {
        fingerprint = failure.getMessage();
      } else {
        detail = stableDetail(failure);
      }
    } finally {
      try {
        M09ScenarioSupport.deleteTree(scratch);
      } catch (RuntimeException failure) {
        classification = Classification.SYSTEM_ERROR;
        fingerprint = "";
        detail = "cleanup:" + stableDetail(failure);
      }
    }
    if (classification == Classification.STUDENT_FAILURE
        && mutation != M11FaultPolicy.Mode.NONE
        && mutationActions == 0) {
      return new Outcome(
          Classification.SYSTEM_ERROR,
          "",
          "observer reported a mutation without an executed fault action",
          0);
    }
    return new Outcome(classification, fingerprint, detail, mutationActions);
  }

  private static Shrink minimize(Path root, Path suiteRoot, Definition definition, List<Step> raw) {
    List<Step> current = new ArrayList<>(raw);
    int trials = 0;
    boolean changed;
    do {
      changed = false;
      for (int index = 0; index < current.size(); index++) {
        List<Step> candidate = new ArrayList<>(current);
        candidate.remove(index);
        trials++;
        Outcome outcome = execute(root, suiteRoot, definition, candidate, definition.mutation());
        systemRequire(
            outcome.classification() != Classification.SYSTEM_ERROR,
            "shrinker SYSTEM_ERROR " + definition.id() + ": " + outcome.detail());
        if (outcome.classification() == Classification.STUDENT_FAILURE
            && definition.fingerprint().equals(outcome.fingerprint())) {
          current = candidate;
          changed = true;
          break;
        }
      }
    } while (changed);
    return new Shrink(List.copyOf(current), trials);
  }

  private static boolean oneMinimal(
      Path root, Path suiteRoot, Definition definition, List<Step> steps) {
    for (int index = 0; index < steps.size(); index++) {
      List<Step> candidate = new ArrayList<>(steps);
      candidate.remove(index);
      Outcome outcome = execute(root, suiteRoot, definition, candidate, definition.mutation());
      systemRequire(
          outcome.classification() != Classification.SYSTEM_ERROR,
          "one-delete SYSTEM_ERROR " + definition.id() + ": " + outcome.detail());
      if (outcome.classification() == Classification.STUDENT_FAILURE
          && definition.fingerprint().equals(outcome.fingerprint())) {
        return false;
      }
    }
    return true;
  }

  private static ReplayAudit strictReplay(
      Path root,
      Path suiteRoot,
      JsonNode parsed,
      Map<String, Definition> definitions,
      byte[] persistedBytes) {
    JsonNode witnesses = parsed.path("witnesses");
    systemRequire(witnesses.size() == 10, "persisted M11 counterexample count changed");
    Set<String> ids = new HashSet<>();
    Set<String> mutants = new HashSet<>();
    ArrayNode results = JsonSupport.MAPPER.createArrayNode();
    StringBuilder canonical = new StringBuilder("M11R2\n");
    int replayed = 0;
    int oneDeleteAudits = 0;

    for (int index = 0; index < witnesses.size(); index++) {
      JsonNode witness = witnesses.get(index);
      String expectedMutant = M11StartCheckRunner.MUTANT_IDS.get(index);
      String mutant = witness.path("mutant").stringValue();
      String id = witness.path("id").stringValue();
      systemRequire(expectedMutant.equals(mutant), "persisted mutant order changed at " + index);
      systemRequire(ids.add(id), "duplicate persisted counterexample ID " + id);
      systemRequire(mutants.add(mutant), "duplicate persisted mutant ID " + mutant);
      Definition definition = definitions.get(mutant);
      systemRequire(definition != null, "persisted mutant is unknown " + mutant);

      List<Step> steps = new ArrayList<>();
      for (JsonNode encoded : witness.path("steps")) {
        Step step = Step.parsePersisted(encoded);
        steps.add(step);
      }
      systemRequire(
          steps.size() == witness.path("minimalActions").intValue(),
          "persisted minimal action count changed " + mutant);

      Outcome production = execute(root, suiteRoot, definition, steps, M11FaultPolicy.Mode.NONE);
      systemRequire(
          production.classification() == Classification.PASS,
          "serialized production replay did not PASS " + mutant);
      Outcome replay = execute(root, suiteRoot, definition, steps, definition.mutation());
      String expectedFingerprint = witness.path("fingerprint").stringValue();
      int expectedMutationActions = witness.path("actualMutationActions").intValue();
      systemRequire(
          replay.classification() == Classification.STUDENT_FAILURE
              && expectedFingerprint.equals(replay.fingerprint())
              && expectedMutationActions == replay.mutationActions(),
          "serialized fresh replay changed " + mutant);
      systemRequire(
          oneMinimal(root, suiteRoot, definition, steps),
          "serialized witness lost one-minimality " + mutant);

      ObjectNode result = results.addObject();
      result.put("counterexampleId", id);
      result.put("mutant", mutant);
      result.put("classification", Classification.STUDENT_FAILURE.name());
      result.put("fingerprint", replay.fingerprint());
      result.put("steps", steps.size());
      result.put("actualMutationActions", replay.mutationActions());
      result.put("freshCandidate", true);
      result.put("freshProductionControl", true);
      result.put("fingerprintExact", true);
      result.put("oneMinimal", true);
      canonical
          .append(mutant)
          .append('|')
          .append(replay.fingerprint())
          .append('|')
          .append(replay.mutationActions())
          .append('|')
          .append(
              steps.stream().map(Step::encoded).collect(java.util.stream.Collectors.joining(",")))
          .append('\n');
      replayed++;
      oneDeleteAudits++;
    }
    systemRequire(ids.size() == 10 && mutants.size() == 10, "persisted IDs are not unique");

    byte[] canonicalBytes = canonical.toString().getBytes(StandardCharsets.UTF_8);
    ObjectNode report = JsonSupport.MAPPER.createObjectNode();
    report.put("schemaVersion", "matching.m11.replay.v1");
    report.put("status", M11CheckRunner.PASS);
    report.put("replayInterpreter", INTERPRETER);
    report.put("persistedBytesSha256", Hashing.sha256Hex(persistedBytes));
    report.put("persistedBytesParsed", true);
    report.put("required", 10);
    report.put("replayed", replayed);
    report.put("orderedUniqueIds", true);
    report.put("stepCountsExact", true);
    report.put("freshCandidatePerReplay", true);
    report.put("productionControlsPassed", replayed);
    report.put("fingerprintsExact", true);
    report.put("oneDeleteAudits", oneDeleteAudits);
    report.put("invalidHistoryCountedAsKill", false);
    report.put("systemErrorCountedAsKill", false);
    report.put("canonicalSha256", Hashing.sha256Hex(canonicalBytes));
    report.set("results", results);
    return new ReplayAudit(report, canonicalBytes, Hashing.sha256Hex(canonicalBytes));
  }

  private static ArrayNode executeControls(Path root) {
    ArrayNode controls = JsonSupport.MAPPER.createArrayNode();
    for (String id : M11StartCheckRunner.SYSTEM_ERROR_IDS) {
      ControlOutcome outcome = executeControl(root, id);
      systemRequire(
          outcome.classification() == Classification.SYSTEM_ERROR,
          "control did not classify as SYSTEM_ERROR " + id);
      ObjectNode node = controls.addObject();
      node.put("id", id);
      node.put("classification", Classification.SYSTEM_ERROR.name());
      node.put("classifier", "M11_SHARED_FAILURE_CLASSIFIER_V1");
      node.put("executedPath", outcome.path());
      node.put("failureType", outcome.failureType());
      node.put("countedAsKill", false);
    }
    return controls;
  }

  private static ControlOutcome executeControl(Path root, String id) {
    try {
      String path =
          switch (id) {
            case "M11-THROWING-CODEC-CONTROL" -> runCodecControl();
            case "M11-CLUSTER-STARTUP-CONTROL" -> runClusterStartupControl(root);
            case "M11-CORRUPT-HARNESS-OUTPUT-CONTROL" -> runHarnessOutputControl();
            default -> throw new IllegalArgumentException("unknown M11 system control " + id);
          };
      return new ControlOutcome(Classification.PASS, path, "NONE");
    } catch (RuntimeException | Error failure) {
      Classification classification =
          Classification.valueOf(M11FailureClassifier.classify(failure));
      return new ControlOutcome(
          classification, controlPath(id), failure.getClass().getSimpleName());
    }
  }

  private static String runCodecControl() {
    byte[] encoded = new M11RequestCodec().encode(request());
    M11RequestCodec codec =
        new M11RequestCodec(M11FaultPolicy.single(M11FaultPolicy.Mode.REQUEST_CODEC_SYSTEM_ERROR));
    try {
      codec.decodeCanonical(encoded, 1);
    } catch (M11ProtocolException failure) {
      throw new IllegalStateException(
          "codec control unexpectedly became a protocol rejection", failure);
    }
    return "REQUEST_CODEC_COMPONENT";
  }

  private static String runClusterStartupControl(Path root) {
    M11SingleNodeConfig config =
        M11SingleNodeConfig.defaults(root.resolve("build/tmp/m11-control-cluster"), 1, 41_111);
    M11SingleNodeCluster cluster =
        M11ClusterStartupControl.launch(
            config,
            M11ApplicationObserver.NO_OP,
            M11FaultPolicy.single(M11FaultPolicy.Mode.CLUSTER_STARTUP_SYSTEM_ERROR));
    cluster.close();
    return "SINGLE_NODE_CLUSTER_LAUNCHER";
  }

  private static String runHarnessOutputControl() {
    ObjectNode valid = JsonSupport.MAPPER.createObjectNode();
    valid.put("schemaVersion", "matching.m11.cluster-runtime.v1");
    byte[] encoded = JsonSupport.prettyBytes(valid);
    byte[] truncated = Arrays.copyOf(encoded, Math.max(1, encoded.length / 2));
    JsonSupport.parse(truncated);
    return "HARNESS_REPORT_PARSER";
  }

  private static String controlPath(String id) {
    return switch (id) {
      case "M11-THROWING-CODEC-CONTROL" -> "REQUEST_CODEC_COMPONENT";
      case "M11-CLUSTER-STARTUP-CONTROL" -> "SINGLE_NODE_CLUSTER_LAUNCHER";
      case "M11-CORRUPT-HARNESS-OUTPUT-CONTROL" -> "HARNESS_REPORT_PARSER";
      default -> "UNKNOWN";
    };
  }

  private static final class CandidateMachine {
    private final Path repositoryRoot;
    private final Path scratch;
    private final M11FaultPolicy faultPolicy;
    private final M11RequestCodec requestCodec;
    private final M11ResponseCodec responseCodec;
    private final M11SnapshotCodec snapshotCodec;
    private final M11ClientCompletionBoundary completionBoundary;
    private final Trace trace = new Trace();
    private final M11CommandRequest original = request();
    private DirectM11MatchingRuntime runtime;
    private byte[] durableSnapshot;
    private byte[] snapshotBytes;
    private boolean snapshotCorrupted;
    private boolean restoreBlocked;
    private String session = "session-a";

    CandidateMachine(Path repositoryRoot, Path scratch, M11FaultPolicy.Mode mutation) {
      this.repositoryRoot = repositoryRoot;
      this.scratch = scratch;
      faultPolicy =
          mutation == M11FaultPolicy.Mode.NONE
              ? M11FaultPolicy.none()
              : M11FaultPolicy.single(mutation);
      requestCodec = new M11RequestCodec(faultPolicy);
      responseCodec = new M11ResponseCodec(faultPolicy);
      snapshotCodec = new M11SnapshotCodec(faultPolicy);
      completionBoundary = new M11ClientCompletionBoundary(faultPolicy);
      runtime = new DirectM11MatchingRuntime(faultPolicy);
      durableSnapshot = snapshotCodec.encodeCurrent(runtime.stateImage());
    }

    void execute(Step step) {
      switch (step.action()) {
        case NOOP -> {}
        case OFFER -> offer();
        case SUBMIT_SESSION -> submitTransportIdentity(step.value(), fixedCorrelation());
        case SUBMIT_CORRELATION ->
            submitTransportIdentity("session-fixed", namedUuid(step.value()));
        case BEGIN_SUBMIT -> beginSubmit();
        case CRASH -> crash();
        case RETRY -> retry();
        case SUBMIT -> submit();
        case TAKE_SNAPSHOT -> takeSnapshot();
        case CORRUPT_SNAPSHOT -> corruptSnapshot();
        case RESTART -> restart();
        case DECODE_REQUEST_V1 -> decodePrevious(Artifact.REQUEST);
        case DECODE_RESPONSE_V1 -> decodePrevious(Artifact.RESPONSE);
        case DECODE_SNAPSHOT_V1 -> decodePrevious(Artifact.SNAPSHOT);
        case READ_DIGEST -> readDigest(step.value());
        case SET_SESSION -> session = step.value();
        case DECODE_REQUEST_V3 -> decodeUnsupported();
      }
    }

    Trace trace() {
      return trace;
    }

    int mutationActions() {
      return Math.toIntExact(faultPolicy.activationCount());
    }

    private void offer() {
      byte[] encoded = requestCodec.encode(original);
      trace.add(new IngressEvent(original.correlationId(), encoded.length));
      M11ClientCompletionBoundary.Decision completion =
          completionBoundary.onIngressOfferAccepted(original, encoded.length);
      if (completion.businessComplete()) {
        trace.add(
            new CompletionEvent(
                completion.correlationId(),
                CompletionSource.valueOf(completion.source().name()),
                status(completion.responseStatus().orElseThrow())));
      }
    }

    private void submitTransportIdentity(String transportSession, UUID correlation) {
      M11CommandRequest external = original.withCorrelationId(correlation);
      M11ApplicationResult result = runtime.submit(external, transportSession);
      trace.add(
          new IdentitySubmissionEvent(
              IdentityKey.of(external),
              transportSession,
              correlation,
              status(result),
              applicationSequence(result),
              resultDigest(result)));
    }

    private void beginSubmit() {
      M11ApplicationResult applied = runtime.submit(original, session);
      trace.add(
          new ApplyEvent(
              original.correlationId(),
              status(applied),
              applicationSequence(applied),
              resultDigest(applied)));
      if (runtime.hasIdentityBinding(original.commandId())) {
        durableSnapshot = snapshotCodec.encodeCurrent(runtime.stateImage());
        trace.add(new BindingEvent(original.commandId(), applicationSequence(applied)));
      }
      M11ClientCompletionBoundary.Decision completion =
          completionBoundary.onCorrelatedEgress(applied.response());
      if (completion.businessComplete()) {
        trace.add(
            new CompletionEvent(
                completion.correlationId(),
                CompletionSource.valueOf(completion.source().name()),
                status(completion.responseStatus().orElseThrow())));
      }
    }

    private void crash() {
      try {
        runtime =
            runtime.recoverAfterCrash(snapshotCodec.decodeForRecovery(durableSnapshot).state());
        restoreBlocked = false;
        trace.add(new CrashEvent(runtime.nextApplicationSequence()));
      } catch (M11ProtocolException failure) {
        throw new IllegalStateException("durable production state did not restore", failure);
      }
    }

    private void retry() {
      M11CommandRequest retry = original.withCorrelationId(retryCorrelation());
      if (restoreBlocked) {
        trace.add(new RetryEvent(retry.correlationId(), OutcomeStatus.BLOCKED, 0, ""));
        return;
      }
      M11ApplicationResult result = runtime.submit(retry, session);
      trace.add(
          new RetryEvent(
              retry.correlationId(),
              status(result),
              applicationSequence(result),
              resultDigest(result)));
    }

    private void submit() {
      M11ApplicationResult result = runtime.submit(original, session);
      trace.add(
          new ApplyEvent(
              original.correlationId(),
              status(result),
              applicationSequence(result),
              resultDigest(result)));
      long writeSignals = faultPolicy.drainStandaloneWriteSignals();
      for (long index = 0; index < writeSignals; index++) {
        writeStandaloneWal(original.envelopeBytes());
      }
      Path wal = scratch.resolve("standalone.m11wal");
      if (Files.isRegularFile(wal)) {
        try {
          trace.add(new WalEvent(wal.getFileName().toString(), Files.size(wal), true));
        } catch (IOException failure) {
          throw new IllegalStateException("cannot inspect standalone WAL fault output", failure);
        }
      }
    }

    private void takeSnapshot() {
      M11RuntimeState state = runtime.stateImage();
      snapshotBytes = snapshotCodec.encodeCurrent(state);
      M11RuntimeState decoded = decodeSnapshot(snapshotBytes).state();
      trace.add(
          new SnapshotEvent(
              decoded.nextApplicationSequence() - 1,
              decoded.identityBindings().size(),
              decoded.identityBindings().size()));
    }

    private void corruptSnapshot() {
      if (snapshotBytes == null) {
        return;
      }
      snapshotBytes = snapshotBytes.clone();
      snapshotBytes[snapshotBytes.length / 2] ^= 1;
      snapshotCorrupted = true;
      trace.add(new CorruptionEvent(snapshotBytes.length / 2));
    }

    private void restart() {
      if (snapshotBytes == null) {
        return;
      }
      try {
        M11RuntimeState decoded = snapshotCodec.decodeForRecovery(snapshotBytes).state();
        runtime = DirectM11MatchingRuntime.restore(decoded, faultPolicy);
        int restoredIdentities = runtime.identityBindingCount();
        RestoreOutcome outcome = RestoreOutcome.EXACT;
        if (snapshotCorrupted && decoded.nextApplicationSequence() == 1) {
          outcome = RestoreOutcome.GENESIS_FALLBACK;
        } else if (restoredIdentities < decoded.identityBindings().size()) {
          outcome = RestoreOutcome.MATCHER_WITHOUT_IDENTITY;
        }
        restoreBlocked = false;
        trace.add(new RestoreEvent(outcome, decoded.nextApplicationSequence(), restoredIdentities));
      } catch (M11ProtocolException | IllegalArgumentException failure) {
        restoreBlocked = true;
        trace.add(new RestoreEvent(RestoreOutcome.FAIL_CLOSED, 0, 0));
      }
    }

    private void decodePrevious(Artifact artifact) {
      byte[] encoded = readBytes(repositoryRoot.resolve(golden(artifact)));
      int wireVersion = ByteBuffer.wrap(encoded).getInt(Integer.BYTES);
      try {
        int decodedVersion =
            switch (artifact) {
              case REQUEST -> requestCodec.decodeCanonical(encoded, 1).protocolVersion();
              case RESPONSE -> responseCodec.decodeCanonical(encoded).protocolVersion();
              case SNAPSHOT -> snapshotCodec.decodeCanonical(encoded).schemaVersion();
            };
        trace.add(new DecodeEvent(artifact, wireVersion, true, decodedVersion, ""));
      } catch (M11ProtocolException failure) {
        trace.add(new DecodeEvent(artifact, wireVersion, false, 0, failure.code().name()));
      }
    }

    private void readDigest(String label) {
      String businessDigest = runtime.semanticStateDigest();
      String exposed = runtime.exposedSemanticStateDigest(session, original.correlationId());
      trace.add(new DigestEvent(label, session, businessDigest, exposed));
    }

    private void decodeUnsupported() {
      byte[] immutable = requestCodec.encode(original);
      ByteBuffer.wrap(immutable).putInt(Integer.BYTES, 3);
      int originalWireVersion = ByteBuffer.wrap(immutable).getInt(Integer.BYTES);
      try {
        M11CommandRequest decoded = requestCodec.decodeCanonical(immutable.clone(), 1);
        trace.add(
            new DecodeEvent(
                Artifact.REQUEST, originalWireVersion, true, decoded.protocolVersion(), ""));
      } catch (M11ProtocolException failure) {
        trace.add(
            new DecodeEvent(
                Artifact.REQUEST, originalWireVersion, false, 0, failure.code().name()));
      }
    }

    private void writeStandaloneWal(byte[] canonicalEnvelope) {
      Path wal = scratch.resolve("standalone.m11wal");
      ByteBuffer record = ByteBuffer.allocate(Integer.BYTES + canonicalEnvelope.length);
      record.putInt(canonicalEnvelope.length).put(canonicalEnvelope).flip();
      try (FileChannel channel =
          FileChannel.open(
              wal,
              StandardOpenOption.CREATE,
              StandardOpenOption.WRITE,
              StandardOpenOption.APPEND)) {
        while (record.hasRemaining()) {
          channel.write(record);
        }
        channel.force(true);
      } catch (IOException failure) {
        throw new IllegalStateException("mutant standalone WAL write failed", failure);
      }
    }

    private io.github.lchareln.cex.matching.cluster.M11Snapshot decodeSnapshot(byte[] encoded) {
      try {
        return snapshotCodec.decodeCanonical(encoded);
      } catch (M11ProtocolException failure) {
        throw new IllegalStateException("cannot decode candidate snapshot", failure);
      }
    }
  }

  private static final class UnifiedObserver {
    private UnifiedObserver() {}

    static Optional<String> observe(Trace trace) {
      List<TraceEvent> events = trace.events();
      for (int index = 0; index < events.size(); index++) {
        if (events.get(index) instanceof CompletionEvent completion
            && completion.source() == CompletionSource.INGRESS_OFFER
            && !hasPriorApply(events, index, completion.correlationId())) {
          return Optional.of("INGRESS_OFFER_UPGRADED_TO_BUSINESS_SUCCESS");
        }
      }

      List<IdentitySubmissionEvent> identities =
          events.stream()
              .filter(IdentitySubmissionEvent.class::isInstance)
              .map(IdentitySubmissionEvent.class::cast)
              .toList();
      if (identities.size() >= 2) {
        IdentitySubmissionEvent first = identities.get(0);
        IdentitySubmissionEvent second = identities.get(1);
        if (first.identity().equals(second.identity())
            && first.status() == OutcomeStatus.NEW
            && second.status() == OutcomeStatus.NEW) {
          if (!first.session().equals(second.session())
              && first.correlationId().equals(second.correlationId())) {
            return Optional.of("SESSION_CHANGED_BUSINESS_IDENTITY");
          }
          if (first.session().equals(second.session())
              && !first.correlationId().equals(second.correlationId())) {
            return Optional.of("CORRELATION_CHANGED_BUSINESS_IDENTITY");
          }
        }
      }

      for (int index = 0; index < events.size(); index++) {
        if (events.get(index) instanceof CompletionEvent completion
            && completion.source() == CompletionSource.CORRELATED_EGRESS
            && !hasPriorBinding(events, index)) {
          CrashEvent crash = firstAfter(events, index, CrashEvent.class);
          boolean matchingNewRetryAfter =
              events.subList(index + 1, events.size()).stream()
                  .filter(RetryEvent.class::isInstance)
                  .map(RetryEvent.class::cast)
                  .anyMatch(
                      retry ->
                          retry.status() == OutcomeStatus.NEW
                              && crash != null
                              && retry.applicationSequence()
                                  == crash.restoredNextApplicationSequence());
          if (crash != null && matchingNewRetryAfter) {
            return Optional.of("RESPONSE_OBSERVED_BEFORE_RESULT_BIND");
          }
        }
      }

      SnapshotEvent snapshot = first(events, SnapshotEvent.class);
      RestoreEvent restore = last(events, RestoreEvent.class);
      RetryEvent retry = last(events, RetryEvent.class);
      if (snapshot != null
          && restore != null
          && retry != null
          && snapshot.liveIdentityCount() > 0
          && snapshot.persistedIdentityCount() == snapshot.liveIdentityCount()
          && restore.identityBindingCount() < snapshot.persistedIdentityCount()
          && restore.outcome() == RestoreOutcome.MATCHER_WITHOUT_IDENTITY
          && retry.status() == OutcomeStatus.NEW
          && retry.applicationSequence() > snapshot.applicationSequence()) {
        return Optional.of("SNAPSHOT_LOST_IDEMPOTENCY_TABLE");
      }
      if (snapshot != null
          && restore != null
          && retry != null
          && snapshot.applicationSequence() > 0
          && events.stream().anyMatch(CorruptionEvent.class::isInstance)
          && restore.outcome() == RestoreOutcome.GENESIS_FALLBACK
          && retry.status() == OutcomeStatus.NEW) {
        return Optional.of("CORRUPT_SNAPSHOT_SILENTLY_BECAME_GENESIS");
      }

      for (TraceEvent event : events) {
        if (event instanceof DecodeEvent decode
            && decode.wireVersion() == 1
            && !decode.accepted()
            && M11ProtocolException.Code.UNSUPPORTED_VERSION
                .name()
                .equals(decode.rejectionCode())) {
          return Optional.of("N_MINUS_ONE_COMPATIBILITY_REJECTED");
        }
      }

      List<DigestEvent> digests =
          events.stream()
              .filter(DigestEvent.class::isInstance)
              .map(DigestEvent.class::cast)
              .toList();
      if (digests.size() >= 2) {
        DigestEvent first = digests.get(0);
        DigestEvent second = digests.get(1);
        if (first.businessDigest().equals(second.businessDigest())
            && !first.exposedDigest().equals(second.exposedDigest())) {
          return Optional.of("RUNTIME_METADATA_CHANGED_BUSINESS_DIGEST");
        }
      }

      if (events.stream()
          .filter(WalEvent.class::isInstance)
          .map(WalEvent.class::cast)
          .anyMatch(wal -> wal.bytes() > 0 && wal.forced())) {
        return Optional.of("CLUSTER_SERVICE_WROTE_STANDALONE_WAL");
      }
      for (TraceEvent event : events) {
        if (event instanceof DecodeEvent decode
            && decode.wireVersion() > M11RequestCodec.CURRENT_VERSION
            && decode.accepted()) {
          return Optional.of("UNSUPPORTED_VERSION_ACCEPTED");
        }
      }
      return Optional.empty();
    }

    private static boolean hasPriorApply(List<TraceEvent> events, int end, UUID correlationId) {
      return events.subList(0, end).stream()
          .filter(ApplyEvent.class::isInstance)
          .map(ApplyEvent.class::cast)
          .anyMatch(apply -> apply.correlationId().equals(correlationId));
    }

    private static boolean hasPriorBinding(List<TraceEvent> events, int end) {
      return events.subList(0, end).stream().anyMatch(BindingEvent.class::isInstance);
    }

    private static <T extends TraceEvent> T firstAfter(
        List<TraceEvent> events, int start, Class<T> type) {
      return events.subList(start + 1, events.size()).stream()
          .filter(type::isInstance)
          .map(type::cast)
          .findFirst()
          .orElse(null);
    }

    private static <T extends TraceEvent> T first(List<TraceEvent> events, Class<T> type) {
      return events.stream().filter(type::isInstance).map(type::cast).findFirst().orElse(null);
    }

    private static <T extends TraceEvent> T last(List<TraceEvent> events, Class<T> type) {
      T found = null;
      for (TraceEvent event : events) {
        if (type.isInstance(event)) {
          found = type.cast(event);
        }
      }
      return found;
    }
  }

  private static final class Trace {
    private final List<TraceEvent> events = new ArrayList<>();

    void add(TraceEvent event) {
      events.add(event);
    }

    List<TraceEvent> events() {
      return List.copyOf(events);
    }
  }

  private sealed interface TraceEvent
      permits IngressEvent,
          CompletionEvent,
          IdentitySubmissionEvent,
          ApplyEvent,
          BindingEvent,
          CrashEvent,
          RetryEvent,
          SnapshotEvent,
          CorruptionEvent,
          RestoreEvent,
          DecodeEvent,
          DigestEvent,
          WalEvent {}

  private record IngressEvent(UUID correlationId, long offeredBytes) implements TraceEvent {}

  private record CompletionEvent(UUID correlationId, CompletionSource source, OutcomeStatus status)
      implements TraceEvent {}

  private record IdentitySubmissionEvent(
      IdentityKey identity,
      String session,
      UUID correlationId,
      OutcomeStatus status,
      long applicationSequence,
      String resultDigest)
      implements TraceEvent {}

  private record ApplyEvent(
      UUID correlationId, OutcomeStatus status, long applicationSequence, String resultDigest)
      implements TraceEvent {}

  private record BindingEvent(UUID commandId, long applicationSequence) implements TraceEvent {}

  private record CrashEvent(long restoredNextApplicationSequence) implements TraceEvent {}

  private record RetryEvent(
      UUID correlationId, OutcomeStatus status, long applicationSequence, String resultDigest)
      implements TraceEvent {}

  private record SnapshotEvent(
      long applicationSequence, int liveIdentityCount, int persistedIdentityCount)
      implements TraceEvent {}

  private record CorruptionEvent(int byteOffset) implements TraceEvent {}

  private record RestoreEvent(
      RestoreOutcome outcome, long nextApplicationSequence, int identityBindingCount)
      implements TraceEvent {}

  private record DecodeEvent(
      Artifact artifact,
      int wireVersion,
      boolean accepted,
      int decodedVersion,
      String rejectionCode)
      implements TraceEvent {}

  private record DigestEvent(
      String label, String session, String businessDigest, String exposedDigest)
      implements TraceEvent {}

  private record WalEvent(String file, long bytes, boolean forced) implements TraceEvent {}

  private record IdentityKey(UUID commandId, Slot slot, String payloadHash) {
    static IdentityKey of(M11CommandRequest request) {
      return new IdentityKey(request.commandId(), request.slot(), request.payloadHash());
    }
  }

  private record Definition(
      String id, M11FaultPolicy.Mode mutation, String fingerprint, List<Step> history) {
    Definition {
      history = List.copyOf(history);
    }
  }

  private record Outcome(
      Classification classification, String fingerprint, String detail, int mutationActions) {}

  private record Shrink(List<Step> steps, int trials) {
    Shrink {
      steps = List.copyOf(steps);
    }
  }

  private record ReplayAudit(ObjectNode report, byte[] canonicalBytes, String digest) {
    ReplayAudit {
      report = report.deepCopy();
      canonicalBytes = canonicalBytes.clone();
    }

    @Override
    public ObjectNode report() {
      return report.deepCopy();
    }

    @Override
    public byte[] canonicalBytes() {
      return canonicalBytes.clone();
    }
  }

  private record ControlOutcome(Classification classification, String path, String failureType) {}

  record Result(
      ObjectNode counterexamples,
      byte[] persistedBytes,
      ArrayNode candidates,
      ArrayNode controls,
      ObjectNode replayReport,
      byte[] canonicalBytes,
      String digest,
      int killed,
      int rawActions,
      int minimalActions,
      int shrinkTrials,
      int actualMutationActions) {
    Result {
      counterexamples = counterexamples.deepCopy();
      persistedBytes = persistedBytes.clone();
      candidates = candidates.deepCopy();
      controls = controls.deepCopy();
      replayReport = replayReport.deepCopy();
      canonicalBytes = canonicalBytes.clone();
    }

    @Override
    public ObjectNode counterexamples() {
      return counterexamples.deepCopy();
    }

    @Override
    public byte[] persistedBytes() {
      return persistedBytes.clone();
    }

    @Override
    public ArrayNode candidates() {
      return candidates.deepCopy();
    }

    @Override
    public ArrayNode controls() {
      return controls.deepCopy();
    }

    @Override
    public ObjectNode replayReport() {
      return replayReport.deepCopy();
    }

    @Override
    public byte[] canonicalBytes() {
      return canonicalBytes.clone();
    }
  }

  private record Step(Action action, String value, String encoded) {
    static Step parse(String encoded) {
      if (encoded == null || !encoded.equals(encoded.strip())) {
        throw new InvalidHistory("step contains surrounding whitespace");
      }
      int separator = encoded.indexOf(':');
      if (separator <= 0 || separator == encoded.length() - 1) {
        throw new InvalidHistory("malformed step");
      }
      String kind = encoded.substring(0, separator);
      String value = encoded.substring(separator + 1);
      if (!value.matches("[a-z0-9][a-z0-9-]*")) {
        throw new InvalidHistory("invalid step value");
      }
      final Action action;
      try {
        action = Action.valueOf(kind);
      } catch (IllegalArgumentException failure) {
        throw new InvalidHistory("unknown step kind " + kind);
      }
      return new Step(action, value, encoded);
    }

    static Step parsePersisted(JsonNode node) {
      if (!node.isObject() || !node.path("kind").isString() || !node.path("encoded").isString()) {
        throw new InvalidHistory("persisted step is malformed");
      }
      Step step = parse(node.path("encoded").stringValue());
      if (!step.action().name().equals(node.path("kind").stringValue())) {
        throw new InvalidHistory("persisted step kind disagrees with encoding");
      }
      return step;
    }

    void write(ObjectNode node) {
      node.put("kind", action.name());
      node.put("encoded", encoded);
    }
  }

  private enum Action {
    NOOP,
    OFFER,
    SUBMIT_SESSION,
    SUBMIT_CORRELATION,
    BEGIN_SUBMIT,
    CRASH,
    RETRY,
    SUBMIT,
    TAKE_SNAPSHOT,
    CORRUPT_SNAPSHOT,
    RESTART,
    DECODE_REQUEST_V1,
    DECODE_RESPONSE_V1,
    DECODE_SNAPSHOT_V1,
    READ_DIGEST,
    SET_SESSION,
    DECODE_REQUEST_V3
  }

  private enum Classification {
    PASS,
    STUDENT_FAILURE,
    SYSTEM_ERROR,
    INVALID_HISTORY
  }

  private enum OutcomeStatus {
    NEW,
    DUPLICATE,
    REJECTED,
    BLOCKED
  }

  private enum CompletionSource {
    INGRESS_OFFER,
    CORRELATED_EGRESS
  }

  private enum RestoreOutcome {
    EXACT,
    MATCHER_WITHOUT_IDENTITY,
    FAIL_CLOSED,
    GENESIS_FALLBACK
  }

  private enum Artifact {
    REQUEST,
    RESPONSE,
    SNAPSHOT
  }

  private static M11CommandRequest request() {
    try {
      return new M11RequestCodec()
          .create(
              2,
              2,
              fixedCorrelation(),
              "m11-mutant",
              1,
              1,
              1,
              new UUID(17, 19),
              new M08Command.Place(
                  "BTC-USDT",
                  BigInteger.valueOf(7001),
                  "BUY",
                  BigInteger.valueOf(5_000_000),
                  BigInteger.ONE,
                  "GTC",
                  0,
                  "NONE",
                  Optional.empty()));
    } catch (M11ProtocolException failure) {
      throw new IllegalStateException("cannot build M11 mutant request", failure);
    }
  }

  private static Path golden(Artifact artifact) {
    String file =
        switch (artifact) {
          case REQUEST -> "request-v1.bin";
          case RESPONSE -> "response-v1.bin";
          case SNAPSHOT -> "snapshot-v1.bin";
        };
    return Path.of("matching-testkit/src/test/resources/m11/goldens").resolve(file);
  }

  private static OutcomeStatus status(M11ApplicationResult result) {
    return status(result.response().status());
  }

  private static OutcomeStatus status(M11ResponseStatus status) {
    return switch (status) {
      case NEW_APPLIED -> OutcomeStatus.NEW;
      case DUPLICATE_REPLAYED -> OutcomeStatus.DUPLICATE;
      case REJECTED -> OutcomeStatus.REJECTED;
    };
  }

  private static long applicationSequence(M11ApplicationResult result) {
    return result.response().applicationSequence().orElse(0);
  }

  private static String resultDigest(M11ApplicationResult result) {
    return result.response().resultDigest().orElse("");
  }

  private static UUID fixedCorrelation() {
    return new UUID(23, 29);
  }

  private static UUID retryCorrelation() {
    return new UUID(31, 37);
  }

  private static UUID namedUuid(String value) {
    return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8));
  }

  private static Path createTemporaryDirectory() {
    try {
      return Files.createTempDirectory("m11-mutant-suite-");
    } catch (IOException failure) {
      throw new IllegalStateException("cannot create M11 mutant suite directory", failure);
    }
  }

  private static Path createCandidateDirectory(Path suiteRoot) {
    try {
      return Files.createTempDirectory(suiteRoot, "candidate-");
    } catch (IOException failure) {
      throw new IllegalStateException("cannot create M11 candidate directory", failure);
    }
  }

  private static String stableDetail(Throwable failure) {
    String message = failure.getMessage();
    return failure.getClass().getSimpleName() + (message == null ? "" : ":" + message);
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

  private static void semanticRequire(boolean condition, String message) {
    if (!condition) {
      throw new M11SemanticFailure(message);
    }
  }

  private static void systemRequire(boolean condition, String message) {
    if (!condition) {
      throw new IllegalStateException(message);
    }
  }

  private static final class InvalidHistory extends RuntimeException {
    private static final long serialVersionUID = 1L;

    InvalidHistory(String message) {
      super(message);
    }
  }
}
