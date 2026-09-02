package io.github.lchareln.cex.matching.testkit;

import io.github.lchareln.cex.matching.benchmark.FrozenPercentiles;
import io.github.lchareln.cex.matching.benchmark.QualificationProfile;
import io.github.lchareln.cex.matching.benchmark.RateMeasurement;
import io.github.lchareln.cex.matching.benchmark.RunAccounting;
import io.github.lchareln.cex.matching.benchmark.RunReconciler;
import io.github.lchareln.cex.matching.benchmark.SaturationAnalysis;
import io.github.lchareln.cex.matching.benchmark.ScheduledArrival;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Deterministic model-clock exercise of the exact qualification arithmetic used by both profiles.
 */
final class M10MethodSuite {
  static final List<Integer> LADDER = List.of(250, 500, 700, 850, 1000, 1150, 1350, 1600);
  static final List<Double> QUANTILES = List.of(0.5, 0.95, 0.99, 0.999);
  static final int CAPACITY = 64;
  private static final int SAMPLES_PER_RATE = 64;
  private static final int REFERENCE_RATE = 1_000;

  Result run() {
    require(
        QualificationProfile.CI_SMOKE.rateLadderPermille().equals(LADDER)
            && !QualificationProfile.CI_SMOKE.eligibleForReleaseEvidence()
            && "METHOD_SMOKE_ONLY".equals(QualificationProfile.CI_SMOKE.resultScope()),
        "benchmark CI profile diverged from the frozen method");
    StringBuilder arrivals = header("matching.m10.raw-arrival.v2");
    StringBuilder completions = header("matching.m10.raw-completion.v2");
    StringBuilder queues = header("matching.m10.raw-queue.v2");
    StringBuilder resources = header("matching.m10.resource-observation.v1");
    ArrayNode rates = JsonSupport.MAPPER.createArrayNode();
    List<RateResult> rateResults = new ArrayList<>();
    List<String> recoveredTokens = new ArrayList<>();

    long operation = 0;
    for (int permille : LADDER) {
      int offeredRate = Math.floorDiv(REFERENCE_RATE * permille, 1_000);
      List<Long> latency = new ArrayList<>();
      List<Long> queueDepth = new ArrayList<>();
      int admitted = 0;
      int overloaded = 0;
      int completed = 0;
      int startDepth = depth(permille, 0);
      int endDepth = startDepth;
      for (int sample = 0; sample < SAMPLES_PER_RATE; sample++) {
        String operationId = "method-" + operation++;
        long scheduled = ScheduledArrival.at(0, sample, offeredRate);
        long actual = scheduled + 1_000L + (sample % 7) * 137L;
        int depth = depth(permille, sample);
        boolean reject = permille >= 1_150 && sample % overloadDivisor(permille) == 0;
        String outcome = reject ? "REJECTED" : "ENQUEUED_NOT_ACK";
        appendArrival(
            arrivals, operationId, permille, offeredRate, scheduled, actual, outcome, depth);
        appendQueue(queues, operationId, permille, actual, depth);
        queueDepth.add((long) depth);
        endDepth = depth;
        if (reject) {
          overloaded++;
        } else {
          admitted++;
          completed++;
          long service = 5_000L + permille * 7L + sample * 19L + (long) depth * 101L;
          long terminal = actual + service;
          long fromScheduled = terminal - scheduled;
          latency.add(fromScheduled);
          recoveredTokens.add(operationId);
          appendCompletion(completions, operationId, scheduled, terminal, fromScheduled);
        }
      }
      appendResources(resources, permille, offeredRate, endDepth);
      int offers = SAMPLES_PER_RATE;
      require(offers == admitted + overloaded, "offer accounting did not reconcile");
      require(admitted == completed, "completion accounting did not reconcile");
      long p99Depth = nearestRank(queueDepth, 0.99);
      RateMeasurement benchmarkMeasurement =
          new RateMeasurement(
              offeredRate,
              CAPACITY,
              admitted,
              completed,
              overloaded,
              startDepth,
              endDepth,
              p99Depth);
      boolean saturated = SaturationAnalysis.classify(benchmarkMeasurement).saturated();
      require(p99Depth == benchmarkMeasurement.p99QueueDepth(), "queue p99 methods diverged");
      RunAccounting accounting =
          new RunAccounting(
              offers,
              admitted,
              overloaded,
              0,
              Map.of("NEW_DURABLY_APPLIED", (long) completed),
              0,
              0);
      RunReconciler.requireValid(accounting, true, completed, offers, 1);
      RateResult result =
          new RateResult(
              permille,
              offeredRate,
              offers,
              admitted,
              overloaded,
              completed,
              startDepth,
              endDepth,
              p99Depth,
              saturated,
              List.copyOf(latency),
              List.copyOf(queueDepth));
      rateResults.add(result);
      rates.add(rateJson(result));
    }

    int knee = findKnee(rateResults);
    int qopCandidate = Math.floorDiv(knee * 70, 100);
    int qop =
        rateResults.stream()
            .filter(rate -> rate.offeredRate() <= qopCandidate && !rate.saturated())
            .mapToInt(RateResult::offeredRate)
            .max()
            .orElseThrow();
    require(knee == 1_150, "method fixture knee changed");
    require(qopCandidate == 805, "method fixture QOP candidate changed");
    require(qop == 700, "method fixture selected measured QOP changed");
    require(
        rateResults.stream()
            .filter(rate -> rate.offeredRate() > knee)
            .allMatch(RateResult::saturated),
        "above-knee measurements lost explicit saturation");

    ObjectNode method = JsonSupport.MAPPER.createObjectNode();
    method.put("schemaVersion", "matching.m10.method-smoke.v1");
    method.put("profileId", "CI_SMOKE");
    method.put("resultScope", "METHOD_SMOKE_ONLY");
    method.put("eligibleForReleaseEvidence", false);
    method.put("evidenceMode", "MODEL_ONLY");
    method.put("methodIsomorphic", false);
    method.put("clock", "DETERMINISTIC_MODEL_CLOCK");
    method.put("performanceClaim", false);
    method.put("latencyOrigin", "SCHEDULED_ARRIVAL");
    method.put("percentileRankRule", FrozenPercentiles.RANK_RULE);
    method.set("rates", rates);
    method.put("sweepKnee", knee);
    method.put("publishedKnee", knee);
    method.put("qualifiedOperatingPointCandidate", qopCandidate);
    method.put("qualifiedOperatingPoint", qop);
    method.put("releaseSweepsClaimed", 0);
    method.put("releaseSoakSecondsClaimed", 0);
    method.put("smokeSoakSeconds", 3);
    method.put("scheduledArrivalSamples", operation);
    method.put("completionSamples", recoveredTokens.size());
    method.put("queueSamples", operation);
    method.put("resourceSeries", LADDER.size());
    method.put("resourceDimensionsPresent", true);
    method.put("aboveKneeRetained", true);

    ObjectNode reconciliation = JsonSupport.MAPPER.createObjectNode();
    reconciliation.put("schemaVersion", "matching.m10.reconciliation.v1");
    reconciliation.put("scope", "CI_SMOKE_METHOD_MODEL");
    reconciliation.put("offers", operation);
    int admittedTotal = rateResults.stream().mapToInt(RateResult::admitted).sum();
    int overloadedTotal = rateResults.stream().mapToInt(RateResult::overloaded).sum();
    int completedTotal = rateResults.stream().mapToInt(RateResult::completed).sum();
    reconciliation.put("admitted", admittedTotal);
    reconciliation.put("overloaded", overloadedTotal);
    reconciliation.put("closedOrInvalid", 0);
    reconciliation.put("submissionResultCompletions", completedTotal);
    reconciliation.put("explicitServiceFailures", 0);
    reconciliation.put("terminalPending", 0);
    reconciliation.put("offerEquation", operation == admittedTotal + overloadedTotal);
    reconciliation.put("completionEquation", admittedTotal == completedTotal);

    ObjectNode recovery = JsonSupport.MAPPER.createObjectNode();
    recovery.put("schemaVersion", "matching.m10.load-recovery.v1");
    recovery.put("scope", "DETERMINISTIC_METHOD_MODEL");
    String digest =
        Hashing.semanticDigest(String.join("\n", recoveredTokens).getBytes(StandardCharsets.UTF_8));
    recovery.put("liveDigest", digest);
    recovery.put("recoveredDigest", digest);
    recovery.put("exact", true);
    recovery.put("productionRuntimeClaim", false);

    return new Result(
        method,
        reconciliation,
        recovery,
        arrivals.toString().getBytes(StandardCharsets.UTF_8),
        completions.toString().getBytes(StandardCharsets.UTF_8),
        queues.toString().getBytes(StandardCharsets.UTF_8),
        resources.toString().getBytes(StandardCharsets.UTF_8),
        operation,
        recoveredTokens.size(),
        knee,
        qopCandidate,
        qop);
  }

