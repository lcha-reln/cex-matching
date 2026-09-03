package io.github.lchareln.cex.matching.testkit;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Eight frozen single-fault semantic candidates plus three non-kill system controls. */
final class M12MutantSuite {
  private static final String CLASSIFIER = "M12_SHARED_FAILURE_CLASSIFIER_V1";
  private static final String OBSERVER = "M12_PURE_DATA_HISTORY_OBSERVER_V1";

  Result run(M12WorkloadLoader.Workload workload, M12DeterministicCorpus.Corpus corpus) {
    M12ExecutionTrace production = M12ExecutionTrace.deterministicModelControl(corpus);
    Map<String, Definition> definitions = definitions();
    ArrayNode candidates = JsonSupport.MAPPER.createArrayNode();
    ArrayNode witnesses = JsonSupport.MAPPER.createArrayNode();
    int killed = 0;
    int shrinkTrials = 0;

    for (String id : workload.requiredMutants()) {
      Definition definition = definitions.get(id);
      systemRequire(definition != null, "missing mutant definition " + id);
      List<Step> raw =
          List.of(
              new Step(StepKind.NOOP, "prefix"),
              new Step(StepKind.APPLY_SINGLE_FAULT, id),
              new Step(StepKind.NOOP, "suffix"));
      Outcome control = execute(workload, production, definition, raw, Mode.NONE);
      systemRequire(
          control.classification() == Classification.PASS, "production control did not PASS " + id);
      Outcome candidate = execute(workload, production, definition, raw, definition.mode());
      systemRequire(
          candidate.classification() != Classification.SYSTEM_ERROR,
          "mutant raised SYSTEM_ERROR " + id);
      systemRequire(
          candidate.classification() == Classification.STUDENT_FAILURE, "mutant survived " + id);
      systemRequire(
          definition.fingerprint().equals(candidate.fingerprint()),
          "mutant fingerprint changed " + id);
      systemRequire(candidate.mutationActions() == 1, "mutant action count changed " + id);

      Shrink shrink = minimize(workload, production, definition, raw);
      shrinkTrials += shrink.trials();
      systemRequire(shrink.steps().size() == 1, "counterexample did not shrink to one step " + id);
      Outcome replay = execute(workload, production, definition, shrink.steps(), definition.mode());
      systemRequire(
          replay.classification() == Classification.STUDENT_FAILURE
              && replay.fingerprint().equals(definition.fingerprint())
              && replay.mutationActions() == 1,
          "minimal replay changed " + id);
      systemRequire(
          oneMinimal(workload, production, definition, shrink.steps()),
          "counterexample is not one-minimal " + id);

      String counterexampleId = "cex-m12-" + id.substring(4).toLowerCase();
      ObjectNode candidateNode = candidates.addObject();
      candidateNode.put("id", id);
      candidateNode.put("productionClassification", Classification.PASS.name());
      candidateNode.put("classification", Classification.STUDENT_FAILURE.name());
      candidateNode.put("fingerprint", candidate.fingerprint());
      candidateNode.put("singleFaultMode", definition.mode().name());
      candidateNode.put("actualMutationActions", candidate.mutationActions());
      candidateNode.put("observer", OBSERVER);
      candidateNode.put("systemErrorCountedAsKill", false);
      candidateNode.put("counterexampleId", counterexampleId);
      candidateNode.put("semanticModelOnly", true);
      candidateNode.put("realClusterExecuted", false);
      candidateNode.put("eligibleAsClusterEvidence", false);

      ObjectNode witness = witnesses.addObject();
      witness.put("id", counterexampleId);
      witness.put("mutant", id);
      witness.put("classification", Classification.STUDENT_FAILURE.name());
      witness.put("fingerprint", candidate.fingerprint());
      witness.put("rawActions", raw.size());
      witness.put("minimalActions", shrink.steps().size());
      witness.put("shrinkTrials", shrink.trials());
      witness.put("actualMutationActions", replay.mutationActions());
      witness.put("oneMinimal", true);
      witness.put("minimalityScope", "ONE_MINIMAL_WITHIN_TYPED_STEP_DELETION_GRAMMAR");
      witness.put("globalMinimumClaim", false);
      witness.put("strictFreshReplay", true);
      witness.put("semanticModelOnly", true);
      ArrayNode steps = witness.putArray("steps");
      shrink.steps().forEach(step -> step.write(steps.addObject()));
      witness.put("replaySha256", replayDigest(id, shrink.steps(), candidate.fingerprint()));
      killed++;
    }

    List<M12CoverageLedger.SystemControlObservation> controlValues = controls(workload);
    ArrayNode controls = JsonSupport.MAPPER.createArrayNode();
    controlValues.forEach(control -> writeControl(control, controls.addObject()));
    systemRequire(killed == 8, "not every M12 mutant was killed");
    systemRequire(controlValues.size() == 3, "system control count changed");

    ObjectNode counterexamples = JsonSupport.MAPPER.createObjectNode();
    counterexamples.put("schemaVersion", "matching.m12.counterexamples.v1");
    counterexamples.put("seed", "6120");
    counterexamples.put("required", 8);
    counterexamples.put("persisted", 8);
    counterexamples.put("semanticModelOnly", true);
    counterexamples.put("realClusterEvidence", false);
    counterexamples.put("invalidHistoryCountedAsKill", false);
    counterexamples.put("systemErrorCountedAsKill", false);
    counterexamples.put("minimalityScope", "ONE_MINIMAL_WITHIN_TYPED_STEP_DELETION_GRAMMAR");
    counterexamples.put("globalMinimumClaim", false);
    counterexamples.set("witnesses", witnesses);
    byte[] serialized = JsonSupport.prettyBytes(counterexamples);
    Replay replay = replay(workload, production, definitions, JsonSupport.parse(serialized));

    ObjectNode report = JsonSupport.MAPPER.createObjectNode();
    report.put("schemaVersion", "matching.m12.mutants.v1");
    report.put("status", "PASS");
    report.put("required", 8);
    report.put("killed", killed);
    report.put("productionControlsPassed", 8);
    report.put("singleFaultCandidates", true);
    report.put("systemErrorCountedAsKill", false);
    report.put("systemErrorControls", 3);
    report.put("shrinkTrials", shrinkTrials);
    report.put("observer", OBSERVER);
    report.put("classifier", CLASSIFIER);
    report.put("semanticModelOnly", true);
    report.put("realClusterExecuted", false);
    report.put("eligibleAsClusterEvidence", false);
    report.set("candidates", candidates);
    report.set("controls", controls);

    return new Result(
        report,
        counterexamples,
        replay.report(),
        serialized,
        List.copyOf(controlValues),
        killed,
        shrinkTrials);
  }

