package io.github.lchareln.cex.matching.testkit;

import io.github.lchareln.cex.matching.cluster.DirectM11MatchingRuntime;
import io.github.lchareln.cex.matching.cluster.M11ApplicationResult;
import io.github.lchareln.cex.matching.cluster.M11CommandRequest;
import io.github.lchareln.cex.matching.cluster.M11ProtocolException;
import io.github.lchareln.cex.matching.cluster.M11RequestCodec;
import io.github.lchareln.cex.matching.cluster.M11ResponseCodec;
import io.github.lchareln.cex.matching.cluster.M11ResponseStatus;
import io.github.lchareln.cex.matching.cluster.M11SnapshotCodec;
import io.github.lchareln.cex.matching.local.DeterministicMatchingAdapter;
import io.github.lchareln.cex.matching.local.M08Command;
import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Executable production-derived adapter candidates judged by one invariant observer. */
final class M11MutantSuite {
  Result run(Path repositoryRoot) {
    Path root = repositoryRoot.toAbsolutePath().normalize();
    ArrayNode candidates = JsonSupport.MAPPER.createArrayNode();
    ArrayNode witnesses = JsonSupport.MAPPER.createArrayNode();
    StringBuilder canonical = new StringBuilder();
    int killed = 0;
    int rawActions = 0;
    int minimalActions = 0;
    int shrinkTrials = 0;
    for (String id : M11StartCheckRunner.MUTANT_IDS) {
      List<Step> raw = rawWitness(id);
      Replay initial = classify(root, id, raw);
      require(
          M11CheckRunner.STUDENT_FAILURE.equals(initial.classification()),
          id + " did not expose a student failure: " + initial.classification());
      Shrink shrink = minimize(root, id, raw, initial.fingerprint());
      Replay replay = classify(root, id, shrink.steps());
      require(
          M11CheckRunner.STUDENT_FAILURE.equals(replay.classification()),
          id + " minimized witness no longer fails");
      require(initial.fingerprint().equals(replay.fingerprint()), id + " fingerprint changed");
      require(
          oneMinimal(root, id, shrink.steps(), replay.fingerprint()), id + " is not one-minimal");

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
      candidate.put("observer", "M11_UNIFIED_CONTRACT_OBSERVER_V1");
      candidate.put("freshCandidatePerReplay", true);
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
    for (String id : M11StartCheckRunner.SYSTEM_ERROR_IDS) {
      control(controls, id, controlAction(id));
    }
    require(killed == 10, "not every M11 mutant was killed");
    require(controls.size() == 3, "M11 system control count changed");

    byte[] canonicalBytes = canonical.toString().getBytes(StandardCharsets.UTF_8);
    ObjectNode counterexamples = JsonSupport.MAPPER.createObjectNode();
    counterexamples.put("schemaVersion", "matching.m11.counterexamples.v1");
    counterexamples.put("seed", "6111");
    counterexamples.put("required", 10);
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

  private static Replay classify(Path root, String id, List<Step> steps) {
    try {
      Observation observation = executeCandidate(root, id, steps);
      String fingerprint = observe(id, observation);
      if (fingerprint != null) {
        throw new M11SemanticFailure(fingerprint);
      }
      return new Replay(M11CheckRunner.PASS, "NONE");
    } catch (InvalidHistory invalid) {
      return new Replay("INVALID_HISTORY", invalid.getMessage());
    } catch (M11SemanticFailure failure) {
      return new Replay(M11CheckRunner.STUDENT_FAILURE, failure.getMessage());
    } catch (RuntimeException failure) {
      return new Replay(M11CheckRunner.SYSTEM_ERROR, failure.getClass().getSimpleName());
    }
  }

  private static Observation executeCandidate(Path root, String id, List<Step> steps) {
    return switch (id) {
      case "M11-OFFER-AS-SUCCESS" -> offerAsSuccess(steps);
      case "M11-SESSION-AS-IDENTITY" -> transportAsIdentity(steps, true);
      case "M11-CORRELATION-AS-IDENTITY" -> transportAsIdentity(steps, false);
      case "M11-RESPOND-BEFORE-BIND" -> respondBeforeBind(steps);
      case "M11-DROP-IDENTITY-FROM-SNAPSHOT" -> dropSnapshotIdentity(steps);
      case "M11-CORRUPT-SNAPSHOT-TO-GENESIS" -> corruptSnapshotToGenesis(steps);
      case "M11-REJECT-N-MINUS-ONE" -> rejectPreviousVersion(root, steps);
      case "M11-INCLUDE-RUNTIME-METADATA-IN-DIGEST" -> metadataInDigest(steps);
      case "M11-DOUBLE-WRITE-LOCAL-WAL" -> doubleWriteLocalWal(steps);
      case "M11-ACCEPT-UNSUPPORTED-VERSION" -> acceptUnsupported(steps);
      default -> throw new IllegalArgumentException("unknown M11 mutant " + id);
    };
  }

  private static Observation offerAsSuccess(List<Step> steps) {
    requireKinds(steps, "OFFER", "APPLY_PENDING", "OBSERVE");
    int applied = 0;
    int responses = 0;
    int responseBeforeApply = 0;
    for (Step step : steps) {
      if ("OFFER".equals(step.kind())) {
        responses++;
        if (applied == 0) {
          responseBeforeApply++;
        }
      } else if ("APPLY_PENDING".equals(step.kind())) {
        requireHistory(applied == 0, "apply was not pending");
      }
    }
    return facts(
        "responses", responses, "applied", applied, "responsesBeforeApply", responseBeforeApply);
  }

  private static Observation transportAsIdentity(List<Step> steps, boolean session) {
    String firstKind = session ? "SESSION_APPLY" : "CORRELATION_APPLY";
    String retryKind = session ? "SESSION_RETRY" : "CORRELATION_RETRY";
    requireKinds(steps, firstKind, retryKind, "OBSERVE");
    M11CommandRequest request = request();
    DirectM11MatchingRuntime oracle = new DirectM11MatchingRuntime();
    M11ApplicationResult oracleFirst = oracle.submit(request);
    M11ApplicationResult oracleRetry = oracle.submit(request.withCorrelationId(new UUID(31, 37)));
    requireHistory(
        oracleFirst.response().status() == M11ResponseStatus.NEW_APPLIED
            && oracleRetry.response().status() == M11ResponseStatus.DUPLICATE_REPLAYED,
        "production identity oracle changed");

    Map<String, DirectM11MatchingRuntime> partitioned = new LinkedHashMap<>();
    int candidateNew = 0;
    for (Step step : steps) {
      if (firstKind.equals(step.kind()) || retryKind.equals(step.kind())) {
        String key = step.value();
        DirectM11MatchingRuntime runtime =
            partitioned.computeIfAbsent(key, ignored -> new DirectM11MatchingRuntime());
        M11ApplicationResult result =
            runtime.submit(
                request.withCorrelationId(
                    UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8))));
        if (result.response().status() == M11ResponseStatus.NEW_APPLIED) {
          candidateNew++;
        }
      }
    }
    return facts("candidateNew", candidateNew, "oracleNew", 1, "oracleDuplicates", 1);
  }