  static long nearestRank(List<Long> samples, double quantile) {
    return FrozenPercentiles.nearestRank(samples, quantile);
  }

  static int findKnee(List<RateResult> rates) {
    List<RateMeasurement> measurements =
        rates.stream()
            .map(
                rate ->
                    new RateMeasurement(
                        rate.offeredRate(),
                        CAPACITY,
                        rate.admitted(),
                        rate.completed(),
                        rate.overloaded(),
                        rate.startDepth(),
                        rate.endDepth(),
                        rate.p99Depth()))
            .toList();
    return Math.toIntExact(SaturationAnalysis.perSweepKnee(measurements));
  }

  private static int depth(int permille, int sample) {
    int ceiling =
        switch (permille) {
          case 250 -> 4;
          case 500 -> 10;
          case 700 -> 22;
          case 850 -> 34;
          case 1000 -> 48;
          case 1150 -> 56;
          case 1350 -> 62;
          case 1600 -> 64;
          default -> throw new IllegalArgumentException("unknown ladder point");
        };
    return Math.min(ceiling, Math.floorMod(sample * 13 + permille, ceiling + 1));
  }

  private static int overloadDivisor(int permille) {
    return switch (permille) {
      case 1150 -> 16;
      case 1350 -> 8;
      case 1600 -> 4;
      default -> Integer.MAX_VALUE;
    };
  }