  private static Map<String, Definition> definitions() {
    Map<String, Definition> result = new LinkedHashMap<>();
    add(result, "M12-OFFER-AS-ACK", Mode.OFFER_AS_ACK, "ACK_WITHOUT_TRUSTED_RESPONSE");
    add(
        result,
        "M12-TIMEOUT-AS-REJECTED",
        Mode.TIMEOUT_AS_REJECTED,
        "TIMEOUT_AS_BUSINESS_REJECTION");
    add(
        result,
        "M12-RETRY-WITH-NEW-IDENTITY",
        Mode.RETRY_WITH_NEW_IDENTITY,
        "UNKNOWN_RETRY_CHANGED_DURABLE_IDENTITY");
    add(
        result,
        "M12-DUPLICATE-AS-NEW-EFFECT",
        Mode.DUPLICATE_AS_NEW_EFFECT,
        "DUPLICATE_CREATED_SECOND_EFFECT");
    add(result, "M12-MINORITY-ACK", Mode.MINORITY_ACK, "MINORITY_ACKNOWLEDGED");
    add(
        result,
        "M12-ACCEPT-STALE-LEADER-AUTHORITY",
        Mode.ACCEPT_STALE_LEADER_AUTHORITY,
        "STALE_LEADER_ACKNOWLEDGED");
    add(
        result,
        "M12-DROP-IDENTITY-DURING-CATCH-UP",
        Mode.DROP_IDENTITY_DURING_CATCH_UP,
        "FOLLOWER_IDENTITY_TABLE_DIVERGED");
    add(
        result,
        "M12-INCLUDE-TERM-IN-SEMANTIC-DIGEST",
        Mode.INCLUDE_TERM_IN_SEMANTIC_DIGEST,
        "RUNTIME_METADATA_CHANGED_SEMANTIC_DIGEST");
    return Map.copyOf(result);
  }