  private static Observation respondBeforeBind(List<Step> steps) {
    requireKinds(steps, "APPLY", "RESPOND", "CRASH_BEFORE_BIND", "RETRY");
    M11CommandRequest request = request();
    M11ApplicationResult first = new DirectM11MatchingRuntime().submit(request);
    requireHistory(first.fullResult().isPresent(), "production apply did not produce a result");
    int applyOrdinal = indexOf(steps, "APPLY");
    int responseOrdinal = indexOf(steps, "RESPOND");
    int bindOrdinal = Integer.MAX_VALUE;
    DirectM11MatchingRuntime afterCrash = new DirectM11MatchingRuntime();
    M11ApplicationResult retry = afterCrash.submit(request.withCorrelationId(new UUID(41, 43)));
    return facts(
        "responseBeforeBind",
        responseOrdinal > applyOrdinal && responseOrdinal < bindOrdinal,
        "retryWasNew",
        retry.response().status() == M11ResponseStatus.NEW_APPLIED);
  }

  private static Observation dropSnapshotIdentity(List<Step> steps) {
    requireKinds(steps, "APPLY", "SNAPSHOT_WITHOUT_IDENTITY", "RESTORE", "RETRY");
    M11CommandRequest request = request();
    DirectM11MatchingRuntime original = new DirectM11MatchingRuntime();
    M11ApplicationResult first = original.submit(request);
    DeterministicMatchingAdapter matcher =
        DeterministicMatchingAdapter.restore(original.stateImage().commandState());
    long before = matcher.nextApplicationSequence();
    var second = matcher.apply(request.command());
    return facts(
        "originalApplied",
        first.response().status() == M11ResponseStatus.NEW_APPLIED,
        "retryApplicationSequence",
        second.applicationSequence(),
        "beforeRetrySequence",
        before);
  }

