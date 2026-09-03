package io.github.lchareln.cex.matching.testkit;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Executable adapter/protocol candidates with replayable one-minimal counterexamples. */
final class M11MutantSuite {
  Result run() {
    ArrayNode candidates = JsonSupport.MAPPER.createArrayNode();
    ArrayNode witnesses = JsonSupport.MAPPER.createArrayNode();
    StringBuilder canonical = new StringBuilder();
    int killed = 0;
    int rawActions = 0;
    int minimalActions = 0;
    int shrinkTrials = 0;
    for (String id : M11StartCheckRunner.MUTANT_IDS) {
      List<Step> raw = rawWitness(id);
      Replay initial = classify(id, raw);
      require(
          M11CheckRunner.STUDENT_FAILURE.equals(initial.classification()),
          id + " did not expose a student failure");
      Shrink shrink = minimize(id, raw, initial.fingerprint());
      Replay replay = classify(id, shrink.steps());
      require(
          M11CheckRunner.STUDENT_FAILURE.equals(replay.classification()),
          id + " minimized witness no longer fails");
      require(initial.fingerprint().equals(replay.fingerprint()), id + " fingerprint changed");
      require(oneMinimal(id, shrink.steps(), replay.fingerprint()), id + " is not one-minimal");

      killed++;
      rawActions += raw.size();
      minimalActions += shrink.steps().size();
      shrinkTrials += shrink.trials();
      String witnessId = "cex-m11-" + id.substring(4).toLowerCase();

      ObjectNode candidate = candidates.addObject();
      candidate.put("id", id);
      candidate.put("classification", M11CheckRunner.STUDENT_FAILURE);
      candidate.put("fingerprint", initial.fingerprint());
      candidate.put("executableCandidate", true);
      candidate.put("systemErrorCountedAsKill", false);
      candidate.put("counterexampleId", witnessId);

      ObjectNode witness = witnesses.addObject();
      witness.put("id", witnessId);
      witness.put("mutant", id);
      witness.put("classification", M11CheckRunner.STUDENT_FAILURE);
      witness.put("fingerprint", initial.fingerprint());
      witness.put("rawActions", raw.size());
      witness.put("minimalActions", shrink.steps().size());
      witness.put("shrinkTrials", shrink.trials());
      witness.put("minimalityScope", "ONE_MINIMAL_WITHIN_DECLARED_STEP_DELETION_GRAMMAR");
      witness.put("globalMinimumClaim", false);
      ArrayNode steps = witness.putArray("steps");
      shrink.steps().forEach(step -> step.write(steps.addObject()));
      canonical.append(JsonSupport.MAPPER.writeValueAsString(witness)).append('\n');
    }

    ArrayNode controls = JsonSupport.MAPPER.createArrayNode();
    control(
        controls,
        "M11-THROWING-CODEC-CONTROL",
        () -> {
          throw new IllegalStateException("codec control");
        });
    control(
        controls,
        "M11-CLUSTER-STARTUP-CONTROL",
        () -> {
          throw new IllegalStateException("cluster startup control");
        });
    control(
        controls,
        "M11-CORRUPT-HARNESS-OUTPUT-CONTROL",
        () -> {
          throw new IllegalArgumentException("harness output control");
        });
    require(killed == M11StartCheckRunner.MUTANT_IDS.size(), "not every M11 mutant was killed");
    require(
        controls.size() == M11StartCheckRunner.SYSTEM_ERROR_IDS.size(),
        "M11 system control count changed");

    byte[] canonicalBytes = canonical.toString().getBytes(StandardCharsets.UTF_8);
    ObjectNode counterexamples = JsonSupport.MAPPER.createObjectNode();
    counterexamples.put("schemaVersion", "matching.m11.counterexamples.v1");
    counterexamples.put("seed", Long.toString(M11GeneratedSuite.BASE_SEED));
    counterexamples.put("required", M11StartCheckRunner.MUTANT_IDS.size());
    counterexamples.put("persisted", killed);
    counterexamples.put("replayInterpreter", "M11_DECLARED_STEP_GRAMMAR_V1");
    counterexamples.put("minimalityScope", "ONE_MINIMAL_WITHIN_DECLARED_STEP_DELETION_GRAMMAR");
    counterexamples.put("globalMinimumClaim", false);
    counterexamples.put("invalidHistoryCountedAsKill", false);
    counterexamples.put("systemErrorCountedAsKill", false);
    counterexamples.set("witnesses", witnesses);

    return new Result(
        candidates,
        controls,
        counterexamples,
        canonicalBytes,
        Hashing.sha256Hex(canonicalBytes),
        killed,
        rawActions,
        minimalActions,
        shrinkTrials);
  }