  private static void add(
      Map<String, Definition> target, String id, Mode mode, String fingerprint) {
    target.put(id, new Definition(id, mode, fingerprint));
  }

  private static Outcome execute(
      M12WorkloadLoader.Workload workload,
      M12ExecutionTrace production,
      Definition definition,
      List<Step> steps,
      Mode activeMode) {
    int actions = 0;
    try {
      M12ExecutionTrace candidate = production;
      for (Step step : steps) {
        if (step.kind() == StepKind.APPLY_SINGLE_FAULT && activeMode != Mode.NONE) {
          if (!definition.id().equals(step.token())) {
            throw new IllegalStateException("step targets another mutant");
          }
          candidate = mutate(candidate, activeMode);
          actions++;
        }
      }
      new M12HistoryJudge().inspect(workload, candidate);
      return new Outcome(Classification.PASS, "", actions);
    } catch (M12SemanticFailure failure) {
      return new Outcome(Classification.STUDENT_FAILURE, failure.fingerprint(), actions);
    } catch (RuntimeException failure) {
      return new Outcome(Classification.SYSTEM_ERROR, failure.getClass().getSimpleName(), actions);
    }
  }

  private static M12ExecutionTrace mutate(M12ExecutionTrace trace, Mode mode) {
    return switch (mode) {
      case NONE -> trace;
      case OFFER_AS_ACK ->
          replaceAttempt(
              trace,
              "APPLIED_RESPONSE_UNOBSERVED_UNKNOWN_1",
              attempt ->
                  attempt.withOutcomeAndResponse(
                      M12DeterministicCorpus.ClientOutcome.ACKNOWLEDGED,
                      false,
                      null,
                      null,
                      attempt.applicationSequence(),
                      attempt.resultDigest(),
                      attempt.businessEffectApplied(),
                      false));
      case TIMEOUT_AS_REJECTED ->
          replaceAttempt(
              trace,
              "APPLIED_RESPONSE_UNOBSERVED_UNKNOWN_1",
              attempt ->
                  attempt.withOutcomeAndResponse(
                      attempt.outcome(),
                      attempt.trustedResponseObserved(),
                      attempt.responseCorrelationId(),
                      attempt.responseStatus(),
                      attempt.applicationSequence(),
                      attempt.resultDigest(),
                      attempt.businessEffectApplied(),
                      true));
      case RETRY_WITH_NEW_IDENTITY ->
          replaceAttempt(
              trace,
              "SAME_IDENTITY_UNKNOWN_RETRY",
              attempt -> attempt.withIdentity(trace.identities().get(33)));
      case DUPLICATE_AS_NEW_EFFECT ->
          replaceAttempt(
              trace,
              "ACKNOWLEDGED_DUPLICATE_RETRY_8",
              attempt ->
                  attempt.withOutcomeAndResponse(
                      attempt.outcome(),
                      true,
                      attempt.correlationId(),
                      M12DeterministicCorpus.ResponseStatus.NEW_APPLIED,
                      attempt.applicationSequence(),
                      attempt.resultDigest(),
                      true,
                      false));
      case MINORITY_ACK -> {
        M12DeterministicCorpus.Binding binding = trace.bindings().getLast();
        M12ExecutionTrace changed =
            replaceAttempt(
                trace,
                "NO_QUORUM_UNKNOWN_1",
                attempt ->
                    attempt.withOutcomeAndResponse(
                        M12DeterministicCorpus.ClientOutcome.ACKNOWLEDGED,
                        true,
                        attempt.correlationId(),
                        M12DeterministicCorpus.ResponseStatus.NEW_APPLIED,
                        binding.applicationSequence(),
                        binding.resultDigest(),
                        true,
                        false));
        yield changed.withTopology(changed.topology().withMinorityAcknowledgements(1));
      }
      case ACCEPT_STALE_LEADER_AUTHORITY -> {
        M12ExecutionTrace changed =
            replaceAttempt(
                trace,
                "POST_FAILOVER_ACKNOWLEDGED_NEW_32",
                attempt -> attempt.withCurrentClientAuthorityAcceptance(false));
        yield changed.withTopology(changed.topology().withStaleLeaderAcknowledgements(1));
      }
      case DROP_IDENTITY_DURING_CATCH_UP -> {
        List<M12ExecutionTrace.MemberObservation> members =
            new ArrayList<>(trace.topology().convergedMembers());
        for (int index = 0; index < members.size(); index++) {
          M12ExecutionTrace.MemberObservation member = members.get(index);
          if (member.memberId() == trace.topology().formerLeaderId()) {
            members.set(
                index,
                member.withState(
                    member.nextApplicationSequence(),
                    65,
                    member.semanticDigest(),
                    Hashing.semanticDigest("dropped-identity".getBytes(StandardCharsets.UTF_8))));
          }
        }
        yield trace.withTopology(trace.topology().withConvergedMembers(members));
      }
      case INCLUDE_TERM_IN_SEMANTIC_DIGEST -> {
        List<M12ExecutionTrace.MemberObservation> members =
            trace.topology().convergedMembers().stream()
                .map(
                    member ->
                        member.withState(
                            member.nextApplicationSequence(),
                            member.identityCount(),
                            Hashing.semanticDigest(
                                (member.semanticDigest() + "|term=" + member.leadershipTerm())
                                    .getBytes(StandardCharsets.UTF_8)),
                            member.identityTableDigest()))
                .toList();
        yield trace.withTopology(trace.topology().withConvergedMembers(members));
      }
    };
  }