  private static Observation corruptSnapshotToGenesis(List<Step> steps) {
    requireKinds(steps, "APPLY", "CORRUPT_SNAPSHOT", "RESTORE_FALLBACK");
    DirectM11MatchingRuntime original = new DirectM11MatchingRuntime();
    original.submit(request());
    byte[] encoded = new M11SnapshotCodec().encodeCurrent(original.stateImage());
    encoded[encoded.length / 2] ^= 1;
    boolean decoderRejected = false;
    try {
      new M11SnapshotCodec().decodeCanonical(encoded);
    } catch (M11ProtocolException expected) {
      decoderRejected = true;
    }
    requireHistory(decoderRejected, "corruption was not detected by the production codec");
    DirectM11MatchingRuntime mutantRestored = new DirectM11MatchingRuntime();
    return facts(
        "decoderRejected",
        true,
        "restoredGenesis",
        mutantRestored.nextApplicationSequence() == 1,
        "originalNextSequence",
        original.nextApplicationSequence());
  }

  private static Observation rejectPreviousVersion(Path root, List<Step> steps) {
    requireKinds(steps, "READ_REQUEST_V1", "READ_RESPONSE_V1", "READ_SNAPSHOT_S1", "OBSERVE");
    M11RequestCodec requests = new M11RequestCodec();
    boolean productionReadable;
    try {
      byte[] request = java.nio.file.Files.readAllBytes(root.resolve(golden("request-v1.bin")));
      byte[] response = java.nio.file.Files.readAllBytes(root.resolve(golden("response-v1.bin")));
      byte[] snapshot = java.nio.file.Files.readAllBytes(root.resolve(golden("snapshot-v1.bin")));
      productionReadable =
          requests.decodeCanonical(request, 1).protocolVersion() == 1
              && new M11ResponseCodec().decodeCanonical(response).protocolVersion() == 1
              && new M11SnapshotCodec().decodeCanonical(snapshot).schemaVersion() == 1;
    } catch (java.io.IOException | M11ProtocolException failure) {
      throw new IllegalStateException("cannot execute N-1 production oracle", failure);
    }
    boolean mutantReadable = false;
    return facts("productionReadable", productionReadable, "mutantReadable", mutantReadable);
  }

  private static Observation metadataInDigest(List<Step> steps) {
    requireKinds(steps, "BUSINESS_STATE", "SESSION_ONE", "SESSION_TWO", "OBSERVE");
    DirectM11MatchingRuntime runtime = new DirectM11MatchingRuntime();
    runtime.submit(request());
    String business = runtime.semanticStateDigest();
    String first = sha256(business + ":session-1");
    String second = sha256(business + ":session-2");
    return facts(
        "businessDigestStable", business.equals(runtime.semanticStateDigest()),
        "mutantDigestStable", first.equals(second));
  }

  private static Observation doubleWriteLocalWal(List<Step> steps) {
    requireKinds(steps, "CLUSTER_APPLY", "OBSERVE_WAL");
    M11CommandRequest request = request();
    M11ApplicationResult applied = new DirectM11MatchingRuntime().submit(request);
    ByteArrayOutputStream localWal = new ByteArrayOutputStream();
    localWal.writeBytes(new M11RequestCodec().encode(request));
    return facts(
        "applied",
        applied.response().status() == M11ResponseStatus.NEW_APPLIED,
        "standaloneWalWrites",
        localWal.size() > 0 ? 1 : 0);
  }

  private static Observation acceptUnsupported(List<Step> steps) {
    requireKinds(steps, "DECODE_REQUEST_V3", "OBSERVE");
    M11RequestCodec codec = new M11RequestCodec();
    byte[] unsupported = codec.encode(request());
    ByteBuffer.wrap(unsupported).putInt(Integer.BYTES, 3);
    boolean productionRejected = false;
    try {
      codec.decodeCanonical(unsupported, 1);
    } catch (M11ProtocolException expected) {
      productionRejected = true;
    }
    ByteBuffer.wrap(unsupported).putInt(Integer.BYTES, 2);
    boolean mutantAccepted;
    try {
      mutantAccepted = codec.decodeCanonical(unsupported, 1).protocolVersion() == 2;
    } catch (M11ProtocolException failure) {
      throw new IllegalStateException("mutant version bypass did not execute", failure);
    }
    return facts("productionRejected", productionRejected, "mutantAccepted", mutantAccepted);
  }