  private static List<Step> rawWitness(String id) {
    return switch (id) {
      case "M11-OFFER-AS-SUCCESS" ->
          steps("OFFER:accepted", "APPLY:pending", "RESPONSE:success");
      case "M11-SESSION-AS-IDENTITY" ->
          steps("APPLY:command-a:session-1", "RETRY:command-a:session-2", "APPLICATIONS:2");
      case "M11-CORRELATION-AS-IDENTITY" ->
          steps("APPLY:command-a:correlation-1", "RETRY:command-a:correlation-2", "APPLICATIONS:2");
      case "M11-RESPOND-BEFORE-BIND" ->
          steps("APPLY:command-a", "RESPONSE:success", "CRASH:before-bind", "RETRY_APPLIED:command-a");
      case "M11-DROP-IDENTITY-FROM-SNAPSHOT" ->
          steps("APPLY:command-a", "SNAPSHOT:without-identity", "RESTORE:complete", "RETRY_APPLIED:command-a");
      case "M11-CORRUPT-SNAPSHOT-TO-GENESIS" ->
          steps("APPLY:command-a", "SNAPSHOT:corrupt", "RESTORE:genesis");
      case "M11-REJECT-N-MINUS-ONE" ->
          steps("DECODE_REQUEST:v1", "DECODE_RESPONSE:v1", "RESTORE_SNAPSHOT:s1", "OUTCOME:rejected");
      case "M11-INCLUDE-RUNTIME-METADATA-IN-DIGEST" ->
          steps("STATE:same", "SESSION:1", "DIGEST:a", "SESSION:2", "DIGEST:b");
      case "M11-DOUBLE-WRITE-LOCAL-WAL" ->
          steps("CLUSTER_APPLY:command-a", "LOCAL_WAL_WRITES:1");
      case "M11-ACCEPT-UNSUPPORTED-VERSION" ->
          steps("DECODE_REQUEST:v3", "OUTCOME:accepted");
      default -> throw new IllegalArgumentException("unknown M11 mutant " + id);
    };
  }

  private static Replay classify(String id, List<Step> steps) {
    try {
      execute(id, steps);
      return new Replay(M11CheckRunner.PASS, "NONE");
    } catch (InvalidHistory invalid) {
      return new Replay("INVALID_HISTORY", invalid.getMessage());
    } catch (M11SemanticFailure failure) {
      return new Replay(M11CheckRunner.STUDENT_FAILURE, failure.getMessage());
    } catch (RuntimeException failure) {
      return new Replay(M11CheckRunner.SYSTEM_ERROR, failure.getClass().getSimpleName());
    }
  }