  private static M12ExecutionTrace replaceAttempt(
      M12ExecutionTrace trace,
      String phase,
      java.util.function.UnaryOperator<M12DeterministicCorpus.Attempt> mutation) {
    List<M12DeterministicCorpus.Attempt> attempts = new ArrayList<>(trace.attempts());
    for (int index = 0; index < attempts.size(); index++) {
      if (phase.equals(attempts.get(index).phase())) {
        attempts.set(index, mutation.apply(attempts.get(index)));
        return trace.withAttempts(attempts);
      }
    }
    throw new IllegalStateException("mutation phase is missing " + phase);
  }

  private static Shrink minimize(
      M12WorkloadLoader.Workload workload,
      M12ExecutionTrace production,
      Definition definition,
      List<Step> raw) {
    List<Step> current = new ArrayList<>(raw);
    int trials = 0;
    boolean changed = true;
    while (changed) {
      changed = false;
      for (int index = 0; index < current.size(); index++) {
        List<Step> trial = new ArrayList<>(current);
        trial.remove(index);
        trials++;
        Outcome outcome = execute(workload, production, definition, trial, definition.mode());
        if (outcome.classification() == Classification.STUDENT_FAILURE
            && definition.fingerprint().equals(outcome.fingerprint())
            && outcome.mutationActions() == 1) {
          current = trial;
          changed = true;
          break;
        }
      }
    }
    return new Shrink(List.copyOf(current), trials);
  }

  private static boolean oneMinimal(
      M12WorkloadLoader.Workload workload,
      M12ExecutionTrace production,
      Definition definition,
      List<Step> steps) {
    for (int index = 0; index < steps.size(); index++) {
      List<Step> trial = new ArrayList<>(steps);
      trial.remove(index);
      Outcome outcome = execute(workload, production, definition, trial, definition.mode());
      if (outcome.classification() == Classification.STUDENT_FAILURE
          && definition.fingerprint().equals(outcome.fingerprint())
          && outcome.mutationActions() == 1) {
        return false;
      }
    }
    return true;
  }