  private static StringBuilder header(String schema) {
    return new StringBuilder()
        .append("{\"schemaVersion\":\"")
        .append(schema)
        .append(
            "\",\"recordType\":\"HEADER\",\"runId\":\"m10-ci-method-6010\",\"profileId\":\"CI_SMOKE\",\"resultScope\":\"METHOD_SMOKE_ONLY\",\"eligibleForReleaseEvidence\":false}\n");
  }

  private static void appendArrival(
      StringBuilder target,
      String operationId,
      int permille,
      int offeredRate,
      long scheduled,
      long actual,
      String outcome,
      int queueDepth) {
    target.append(
        String.format(
            Locale.ROOT,
            "{\"recordType\":\"ARRIVAL\",\"operationId\":\"%s\",\"attempt\":0,\"attemptKind\":\"INITIAL_SCHEDULED\",\"phase\":\"MEASURE\",\"sweep\":0,\"ladderPermille\":%d,\"offeredRate\":%d,\"scheduledArrivalNanos\":%d,\"admissionDecisionNanos\":%d,\"admissionOutcome\":\"%s\",\"observationKind\":\"ADMISSION_GATE_DECISION\",\"decisionQueueDepth\":%d}\n",
            operationId,
            permille,
            offeredRate,
            scheduled,
            actual,
            outcome,
            queueDepth));
  }

  private static void appendCompletion(
      StringBuilder target, String operationId, long scheduled, long terminal, long latency) {
    target.append(
        String.format(
            Locale.ROOT,
            "{\"recordType\":\"COMPLETION\",\"operationId\":\"%s\",\"attempt\":0,\"timeOrigin\":\"OWNER_COMPLETED_UNDER_GATE\",\"scheduledArrivalNanos\":%d,\"ownerCompletedNanos\":%d,\"latencyFromScheduledNanos\":%d,\"completionKind\":\"SUBMISSION_RESULT\",\"submissionResultVariant\":\"NEW_DURABLY_APPLIED\"}\n",
            operationId,
            scheduled,
            terminal,
            latency));
  }