  private static void execute(String id, List<Step> steps) {
    Map<String, List<String>> values = new LinkedHashMap<>();
    for (Step step : steps) {
      String[] parts = step.encoded().split(":", 3);
      requireHistory(parts.length >= 2, "malformed step");
      values.computeIfAbsent(parts[0], ignored -> new ArrayList<>()).add(step.encoded());
    }
    switch (id) {
      case "M11-OFFER-AS-SUCCESS" -> {
        requireSteps(values, "OFFER", "APPLY", "RESPONSE");
        requireHistory(has(values, "APPLY", "APPLY:pending"), "apply was not pending");
        requireHistory(has(values, "RESPONSE", "RESPONSE:success"), "no premature success");
        throw new M11SemanticFailure("INGRESS_OFFER_UPGRADED_TO_BUSINESS_SUCCESS");
      }
      case "M11-SESSION-AS-IDENTITY" -> {
        requireSteps(values, "APPLY", "RETRY", "APPLICATIONS");
        requireHistory(sessionChanged(values), "session did not change");
        requireHistory(has(values, "APPLICATIONS", "APPLICATIONS:2"), "no double apply");
        throw new M11SemanticFailure("SESSION_CHANGED_BUSINESS_IDENTITY");
      }
      case "M11-CORRELATION-AS-IDENTITY" -> {
        requireSteps(values, "APPLY", "RETRY", "APPLICATIONS");
        requireHistory(correlationChanged(values), "correlation did not change");
        requireHistory(has(values, "APPLICATIONS", "APPLICATIONS:2"), "no double apply");
        throw new M11SemanticFailure("CORRELATION_CHANGED_BUSINESS_IDENTITY");
      }
      case "M11-RESPOND-BEFORE-BIND" -> {
        requireSteps(values, "APPLY", "RESPONSE", "CRASH", "RETRY_APPLIED");
        requireHistory(has(values, "CRASH", "CRASH:before-bind"), "crash cut changed");
        throw new M11SemanticFailure("RESPONSE_OBSERVED_BEFORE_RESULT_BIND");
      }
      case "M11-DROP-IDENTITY-FROM-SNAPSHOT" -> {
        requireSteps(values, "APPLY", "SNAPSHOT", "RESTORE", "RETRY_APPLIED");
        requireHistory(
            has(values, "SNAPSHOT", "SNAPSHOT:without-identity"), "identity was retained");
        throw new M11SemanticFailure("SNAPSHOT_LOST_IDEMPOTENCY_TABLE");
      }
      case "M11-CORRUPT-SNAPSHOT-TO-GENESIS" -> {
        requireSteps(values, "APPLY", "SNAPSHOT", "RESTORE");
        requireHistory(has(values, "RESTORE", "RESTORE:genesis"), "restore was not genesis");
        throw new M11SemanticFailure("CORRUPT_SNAPSHOT_SILENTLY_BECAME_GENESIS");
      }
      case "M11-REJECT-N-MINUS-ONE" -> {
        requireSteps(values, "DECODE_REQUEST", "DECODE_RESPONSE", "RESTORE_SNAPSHOT", "OUTCOME");
        requireHistory(
            has(values, "DECODE_REQUEST", "DECODE_REQUEST:v1")
                && has(values, "DECODE_RESPONSE", "DECODE_RESPONSE:v1")
                && has(values, "RESTORE_SNAPSHOT", "RESTORE_SNAPSHOT:s1"),
            "fixture was not N-1");
        throw new M11SemanticFailure("N_MINUS_ONE_COMPATIBILITY_REJECTED");
      }
      case "M11-INCLUDE-RUNTIME-METADATA-IN-DIGEST" -> {
        requireSteps(values, "STATE", "SESSION", "DIGEST");
        requireHistory(values.get("SESSION").size() == 2, "two sessions required");
        requireHistory(values.get("DIGEST").size() == 2, "two digests required");
        requireHistory(!values.get("DIGEST").get(0).equals(values.get("DIGEST").get(1)), "digests equal");
        throw new M11SemanticFailure("RUNTIME_METADATA_CHANGED_BUSINESS_DIGEST");
      }
      case "M11-DOUBLE-WRITE-LOCAL-WAL" -> {
        requireSteps(values, "CLUSTER_APPLY", "LOCAL_WAL_WRITES");
        requireHistory(has(values, "LOCAL_WAL_WRITES", "LOCAL_WAL_WRITES:1"), "no local WAL write");
        throw new M11SemanticFailure("CLUSTER_SERVICE_WROTE_STANDALONE_WAL");
      }
      case "M11-ACCEPT-UNSUPPORTED-VERSION" -> {
        requireSteps(values, "DECODE_REQUEST", "OUTCOME");
        requireHistory(has(values, "DECODE_REQUEST", "DECODE_REQUEST:v3"), "request was supported");
        requireHistory(has(values, "OUTCOME", "OUTCOME:accepted"), "unsupported input rejected");
        throw new M11SemanticFailure("UNSUPPORTED_VERSION_ACCEPTED");
      }
      default -> throw new IllegalArgumentException("unknown M11 mutant " + id);
    }
  }