  private static Replay replay(
      M12WorkloadLoader.Workload workload,
      M12ExecutionTrace production,
      Map<String, Definition> definitions,
      JsonNode counterexamples) {
    ArrayNode audits = JsonSupport.MAPPER.createArrayNode();
    int replayed = 0;
    for (JsonNode witness : counterexamples.path("witnesses")) {
      String id = witness.path("mutant").stringValue();
      Definition definition = definitions.get(id);
      systemRequire(definition != null, "replay references unknown mutant");
      List<Step> steps = new ArrayList<>();
      witness.path("steps").forEach(node -> steps.add(Step.read(node)));
      Outcome productionControl = execute(workload, production, definition, steps, Mode.NONE);
      Outcome candidate = execute(workload, production, definition, steps, definition.mode());
      boolean exact =
          productionControl.classification() == Classification.PASS
              && candidate.classification() == Classification.STUDENT_FAILURE
              && definition.fingerprint().equals(candidate.fingerprint())
              && candidate.mutationActions() == 1
              && oneMinimal(workload, production, definition, steps)
              && witness
                  .path("replaySha256")
                  .stringValue()
                  .equals(replayDigest(id, steps, candidate.fingerprint()));
      systemRequire(exact, "strict counterexample replay failed " + id);
      ObjectNode audit = audits.addObject();
      audit.put("mutant", id);
      audit.put("classification", candidate.classification().name());
      audit.put("fingerprint", candidate.fingerprint());
      audit.put("productionControl", productionControl.classification().name());
      audit.put("oneMinimal", true);
      audit.put("exact", true);
      replayed++;
    }
    systemRequire(replayed == 8, "replay count changed");
    ObjectNode report = JsonSupport.MAPPER.createObjectNode();
    report.put("schemaVersion", "matching.m12.replay.v1");
    report.put("status", "PASS");
    report.put("replayed", replayed);
    report.put("productionControlsPassed", replayed);
    report.put("studentFailuresReproduced", replayed);
    report.put("fingerprintsExact", true);
    report.put("oneDeleteAudits", replayed);
    report.put("oneMinimal", true);
    report.put("systemErrorCountedAsKill", false);
    report.put("semanticModelOnly", true);
    report.set("audits", audits);
    return new Replay(report);
  }

  private static List<M12CoverageLedger.SystemControlObservation> controls(
      M12WorkloadLoader.Workload workload) {
    List<M12CoverageLedger.SystemControlObservation> result = new ArrayList<>();
    for (String id : workload.systemErrorControls()) {
      String path =
          switch (id) {
            case "M12-NON-LEADER-FAULT-TARGET-CONTROL" ->
                "M12InfrastructurePreconditions#requireCurrentLeaderFaultTarget";
            case "M12-CLUSTER-STARTUP-CONTROL" ->
                "M12InfrastructurePreconditions#requireStaticLaunchTopology";
            case "M12-CORRUPT-HISTORY-OUTPUT-CONTROL" ->
                "M12InfrastructurePreconditions#parseHistory";
            default -> throw new IllegalStateException("unknown system control " + id);
          };
      Outcome outcome = executeSystemControl(id);
      systemRequire(
          outcome.classification() == Classification.SYSTEM_ERROR,
          "system control did not classify SYSTEM_ERROR " + id);
      result.add(
          new M12CoverageLedger.SystemControlObservation(
              id, Classification.SYSTEM_ERROR.name(), false, path, outcome.fingerprint()));
    }
    return List.copyOf(result);
  }

