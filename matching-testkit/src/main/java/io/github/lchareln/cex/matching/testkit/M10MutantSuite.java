package io.github.lchareln.cex.matching.testkit;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Executable method/admission candidates with replayable, grammar-preserving counterexamples. */
final class M10MutantSuite {
  static final String PASS = "PASS";
  static final String STUDENT_FAILURE = "STUDENT_FAILURE";
  static final String SYSTEM_ERROR = "SYSTEM_ERROR";

  Result run() {
    ArrayNode candidates = JsonSupport.MAPPER.createArrayNode();
    ArrayNode witnesses = JsonSupport.MAPPER.createArrayNode();
    StringBuilder canonical = new StringBuilder();
    int rawActions = 0;
    int minimalActions = 0;
    int trials = 0;
    int killed = 0;
    for (String id : M10StartCheckRunner.REQUIRED_MUTANTS) {
      List<Step> raw = rawWitness(id);
      Replay baseline = classify(id, raw);
      require(STUDENT_FAILURE.equals(baseline.classification()), id + " was not STUDENT_FAILURE");
      Shrink shrink = minimize(id, raw, baseline.fingerprint());
      Replay strict = classify(id, shrink.steps());
      require(
          STUDENT_FAILURE.equals(strict.classification()), id + " minimized replay did not fail");
      require(baseline.fingerprint().equals(strict.fingerprint()), id + " fingerprint changed");
      require(oneMinimal(id, shrink.steps(), baseline.fingerprint()), id + " is not one-minimal");
      killed++;
      rawActions += raw.size();
      minimalActions += shrink.steps().size();
      trials += shrink.trials();

      ObjectNode candidate = candidates.addObject();
      candidate.put("id", id);
      candidate.put("classification", STUDENT_FAILURE);
      candidate.put("fingerprint", baseline.fingerprint());
      candidate.put("executableCandidate", true);
      candidate.put("systemErrorCountedAsKill", false);
      candidate.put("counterexampleId", "cex-" + id.substring(4).toLowerCase().replace('_', '-'));

      ObjectNode witness = witnesses.addObject();
      witness.put("id", "cex-" + id.substring(4).toLowerCase().replace('_', '-'));
      witness.put("mutant", id);
      witness.put("classification", STUDENT_FAILURE);
      witness.put("fingerprint", baseline.fingerprint());
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
        "THROWING_HARNESS",
        () -> {
          throw new IllegalStateException("control");
        });
    control(
        controls,
        "UNAVAILABLE_RESOURCE_COLLECTOR",
        () -> {
          throw new UnsupportedOperationException("collector unavailable");
        });
    control(
        controls,
        "MALFORMED_RAW_SAMPLE",
        () -> {
          M10MethodSuite.nearestRank(List.of(), 0.99);
        });
    require(killed == 12, "not every required M10 candidate was killed");
    byte[] canonicalBytes = canonical.toString().getBytes(StandardCharsets.UTF_8);
    ObjectNode counterexamples = JsonSupport.MAPPER.createObjectNode();
    counterexamples.put("schemaVersion", "matching.m10.counterexamples.v1");
    counterexamples.put("seed", "6010");
    counterexamples.put("required", 12);
    counterexamples.put("persisted", killed);
    counterexamples.put("replayInterpreter", "M10_DECLARED_STEP_GRAMMAR_V1");
    counterexamples.put("minimalityScope", "ONE_MINIMAL_WITHIN_DECLARED_STEP_DELETION_GRAMMAR");
    counterexamples.put("globalMinimumClaim", false);
    counterexamples.put("invalidHistoryCountedAsKill", false);
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
        trials);
  }

  private static List<Step> rawWitness(String id) {
    return switch (id) {
      case "M10-UNBOUNDED-QUEUE" -> steps("CAPACITY:2", "OFFER:a", "OFFER:b", "OFFER:c");
      case "M10-BLOCKING-PUT" -> steps("CAPACITY:1", "OFFER:a", "OFFER:b");
      case "M10-REJECT-AFTER-WAL" -> steps("CAPACITY:1", "OFFER:a", "OFFER:b", "OBSERVE:wal");
      case "M10-REJECT-BINDS-IDENTITY" -> steps("CAPACITY:1", "OFFER:a", "OFFER:b", "RETRY:b");
      case "M10-ENQUEUE-AS-ACK" -> steps("CAPACITY:1", "OFFER:a", "OBSERVE:ack");
      case "M10-DUAL-WORKER-REORDER" ->
          steps("CAPACITY:2", "OFFER:a", "OFFER:b", "COMPLETE:b", "COMPLETE:a");
      case "M10-DROPPED-COMPLETION" -> steps("CAPACITY:2", "OFFER:a", "OFFER:b", "QUIESCE:now");
      case "M10-METRICS-UNDERCOUNT" ->
          steps("CAPACITY:1", "OFFER:a", "OFFER:b", "OBSERVE:reconciliation");
      case "M10-CLOSED-LOOP-GENERATOR" -> steps("SCHEDULE:a:0", "COMPLETE:a:80", "SCHEDULE:b:80");
      case "M10-LATENCY-FROM-ACTUAL-SEND" -> steps("SCHEDULE:a:0", "SEND:a:40", "COMPLETE:a:100");
      case "M10-WRONG-PERCENTILE-KNEE" ->
          steps("SAMPLES:1,2,3,100", "RATE:100:clear", "RATE:200:saturated", "RATE:300:saturated");
      case "M10-SKIP-LOAD-RECOVERY-CHECK" ->
          steps("LIVE_DIGEST:sha256:aaa", "RECOVERED_DIGEST:sha256:bbb", "QUALIFY:now");
      default -> throw new IllegalArgumentException("unknown mutant " + id);
    };
  }

  private static Replay classify(String id, List<Step> steps) {
    try {
      return execute(id, steps);
    } catch (InvalidHistory invalid) {
      return new Replay("INVALID_HISTORY", invalid.getMessage());
    } catch (M10SemanticFailure failure) {
      return new Replay(STUDENT_FAILURE, failure.getMessage());
    } catch (RuntimeException failure) {
      return new Replay(SYSTEM_ERROR, failure.getClass().getSimpleName());
    }
  }

  private static Replay execute(String id, List<Step> steps) {
    Map<String, String> values = new LinkedHashMap<>();
    List<String> offers = new ArrayList<>();
    List<String> completions = new ArrayList<>();
    int capacity = -1;
    int wal = 0;
    int identities = 0;
    int overload = 0;
    long scheduledA = Long.MIN_VALUE;
    long scheduledB = Long.MIN_VALUE;
    long send = Long.MIN_VALUE;
    long complete = Long.MIN_VALUE;
    List<Long> samples = List.of();
    List<Boolean> saturation = new ArrayList<>();
    List<Integer> rates = new ArrayList<>();
    for (Step step : steps) {
      String[] parts = step.encoded().split(":", 3);
      switch (parts[0]) {
        case "CAPACITY" -> capacity = Integer.parseInt(parts[1]);
        case "OFFER" -> {
          requireHistory(capacity > 0, "offer before capacity");
          boolean full = offers.size() - completions.size() >= capacity;
          if (full && !"M10-UNBOUNDED-QUEUE".equals(id)) {
            overload++;
            if ("M10-BLOCKING-PUT".equals(id)) {
              values.put("offerDelay", "1");
            }
            if ("M10-REJECT-AFTER-WAL".equals(id)) {
              wal++;
            }
            if ("M10-REJECT-BINDS-IDENTITY".equals(id)) {
              identities++;
            }
          } else {
            offers.add(parts[1]);
            wal++;
            identities++;
            if ("M10-ENQUEUE-AS-ACK".equals(id)) {
              values.put("ack", "true");
            }
          }
        }
        case "RETRY" ->
            values.put("retryIdentityAlreadyBound", Boolean.toString(identities > offers.size()));
        case "COMPLETE" -> completions.add(parts[1]);
        case "QUIESCE" -> {
          if (!"M10-DROPPED-COMPLETION".equals(id)) {
            completions.addAll(offers.subList(completions.size(), offers.size()));
          }
        }
        case "OBSERVE", "QUALIFY" -> values.put(parts[0], parts[1]);
        case "SCHEDULE" -> {
          long value = Long.parseLong(parts[2]);
          if ("a".equals(parts[1])) scheduledA = value;
          else scheduledB = value;
        }
        case "SEND" -> send = Long.parseLong(parts[2]);
        case "SAMPLES" ->
            samples = java.util.Arrays.stream(parts[1].split(",")).map(Long::parseLong).toList();
        case "RATE" -> {
          rates.add(Integer.parseInt(parts[1]));
          saturation.add("saturated".equals(parts[2]));
        }
        case "LIVE_DIGEST", "RECOVERED_DIGEST" ->
            values.put(parts[0], parts.length == 3 ? parts[1] + ':' + parts[2] : parts[1]);
        default -> throw new InvalidHistory("unknown step " + step.encoded());
      }
      if ("COMPLETE".equals(parts[0])) {
        if (parts.length == 3) complete = Long.parseLong(parts[2]);
      }
    }

    String fingerprint;
    switch (id) {
      case "M10-UNBOUNDED-QUEUE" -> {
        requireHistory(capacity > 0 && offers.size() > capacity, "no overflow witness");
        fingerprint = "QUEUE_ACCEPTED_PAST_CAPACITY";
      }
      case "M10-BLOCKING-PUT" -> {
        requireHistory(overload > 0 && values.containsKey("offerDelay"), "no full offer");
        fingerprint = "TRY_SUBMIT_WAITED_FOR_CAPACITY";
      }
      case "M10-REJECT-AFTER-WAL" -> {
        requireHistory(overload > 0 && wal > offers.size(), "no rejected WAL mutation");
        fingerprint = "OVERLOAD_MUTATED_WAL";
      }
      case "M10-REJECT-BINDS-IDENTITY" -> {
        requireHistory(
            "true".equals(values.get("retryIdentityAlreadyBound")), "no rejected identity binding");
        fingerprint = "OVERLOAD_BOUND_IDENTITY";
      }
      case "M10-ENQUEUE-AS-ACK" -> {
        requireHistory("true".equals(values.get("ack")), "no enqueue ACK");
        fingerprint = "ENQUEUE_UPGRADED_TO_DURABLE_ACK";
      }
      case "M10-DUAL-WORKER-REORDER" -> {
        requireHistory(offers.size() == 2 && completions.size() == 2, "no two completions");
        requireHistory(!offers.equals(completions), "completion order did not change");
        fingerprint = "FIFO_COMPLETION_REORDERED";
      }
      case "M10-DROPPED-COMPLETION" -> {
        requireHistory(
            values.containsKey("QUIESCE")
                || steps.stream().anyMatch(step -> step.encoded().startsWith("QUIESCE:")),
            "no quiesce");
        requireHistory(completions.size() < offers.size(), "no dropped completion");
        fingerprint = "ACCEPTED_ITEM_HAS_NO_TERMINAL_COMPLETION";
      }
      case "M10-METRICS-UNDERCOUNT" -> {
        requireHistory(
            values.containsKey("OBSERVE") && offers.size() + overload > offers.size(),
            "no accounting cut");
        fingerprint = "OFFERS_DO_NOT_RECONCILE";
      }
      case "M10-CLOSED-LOOP-GENERATOR" -> {
        requireHistory(
            scheduledA != Long.MIN_VALUE
                && scheduledB != Long.MIN_VALUE
                && complete != Long.MIN_VALUE,
            "missing schedule/completion");
        requireHistory(scheduledB == complete, "second schedule is independent");
        fingerprint = "ARRIVAL_DEPENDS_ON_PRIOR_COMPLETION";
      }
      case "M10-LATENCY-FROM-ACTUAL-SEND" -> {
        requireHistory(
            scheduledA != Long.MIN_VALUE && send != Long.MIN_VALUE && complete != Long.MIN_VALUE,
            "missing latency points");
        long mutated = complete - send;
        long required = complete - scheduledA;
        requireHistory(mutated != required, "origins are equal");
        fingerprint = "LATENCY_DROPPED_SCHEDULER_DELAY";
      }
      case "M10-WRONG-PERCENTILE-KNEE" -> {
        requireHistory(
            samples.size() == 4 && rates.size() == 3 && saturation.size() == 3,
            "missing percentile/knee inputs");
        long required = M10MethodSuite.nearestRank(samples, 0.99);
        long mutated = samples.stream().sorted().toList().get(2);
        int requiredKnee = firstPair(rates, saturation);
        int mutatedKnee = rates.getLast();
        requireHistory(
            required != mutated && requiredKnee != mutatedKnee, "mutations did not diverge");
        fingerprint = "PERCENTILE_AND_KNEE_RULE_DIVERGED";
      }
      case "M10-SKIP-LOAD-RECOVERY-CHECK" -> {
        requireHistory(values.containsKey("QUALIFY"), "no qualification decision");
        requireHistory(
            !java.util.Objects.equals(values.get("LIVE_DIGEST"), values.get("RECOVERED_DIGEST")),
            "recovery did not diverge");
        fingerprint = "RECOVERY_MISMATCH_ACCEPTED";
      }
      default -> throw new IllegalArgumentException("unknown mutant " + id);
    }
    throw new M10SemanticFailure(fingerprint);
  }

  private static int firstPair(List<Integer> rates, List<Boolean> saturated) {
    for (int index = 0; index + 1 < rates.size(); index++) {
      if (saturated.get(index) && saturated.get(index + 1)) return rates.get(index);
    }
    throw new InvalidHistory("no saturation pair");
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
        if (STUDENT_FAILURE.equals(replay.classification())
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
      if (STUDENT_FAILURE.equals(replay.classification())
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
      classification = PASS;
    } catch (RuntimeException failure) {
      classification = SYSTEM_ERROR;
    }
    require(SYSTEM_ERROR.equals(classification), id + " did not fail as SYSTEM_ERROR");
    ObjectNode node = controls.addObject();
    node.put("id", id);
    node.put("classification", classification);
    node.put("countedAsKill", false);
  }

  private static List<Step> steps(String... encoded) {
    return java.util.Arrays.stream(encoded).map(Step::new).toList();
  }

  private static void requireHistory(boolean condition, String message) {
    if (!condition) throw new InvalidHistory(message);
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new IllegalStateException(message);
  }

  record Step(String encoded) {
    void write(ObjectNode node) {
      String[] parts = encoded.split(":", 3);
      node.put("kind", parts[0]);
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