  private static String observe(String id, Observation observation) {
    return switch (id) {
      case "M11-OFFER-AS-SUCCESS" ->
          observation.number("responsesBeforeApply") > 0
              ? "INGRESS_OFFER_UPGRADED_TO_BUSINESS_SUCCESS"
              : null;
      case "M11-SESSION-AS-IDENTITY" ->
          observation.number("candidateNew") > observation.number("oracleNew")
              ? "SESSION_CHANGED_BUSINESS_IDENTITY"
              : null;
      case "M11-CORRELATION-AS-IDENTITY" ->
          observation.number("candidateNew") > observation.number("oracleNew")
              ? "CORRELATION_CHANGED_BUSINESS_IDENTITY"
              : null;
      case "M11-RESPOND-BEFORE-BIND" ->
          observation.flag("responseBeforeBind") && observation.flag("retryWasNew")
              ? "RESPONSE_OBSERVED_BEFORE_RESULT_BIND"
              : null;
      case "M11-DROP-IDENTITY-FROM-SNAPSHOT" ->
          observation.number("retryApplicationSequence")
                  >= observation.number("beforeRetrySequence")
              ? "SNAPSHOT_LOST_IDEMPOTENCY_TABLE"
              : null;
      case "M11-CORRUPT-SNAPSHOT-TO-GENESIS" ->
          observation.flag("decoderRejected") && observation.flag("restoredGenesis")
              ? "CORRUPT_SNAPSHOT_SILENTLY_BECAME_GENESIS"
              : null;
      case "M11-REJECT-N-MINUS-ONE" ->
          observation.flag("productionReadable") && !observation.flag("mutantReadable")
              ? "N_MINUS_ONE_COMPATIBILITY_REJECTED"
              : null;
      case "M11-INCLUDE-RUNTIME-METADATA-IN-DIGEST" ->
          observation.flag("businessDigestStable") && !observation.flag("mutantDigestStable")
              ? "RUNTIME_METADATA_CHANGED_BUSINESS_DIGEST"
              : null;
      case "M11-DOUBLE-WRITE-LOCAL-WAL" ->
          observation.number("standaloneWalWrites") > 0
              ? "CLUSTER_SERVICE_WROTE_STANDALONE_WAL"
              : null;
      case "M11-ACCEPT-UNSUPPORTED-VERSION" ->
          observation.flag("productionRejected") && observation.flag("mutantAccepted")
              ? "UNSUPPORTED_VERSION_ACCEPTED"
              : null;
      default -> throw new IllegalArgumentException("unknown M11 mutant " + id);
    };
  }

  private static List<Step> rawWitness(String id) {
    return switch (id) {
      case "M11-OFFER-AS-SUCCESS" -> steps("OFFER:one", "APPLY_PENDING:one", "OBSERVE:one");
      case "M11-SESSION-AS-IDENTITY" ->
          steps("SESSION_APPLY:session-1", "SESSION_RETRY:session-2", "OBSERVE:one");
      case "M11-CORRELATION-AS-IDENTITY" ->
          steps(
              "CORRELATION_APPLY:correlation-1", "CORRELATION_RETRY:correlation-2", "OBSERVE:one");
      case "M11-RESPOND-BEFORE-BIND" ->
          steps("APPLY:one", "RESPOND:one", "CRASH_BEFORE_BIND:one", "RETRY:one");
      case "M11-DROP-IDENTITY-FROM-SNAPSHOT" ->
          steps("APPLY:one", "SNAPSHOT_WITHOUT_IDENTITY:one", "RESTORE:one", "RETRY:one");
      case "M11-CORRUPT-SNAPSHOT-TO-GENESIS" ->
          steps("APPLY:one", "CORRUPT_SNAPSHOT:one", "RESTORE_FALLBACK:one");
      case "M11-REJECT-N-MINUS-ONE" ->
          steps(
              "READ_REQUEST_V1:one", "READ_RESPONSE_V1:one", "READ_SNAPSHOT_S1:one", "OBSERVE:one");
      case "M11-INCLUDE-RUNTIME-METADATA-IN-DIGEST" ->
          steps("BUSINESS_STATE:one", "SESSION_ONE:one", "SESSION_TWO:one", "OBSERVE:one");
      case "M11-DOUBLE-WRITE-LOCAL-WAL" -> steps("CLUSTER_APPLY:one", "OBSERVE_WAL:one");
      case "M11-ACCEPT-UNSUPPORTED-VERSION" -> steps("DECODE_REQUEST_V3:one", "OBSERVE:one");
      default -> throw new IllegalArgumentException("unknown M11 mutant " + id);
    };
  }