  private static Outcome executeSystemControl(String id) {
    try {
      switch (id) {
        case "M12-NON-LEADER-FAULT-TARGET-CONTROL" ->
            M12InfrastructurePreconditions.requireCurrentLeaderFaultTarget(1, 0, "LEADER");
        case "M12-CLUSTER-STARTUP-CONTROL" ->
            M12InfrastructurePreconditions.requireStaticLaunchTopology(3, List.of(0, 1), 15);
        case "M12-CORRUPT-HISTORY-OUTPUT-CONTROL" ->
            M12InfrastructurePreconditions.parseHistory("{".getBytes(StandardCharsets.UTF_8));
        default -> throw new IllegalStateException("unknown control");
      }
      return new Outcome(Classification.PASS, "", 0);
    } catch (M12SemanticFailure failure) {
      return new Outcome(Classification.STUDENT_FAILURE, failure.fingerprint(), 0);
    } catch (RuntimeException failure) {
      return new Outcome(Classification.SYSTEM_ERROR, failure.getClass().getSimpleName(), 0);
    }
  }

  private static void writeControl(
      M12CoverageLedger.SystemControlObservation control, ObjectNode target) {
    target.put("id", control.id());
    target.put("classification", control.classification());
    target.put("classifier", CLASSIFIER);
    target.put("countedAsKill", control.countedAsKill());
    target.put("executedPath", control.executedPath());
    target.put("failureType", control.failureType());
  }

  private static String replayDigest(String id, List<Step> steps, String fingerprint) {
    StringBuilder canonical = new StringBuilder("M12-COUNTEREXAMPLE-REPLAY-V1\n");
    canonical.append(id).append('\n').append(fingerprint).append('\n');
    steps.forEach(
        step -> canonical.append(step.kind()).append(':').append(step.token()).append('\n'));
    return Hashing.sha256Hex(canonical.toString().getBytes(StandardCharsets.UTF_8));
  }

  private static void systemRequire(boolean condition, String message) {
    if (!condition) {
      throw new IllegalStateException("M12 mutant harness: " + message);
    }
  }

  private enum Mode {
    NONE,
    OFFER_AS_ACK,
    TIMEOUT_AS_REJECTED,
    RETRY_WITH_NEW_IDENTITY,
    DUPLICATE_AS_NEW_EFFECT,
    MINORITY_ACK,
    ACCEPT_STALE_LEADER_AUTHORITY,
    DROP_IDENTITY_DURING_CATCH_UP,
    INCLUDE_TERM_IN_SEMANTIC_DIGEST
  }

  private enum StepKind {
    NOOP,
    APPLY_SINGLE_FAULT
  }

  private enum Classification {
    PASS,
    STUDENT_FAILURE,
    SYSTEM_ERROR
  }

  private record Definition(String id, Mode mode, String fingerprint) {}

  private record Step(StepKind kind, String token) {
    Step {
      Objects.requireNonNull(kind);
      Objects.requireNonNull(token);
    }

    static Step read(JsonNode node) {
      return new Step(
          StepKind.valueOf(node.path("kind").stringValue()), node.path("token").stringValue());
    }

    void write(ObjectNode target) {
      target.put("kind", kind.name());
      target.put("token", token);
    }
  }

  private record Outcome(Classification classification, String fingerprint, int mutationActions) {}

  private record Shrink(List<Step> steps, int trials) {
    Shrink {
      steps = List.copyOf(steps);
    }
  }

  private record Replay(ObjectNode report) {
    Replay {
      report = report.deepCopy();
    }

    @Override
    public ObjectNode report() {
      return report.deepCopy();
    }
  }

  record Result(
      ObjectNode report,
      ObjectNode counterexamples,
      ObjectNode replayReport,
      byte[] counterexampleBytes,
      List<M12CoverageLedger.SystemControlObservation> controls,
      int killed,
      int shrinkTrials) {
    Result {
      report = report.deepCopy();
      counterexamples = counterexamples.deepCopy();
      replayReport = replayReport.deepCopy();
      counterexampleBytes = counterexampleBytes.clone();
      controls = List.copyOf(controls);
    }

    @Override
    public ObjectNode report() {
      return report.deepCopy();
    }

    @Override
    public ObjectNode counterexamples() {
      return counterexamples.deepCopy();
    }

    @Override
    public ObjectNode replayReport() {
      return replayReport.deepCopy();
    }

    @Override
    public byte[] counterexampleBytes() {
      return counterexampleBytes.clone();
    }
  }
}