  private static void appendQueue(
      StringBuilder target, String operationId, int permille, long observed, int depth) {
    target.append(
        String.format(
            Locale.ROOT,
            "{\"recordType\":\"QUEUE\",\"operationId\":\"%s\",\"attempt\":0,\"attemptKind\":\"INITIAL_SCHEDULED\",\"ladderPermille\":%d,\"admissionDecisionNanos\":%d,\"observationKind\":\"ADMISSION_GATE_DECISION\",\"decisionQueueDepth\":%d,\"capacity\":64}\n",
            operationId,
            permille,
            observed,
            depth));
  }

  private static void appendResources(
      StringBuilder target, int permille, int offeredRate, int queueDepth) {
    target.append(
        String.format(
            Locale.ROOT,
            "{\"recordType\":\"RESOURCE\",\"ladderPermille\":%d,\"offeredRate\":%d,\"elapsedNanos\":%d,\"allocatedBytes\":%d,\"gcCount\":%d,\"gcMillis\":%d,\"processCpuNanos\":%d,\"heapUsedBytes\":%d,\"committedVirtualMemoryBytes\":%d,\"queueDepth\":%d,\"units\":{\"allocation\":\"bytes\",\"gcTime\":\"milliseconds\",\"cpu\":\"nanoseconds\",\"memory\":\"bytes\",\"queueDepth\":\"items\"}}\n",
            permille,
            offeredRate,
            permille * 1_000_000L,
            permille * 4_096L,
            permille / 500,
            permille / 250,
            permille * 300_000L,
            64_000_000L + permille * 1_024L,
            512_000_000L,
            queueDepth));
  }

  private static ObjectNode rateJson(RateResult rate) {
    ObjectNode node = JsonSupport.MAPPER.createObjectNode();
    node.put("ladderPermille", rate.permille());
    node.put("offeredRate", rate.offeredRate());
    node.put("offers", rate.offers());
    node.put("admitted", rate.admitted());
    node.put("overloaded", rate.overloaded());
    node.put("completed", rate.completed());
    node.put("startQueueDepth", rate.startDepth());
    node.put("endQueueDepth", rate.endDepth());
    node.put("p99QueueDepth", rate.p99Depth());
    node.put("saturated", rate.saturated());
    ObjectNode published = node.putObject("latencyNanos");
    for (double quantile : QUANTILES) {
      long value = nearestRank(rate.latency(), quantile);
      published.put(label(quantile), value);
    }
    node.put("rawLatencySamples", rate.latency().size());
    node.put("percentilesRecomputed", true);
    return node;
  }

  private static String label(double quantile) {
    return switch ((int) Math.round(quantile * 1_000)) {
      case 500 -> "p50";
      case 950 -> "p95";
      case 990 -> "p99";
      case 999 -> "p99_9";
      default -> throw new IllegalArgumentException("unknown quantile");
    };
  }

  private static void require(boolean condition, String message) {
    if (!condition) {
      throw new M10SemanticFailure(message);
    }
  }

  record RateResult(
      int permille,
      int offeredRate,
      int offers,
      int admitted,
      int overloaded,
      int completed,
      int startDepth,
      int endDepth,
      long p99Depth,
      boolean saturated,
      List<Long> latency,
      List<Long> queueDepth) {}

  record Result(
      ObjectNode method,
      ObjectNode reconciliation,
      ObjectNode recovery,
      byte[] arrivals,
      byte[] completions,
      byte[] queues,
      byte[] resources,
      long scheduledArrivals,
      long completionSamples,
      int knee,
      int qopCandidate,
      int qop) {
    Result {
      arrivals = arrivals.clone();
      completions = completions.clone();
      queues = queues.clone();
      resources = resources.clone();
    }

    @Override
    public byte[] arrivals() {
      return arrivals.clone();
    }

    @Override
    public byte[] completions() {
      return completions.clone();
    }

    @Override
    public byte[] queues() {
      return queues.clone();
    }

    @Override
    public byte[] resources() {
      return resources.clone();
    }
  }
}