  private static boolean sessionChanged(Map<String, List<String>> values) {
    return values.get("APPLY").stream().anyMatch(value -> value.endsWith("session-1"))
        && values.get("RETRY").stream().anyMatch(value -> value.endsWith("session-2"));
  }

  private static boolean correlationChanged(Map<String, List<String>> values) {
    return values.get("APPLY").stream().anyMatch(value -> value.endsWith("correlation-1"))
        && values.get("RETRY").stream().anyMatch(value -> value.endsWith("correlation-2"));
  }

  private static boolean has(Map<String, List<String>> values, String kind, String encoded) {
    return values.getOrDefault(kind, List.of()).contains(encoded);
  }

  private static void requireSteps(Map<String, List<String>> values, String... kinds) {
    for (String kind : kinds) {
      requireHistory(values.containsKey(kind), "missing " + kind);
    }
  }

  private static Shrink minimize(String id, List<Step> raw, String fingerprint) {
    List<Step> current = new ArrayList<>(raw);
    int trials = 0;
    boolean changed;
    do {
      changed = false;
      for (int index = 0; index < current.size(); index++) {
        List<Step> candidate = new ArrayList<>(current);
        candidate.remove(index);
        trials++;
        Replay replay = classify(id, candidate);
        if (M11CheckRunner.STUDENT_FAILURE.equals(replay.classification())
            && fingerprint.equals(replay.fingerprint())) {
          current = candidate;
          changed = true;
          break;
        }
      }
    } while (changed);
    return new Shrink(List.copyOf(current), trials);
  }

  private static boolean oneMinimal(String id, List<Step> steps, String fingerprint) {
    for (int index = 0; index < steps.size(); index++) {
      List<Step> candidate = new ArrayList<>(steps);
      candidate.remove(index);
      Replay replay = classify(id, candidate);
      if (M11CheckRunner.STUDENT_FAILURE.equals(replay.classification())
          && fingerprint.equals(replay.fingerprint())) {
        return false;
      }
    }
    return true;
  }

  private static void control(ArrayNode controls, String id, Runnable action) {
    String classification;
    try {
      action.run();
      classification = M11CheckRunner.PASS;
    } catch (RuntimeException failure) {
      classification = M11CheckRunner.SYSTEM_ERROR;
    }
    require(M11CheckRunner.SYSTEM_ERROR.equals(classification), id + " did not fail as SYSTEM_ERROR");
    ObjectNode node = controls.addObject();
    node.put("id", id);
    node.put("classification", classification);
    node.put("countedAsKill", false);
  }

  private static List<Step> steps(String... encoded) {
    return java.util.Arrays.stream(encoded).map(Step::new).toList();
  }

  private static void requireHistory(boolean condition, String message) {
    if (!condition) {
      throw new InvalidHistory(message);
    }
  }

  private static void require(boolean condition, String message) {
    if (!condition) {
      throw new IllegalStateException(message);
    }
  }

  record Step(String encoded) {
    void write(ObjectNode node) {
      node.put("kind", encoded.substring(0, encoded.indexOf(':')));
      node.put("encoded", encoded);
    }
  }

  record Replay(String classification, String fingerprint) {}

  record Shrink(List<Step> steps, int trials) {}

  record Result(
      ArrayNode candidates,
      ArrayNode controls,
      ObjectNode counterexamples,
      byte[] canonicalBytes,
      String digest,
      int killed,
      int rawActions,
      int minimalActions,
      int shrinkTrials) {
    Result {
      canonicalBytes = canonicalBytes.clone();
    }

    @Override
    public byte[] canonicalBytes() {
      return canonicalBytes.clone();
    }
  }

  private static final class InvalidHistory extends RuntimeException {
    private static final long serialVersionUID = 1L;

    InvalidHistory(String message) {
      super(message);
    }
  }
}