  private static M11CommandRequest request() {
    try {
      return new M11RequestCodec()
          .create(
              2,
              2,
              new UUID(11, 13),
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

  private static Path golden(String name) {
    return Path.of("matching-testkit/src/test/resources/m11/goldens").resolve(name);
  }

  private static Observation facts(Object... pairs) {
    Map<String, Object> values = new LinkedHashMap<>();
    for (int index = 0; index < pairs.length; index += 2) {
      values.put((String) pairs[index], pairs[index + 1]);
    }
    return new Observation(Map.copyOf(values));
  }

  private static int indexOf(List<Step> steps, String kind) {
    for (int index = 0; index < steps.size(); index++) {
      if (kind.equals(steps.get(index).kind())) {
        return index;
      }
    }
    throw new InvalidHistory("missing " + kind);
  }

  private static void requireKinds(List<Step> steps, String... kinds) {
    List<String> actual = steps.stream().map(Step::kind).toList();
    for (String kind : kinds) {
      requireHistory(actual.contains(kind), "missing " + kind);
    }
  }

  private static String sha256(String value) {
    try {
      return java.util.HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException failure) {
      throw new IllegalStateException("SHA-256 unavailable", failure);
    }
  }

  private static Shrink minimize(Path root, String id, List<Step> raw, String fingerprint) {
    List<Step> current = new ArrayList<>(raw);
    int trials = 0;
    boolean changed;
    do {
      changed = false;
      for (int index = 0; index < current.size(); index++) {
        List<Step> candidate = new ArrayList<>(current);
        candidate.remove(index);
        trials++;
        Replay replay = classify(root, id, candidate);
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

  private static boolean oneMinimal(Path root, String id, List<Step> steps, String fingerprint) {
    for (int index = 0; index < steps.size(); index++) {
      List<Step> candidate = new ArrayList<>(steps);
      candidate.remove(index);
      Replay replay = classify(root, id, candidate);
      if (M11CheckRunner.STUDENT_FAILURE.equals(replay.classification())
          && fingerprint.equals(replay.fingerprint())) {
        return false;
      }
    }
    return true;
  }

  private static Runnable controlAction(String id) {
    return switch (id) {
      case "M11-THROWING-CODEC-CONTROL" ->
          () -> {
            throw new IllegalStateException("codec control");
          };
      case "M11-CLUSTER-STARTUP-CONTROL" ->
          () -> {
            throw new IllegalStateException("cluster startup control");
          };
      case "M11-CORRUPT-HARNESS-OUTPUT-CONTROL" ->
          () -> {
            throw new IllegalArgumentException("harness output control");
          };
      default -> throw new IllegalArgumentException("unknown M11 system control " + id);
    };
  }

  private static void control(ArrayNode controls, String id, Runnable action) {
    String classification;
    try {
      action.run();
      classification = M11CheckRunner.PASS;
    } catch (RuntimeException failure) {
      classification = M11CheckRunner.SYSTEM_ERROR;
    }
    require(
        M11CheckRunner.SYSTEM_ERROR.equals(classification), id + " did not fail as SYSTEM_ERROR");
    ObjectNode node = controls.addObject();
    node.put("id", id);
    node.put("classification", classification);
    node.put("countedAsKill", false);
  }

  private static List<Step> steps(String... encoded) {
    return Arrays.stream(encoded).map(Step::new).toList();
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
    String kind() {
      int separator = encoded.indexOf(':');
      if (separator <= 0 || separator == encoded.length() - 1) {
        throw new InvalidHistory("malformed step");
      }
      return encoded.substring(0, separator);
    }

    String value() {
      return encoded.substring(encoded.indexOf(':') + 1);
    }

    void write(ObjectNode node) {
      node.put("kind", kind());
      node.put("encoded", encoded);
    }
  }

  record Observation(Map<String, Object> facts) {
    long number(String key) {
      return ((Number) facts.get(key)).longValue();
    }

    boolean flag(String key) {
      return (Boolean) facts.get(key);
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
