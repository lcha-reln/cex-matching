package io.github.lchareln.cex.matching.testkit;

import io.github.lchareln.cex.matching.cluster.DirectM11MatchingRuntime;
import io.github.lchareln.cex.matching.cluster.M11CommandRequest;
import io.github.lchareln.cex.matching.cluster.M11ProtocolException;
import io.github.lchareln.cex.matching.cluster.M11RequestCodec;
import io.github.lchareln.cex.matching.cluster.M11ResponseStatus;
import io.github.lchareln.cex.matching.cluster.M11RuntimeStateCodec;
import io.github.lchareln.cex.matching.local.M08Command;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Reproducible, runtime-independent command and invocation corpus for the frozen M12 schedule. */
final class M12DeterministicCorpus {
  static final long SEED = 6120L;

  private M12DeterministicCorpus() {}

  static Corpus generate(M12WorkloadLoader.Workload workload) {
    require(workload.sha256().equals(M12StartCheckRunner.WORKLOAD_SHA256), "workload binding");
    List<DurableIdentity> identities = new ArrayList<>();
    for (int index = 1; index <= 66; index++) {
      identities.add(identity(index));
    }

    List<Attempt> attempts = new ArrayList<>();
    int ordinal = 1;
    DirectM11MatchingRuntime oracle = new DirectM11MatchingRuntime();
    attempts.add(
        Attempt.notSubmitted(
            ordinal++, "PRE_OFFER_IS_NOT_SUBMITTED", identities.getFirst(), correlation(1), 1, 0));

    Map<DurableIdentity, Binding> bindings = new LinkedHashMap<>();
    for (int index = 0; index < 32; index++) {
      DurableIdentity identity = identities.get(index);
      Binding binding = binding(identity, index + 1, 1, oracle);
      bindings.put(identity, binding);
      attempts.add(
          Attempt.acknowledgedNew(
              ordinal++,
              "PRE_FAILOVER_ACKNOWLEDGED_NEW_32",
              identity,
              correlation(ordinal),
              1,
              0,
              binding));
    }
    for (int index = 0; index < 8; index++) {
      DurableIdentity identity = identities.get(index);
      attempts.add(
          Attempt.acknowledgedDuplicate(
              ordinal++,
              "ACKNOWLEDGED_DUPLICATE_RETRY_8",
              identity,
              correlation(ordinal),
              1,
              0,
              firstAttemptOrdinal(attempts, identity),
              bindings.get(identity)));
    }

    DurableIdentity appliedUnknown = identities.get(32);
    Binding unknownBinding = binding(appliedUnknown, 33, 1, oracle);
    bindings.put(appliedUnknown, unknownBinding);
    int appliedUnknownOrdinal = ordinal;
    attempts.add(
        Attempt.unknownApplied(
            ordinal++,
            "APPLIED_RESPONSE_UNOBSERVED_UNKNOWN_1",
            appliedUnknown,
            correlation(ordinal),
            1,
            0,
            unknownBinding));
    attempts.add(
        Attempt.acknowledgedDuplicate(
            ordinal++,
            "SAME_IDENTITY_UNKNOWN_RETRY",
            appliedUnknown,
            correlation(ordinal),
            2,
            1,
            appliedUnknownOrdinal,
            unknownBinding));

    for (int index = 33; index < 65; index++) {
      DurableIdentity identity = identities.get(index);
      Binding binding = binding(identity, index + 1, 2, oracle);
      bindings.put(identity, binding);
      attempts.add(
          Attempt.acknowledgedNew(
              ordinal++,
              "POST_FAILOVER_ACKNOWLEDGED_NEW_32",
              identity,
              correlation(ordinal),
              2,
              1,
              binding));
    }
    for (int index = 33; index < 41; index++) {
      DurableIdentity identity = identities.get(index);
      attempts.add(
          Attempt.acknowledgedDuplicate(
              ordinal++,
              "POST_FAILOVER_DUPLICATE_RETRY_8",
              identity,
              correlation(ordinal),
              2,
              1,
              firstAttemptOrdinal(attempts, identity),
              bindings.get(identity)));
    }

    DurableIdentity noQuorum = identities.get(65);
    int noQuorumOrdinal = ordinal;
    attempts.add(
        Attempt.unknownNotApplied(
            ordinal++, "NO_QUORUM_UNKNOWN_1", noQuorum, correlation(ordinal), 2, 1, true));
    Binding noQuorumBinding = binding(noQuorum, 66, 2, oracle);
    bindings.put(noQuorum, noQuorumBinding);
    attempts.add(
        Attempt.acknowledgedNewRetry(
            ordinal++,
            "RESTORE_QUORUM_AND_SAME_IDENTITY_RETRY",
            noQuorum,
            correlation(ordinal),
            2,
            1,
            noQuorumOrdinal,
            noQuorumBinding));

    require(identities.size() == 66, "identity cardinality");
    require(attempts.size() == 85, "client invocation cardinality");
    require(
        attempts.stream().filter(Attempt::ingressAccepted).count() == 84,
        "ingress attempt cardinality");
    require(bindings.size() == 66, "binding cardinality");
    require(
        new HashSet<>(attempts.stream().map(Attempt::correlationId).toList()).size() == 85,
        "correlation uniqueness");

    String corpusDigest = corpusDigest(identities, attempts, List.copyOf(bindings.values()));
    String expectedIdentityResultDigest =
        new M11RuntimeStateCodec().identityTableDigest(oracle.stateImage().identityBindings());
    return new Corpus(
        identities,
        attempts,
        List.copyOf(bindings.values()),
        corpusDigest,
        oracle.semanticStateDigest(),
        expectedIdentityResultDigest);
  }

  private static DurableIdentity identity(int index) {
    long a = mix64(SEED + index * 2L);
    long b = mix64(SEED + index * 2L + 1L);
    UUID commandId = new UUID(a, b);
    String producerId = "m12-producer";
    long producerEpoch = 12L;
    long producerSequence = index;
    String side = (index & 1) == 0 ? "SELL" : "BUY";
    long price = 50_000_000L + Math.floorMod(mix64(index), 10_000L) * 100L;
    long quantity = 1L + Math.floorMod(mix64(index + 1000L), 9L);
    M08Command.Place command =
        new M08Command.Place(
            "BTC-USDT",
            BigInteger.valueOf(12_000_000L + index),
            side,
            BigInteger.valueOf(price),
            BigInteger.valueOf(quantity),
            "GTC",
            0,
            "NONE",
            Optional.empty());
    M11CommandRequest request;
    try {
      request =
          new M11RequestCodec()
              .create(
                  2,
                  2,
                  correlation(10_000 + index),
                  producerId,
                  producerEpoch,
                  1,
                  producerSequence,
                  commandId,
                  command);
    } catch (M11ProtocolException failure) {
      throw new IllegalStateException("cannot create canonical M12 production request", failure);
    }
    return new DurableIdentity(
        index,
        request.commandId(),
        request.slot().producerId(),
        request.slot().producerEpoch(),
        request.slot().shardId(),
        request.slot().producerSequence(),
        request.payloadHash(),
        request.envelope().commandPayload(),
        request.envelopeBytes());
  }

  static M11CommandRequest requestFor(DurableIdentity identity, UUID correlationId) {
    Objects.requireNonNull(identity, "identity");
    Objects.requireNonNull(correlationId, "correlationId");
    try {
      M11CommandRequest request =
          new M11RequestCodec()
              .create(2, 2, correlationId, identity.canonicalBytes(), identity.shardId());
      require(request.commandId().equals(identity.commandId()), "request command identity");
      require(request.slot().equals(identity.slot()), "request producer Slot");
      require(request.payloadHash().equals(identity.payloadSha256()), "request payload hash");
      return request;
    } catch (M11ProtocolException failure) {
      throw new IllegalStateException("cannot rebuild canonical M12 request", failure);
    }
  }

  private static Binding binding(
      DurableIdentity identity, long sequence, long term, DirectM11MatchingRuntime oracle) {
    M11CommandRequest request = requestFor(identity, correlation(30_000 + identity.index()));
    var response = oracle.submit(request).response();
    require(response.status() == M11ResponseStatus.NEW_APPLIED, "direct oracle NEW result");
    require(
        response.applicationSequence().isPresent()
            && response.applicationSequence().orElseThrow() == sequence,
        "direct oracle application sequence");
    require(response.resultDigest().isPresent(), "direct oracle result digest");
    return new Binding(identity, sequence, response.resultDigest().orElseThrow(), 1, term);
  }

  private static UUID correlation(int ordinal) {
    return new UUID(mix64(SEED ^ (ordinal * 31L)), mix64(SEED ^ (ordinal * 31L + 1L)));
  }

  private static int firstAttemptOrdinal(List<Attempt> attempts, DurableIdentity identity) {
    return attempts.stream()
        .filter(attempt -> attempt.identity().equals(identity) && attempt.ingressAccepted())
        .mapToInt(Attempt::ordinal)
        .findFirst()
        .orElseThrow();
  }

  private static long mix64(long value) {
    long mixed = value + 0x9E3779B97F4A7C15L;
    mixed = (mixed ^ (mixed >>> 30)) * 0xBF58476D1CE4E5B9L;
    mixed = (mixed ^ (mixed >>> 27)) * 0x94D049BB133111EBL;
    return mixed ^ (mixed >>> 31);
  }

  static String semanticDigest(List<Binding> bindings) {
    StringBuilder canonical = new StringBuilder("M12-SEMANTIC-STATE-V1\n");
    bindings.stream()
        .sorted(java.util.Comparator.comparingLong(Binding::applicationSequence))
        .forEach(
            binding -> {
              append(canonical, "identity", binding.identity().canonicalSha256());
              append(canonical, "sequence", Long.toString(binding.applicationSequence()));
              append(canonical, "result", binding.resultDigest());
              append(canonical, "effects", Integer.toString(binding.businessEffectCount()));
            });
    return Hashing.sha256Hex(canonical.toString().getBytes(StandardCharsets.UTF_8));
  }

  private static String corpusDigest(
      List<DurableIdentity> identities, List<Attempt> attempts, List<Binding> bindings) {
    StringBuilder canonical = new StringBuilder("M12-DETERMINISTIC-CORPUS-V1\n");
    identities.forEach(identity -> append(canonical, "identity", identity.canonicalSha256()));
    attempts.forEach(
        attempt -> {
          append(canonical, "attempt", Integer.toString(attempt.ordinal()));
          append(canonical, "phase", attempt.phase());
          append(canonical, "identity", attempt.identity().canonicalSha256());
          append(canonical, "correlation", attempt.correlationId().toString());
          append(canonical, "outcome", attempt.outcome().name());
          append(canonical, "status", Objects.toString(attempt.responseStatus(), ""));
        });
    append(canonical, "semantic", semanticDigest(bindings));
    return Hashing.sha256Hex(canonical.toString().getBytes(StandardCharsets.UTF_8));
  }

  private static void append(StringBuilder target, String name, String value) {
    target
        .append(name)
        .append(':')
        .append(value.getBytes(StandardCharsets.UTF_8).length)
        .append(':')
        .append(value)
        .append('\n');
  }

  private static void require(boolean condition, String message) {
    if (!condition) {
      throw new IllegalStateException("invalid M12 deterministic corpus: " + message);
    }
  }

  enum ClientOutcome {
    NOT_SUBMITTED,
    UNKNOWN,
    ACKNOWLEDGED
  }

  enum ResponseStatus {
    NEW_APPLIED,
    DUPLICATE_REPLAYED
  }

  static final class DurableIdentity {
    private final int index;
    private final UUID commandId;
    private final String producerId;
    private final long producerEpoch;
    private final long shardId;
    private final long producerSequence;
    private final String payloadSha256;
    private final byte[] payloadBytes;
    private final byte[] canonicalBytes;
    private final String canonicalSha256;

    DurableIdentity(
        int index,
        UUID commandId,
        String producerId,
        long producerEpoch,
        long shardId,
        long producerSequence,
        String payloadSha256,
        byte[] payloadBytes,
        byte[] canonicalBytes) {
      this.index = index;
      this.commandId = Objects.requireNonNull(commandId);
      this.producerId = Objects.requireNonNull(producerId);
      this.producerEpoch = producerEpoch;
      this.shardId = shardId;
      this.producerSequence = producerSequence;
      this.payloadSha256 = Objects.requireNonNull(payloadSha256);
      this.payloadBytes = payloadBytes.clone();
      this.canonicalBytes = canonicalBytes.clone();
      this.canonicalSha256 = Hashing.sha256Hex(this.canonicalBytes);
      require(index > 0, "identity index");
      require(producerEpoch > 0 && shardId > 0 && producerSequence > 0, "producer Slot");
      require(Hashing.sha256Hex(this.payloadBytes).equals(payloadSha256), "payload digest");
    }

    int index() {
      return index;
    }

    UUID commandId() {
      return commandId;
    }

    String producerId() {
      return producerId;
    }

    long producerEpoch() {
      return producerEpoch;
    }

    long producerSequence() {
      return producerSequence;
    }

    long shardId() {
      return shardId;
    }

    io.github.lchareln.cex.matching.local.Slot slot() {
      return new io.github.lchareln.cex.matching.local.Slot(
          producerId, producerEpoch, shardId, producerSequence);
    }

    String payloadSha256() {
      return payloadSha256;
    }

    byte[] payloadBytes() {
      return payloadBytes.clone();
    }

    byte[] canonicalBytes() {
      return canonicalBytes.clone();
    }

    String canonicalSha256() {
      return canonicalSha256;
    }

    @Override
    public boolean equals(Object other) {
      return other instanceof DurableIdentity identity
          && Arrays.equals(canonicalBytes, identity.canonicalBytes);
    }

    @Override
    public int hashCode() {
      return Arrays.hashCode(canonicalBytes);
    }

    @Override
    public String toString() {
      return "DurableIdentity[" + index + "," + canonicalSha256 + ']';
    }
  }

  record Binding(
      DurableIdentity identity,
      long applicationSequence,
      String resultDigest,
      int businessEffectCount,
      long observedResponseAuthorityTerm) {
    Binding {
      Objects.requireNonNull(identity);
      Objects.requireNonNull(resultDigest);
      require(applicationSequence > 0, "application sequence");
      require(businessEffectCount >= 0, "business effect count");
      require(observedResponseAuthorityTerm >= 0, "observed response authority term");
    }
  }

  record Attempt(
      int ordinal,
      String phase,
      DurableIdentity identity,
      UUID correlationId,
      boolean ingressAccepted,
      ClientOutcome outcome,
      boolean trustedResponseObserved,
      UUID responseCorrelationId,
      ResponseStatus responseStatus,
      Long applicationSequence,
      String resultDigest,
      boolean businessEffectApplied,
      Integer retryOfAttemptOrdinal,
      long authorityTerm,
      int authorityLeaderId,
      boolean responseAcceptedUnderCurrentClientAuthority,
      boolean noQuorumWindow,
      boolean timeoutClassifiedAsBusinessRejection) {
    Attempt {
      require(ordinal > 0, "attempt ordinal");
      Objects.requireNonNull(phase);
      Objects.requireNonNull(identity);
      Objects.requireNonNull(correlationId);
      Objects.requireNonNull(outcome);
      require(authorityTerm >= 0, "authority term");
      require(authorityLeaderId >= 0, "authority leader");
      if (applicationSequence != null) {
        require(applicationSequence > 0, "bound application sequence");
        Objects.requireNonNull(resultDigest);
      } else {
        require(resultDigest == null, "result digest without sequence");
      }
    }

    static Attempt notSubmitted(
        int ordinal,
        String phase,
        DurableIdentity identity,
        UUID correlation,
        long term,
        int leader) {
      return new Attempt(
          ordinal,
          phase,
          identity,
          correlation,
          false,
          ClientOutcome.NOT_SUBMITTED,
          false,
          null,
          null,
          null,
          null,
          false,
          null,
          term,
          leader,
          false,
          false,
          false);
    }

    static Attempt unknownApplied(
        int ordinal,
        String phase,
        DurableIdentity identity,
        UUID correlation,
        long term,
        int leader,
        Binding binding) {
      return new Attempt(
          ordinal,
          phase,
          identity,
          correlation,
          true,
          ClientOutcome.UNKNOWN,
          false,
          null,
          null,
          binding.applicationSequence(),
          binding.resultDigest(),
          true,
          null,
          term,
          leader,
          false,
          false,
          false);
    }

    static Attempt unknownNotApplied(
        int ordinal,
        String phase,
        DurableIdentity identity,
        UUID correlation,
        long term,
        int leader,
        boolean noQuorum) {
      return new Attempt(
          ordinal,
          phase,
          identity,
          correlation,
          true,
          ClientOutcome.UNKNOWN,
          false,
          null,
          null,
          null,
          null,
          false,
          null,
          term,
          leader,
          false,
          noQuorum,
          false);
    }

    static Attempt acknowledgedNew(
        int ordinal,
        String phase,
        DurableIdentity identity,
        UUID correlation,
        long term,
        int leader,
        Binding binding) {
      return acknowledged(
          ordinal,
          phase,
          identity,
          correlation,
          term,
          leader,
          null,
          binding,
          ResponseStatus.NEW_APPLIED);
    }

    static Attempt acknowledgedNewRetry(
        int ordinal,
        String phase,
        DurableIdentity identity,
        UUID correlation,
        long term,
        int leader,
        int retryOf,
        Binding binding) {
      return acknowledged(
          ordinal,
          phase,
          identity,
          correlation,
          term,
          leader,
          retryOf,
          binding,
          ResponseStatus.NEW_APPLIED);
    }

    static Attempt acknowledgedDuplicate(
        int ordinal,
        String phase,
        DurableIdentity identity,
        UUID correlation,
        long term,
        int leader,
        int retryOf,
        Binding binding) {
      return acknowledged(
          ordinal,
          phase,
          identity,
          correlation,
          term,
          leader,
          retryOf,
          binding,
          ResponseStatus.DUPLICATE_REPLAYED);
    }

    private static Attempt acknowledged(
        int ordinal,
        String phase,
        DurableIdentity identity,
        UUID correlation,
        long term,
        int leader,
        Integer retryOf,
        Binding binding,
        ResponseStatus status) {
      return new Attempt(
          ordinal,
          phase,
          identity,
          correlation,
          true,
          ClientOutcome.ACKNOWLEDGED,
          true,
          correlation,
          status,
          binding.applicationSequence(),
          binding.resultDigest(),
          status == ResponseStatus.NEW_APPLIED,
          retryOf,
          term,
          leader,
          true,
          false,
          false);
    }

    Attempt withIdentity(DurableIdentity replacement) {
      return new Attempt(
          ordinal,
          phase,
          replacement,
          correlationId,
          ingressAccepted,
          outcome,
          trustedResponseObserved,
          responseCorrelationId,
          responseStatus,
          applicationSequence,
          resultDigest,
          businessEffectApplied,
          retryOfAttemptOrdinal,
          authorityTerm,
          authorityLeaderId,
          responseAcceptedUnderCurrentClientAuthority,
          noQuorumWindow,
          timeoutClassifiedAsBusinessRejection);
    }

    Attempt withOutcomeAndResponse(
        ClientOutcome replacementOutcome,
        boolean replacementTrustedResponse,
        UUID replacementResponseCorrelation,
        ResponseStatus replacementStatus,
        Long replacementSequence,
        String replacementDigest,
        boolean replacementEffect,
        boolean replacementTimeoutRejection) {
      return new Attempt(
          ordinal,
          phase,
          identity,
          correlationId,
          ingressAccepted,
          replacementOutcome,
          replacementTrustedResponse,
          replacementResponseCorrelation,
          replacementStatus,
          replacementSequence,
          replacementDigest,
          replacementEffect,
          retryOfAttemptOrdinal,
          authorityTerm,
          authorityLeaderId,
          responseAcceptedUnderCurrentClientAuthority,
          noQuorumWindow,
          replacementTimeoutRejection);
    }

    Attempt withCurrentClientAuthorityAcceptance(boolean current) {
      return new Attempt(
          ordinal,
          phase,
          identity,
          correlationId,
          ingressAccepted,
          outcome,
          trustedResponseObserved,
          responseCorrelationId,
          responseStatus,
          applicationSequence,
          resultDigest,
          businessEffectApplied,
          retryOfAttemptOrdinal,
          authorityTerm,
          authorityLeaderId,
          current,
          noQuorumWindow,
          timeoutClassifiedAsBusinessRejection);
    }
  }

  record Corpus(
      List<DurableIdentity> identities,
      List<Attempt> attempts,
      List<Binding> bindings,
      String corpusSha256,
      String expectedFinalSemanticDigest,
      String expectedIdentityResultDigest) {
    Corpus {
      identities = List.copyOf(identities);
      attempts = List.copyOf(attempts);
      bindings = List.copyOf(bindings);
      Objects.requireNonNull(corpusSha256);
      Objects.requireNonNull(expectedFinalSemanticDigest);
      Objects.requireNonNull(expectedIdentityResultDigest);
    }

    int ingressAttemptCount() {
      return Math.toIntExact(attempts.stream().filter(Attempt::ingressAccepted).count());
    }

    Map<Integer, Attempt> attemptsByOrdinal() {
      Map<Integer, Attempt> result = new LinkedHashMap<>();
      attempts.forEach(attempt -> result.put(attempt.ordinal(), attempt));
      return Map.copyOf(result);
    }

    Set<String> identityDigests() {
      return Set.copyOf(identities.stream().map(DurableIdentity::canonicalSha256).toList());
    }

    List<M11CommandRequest> requests() {
      return identities.stream()
          .map(identity -> requestFor(identity, correlation(20_000 + identity.index())))
          .toList();
    }

    M11CommandRequest requestFor(DurableIdentity identity, UUID correlationId) {
      require(identities.contains(identity), "identity does not belong to this corpus");
      return M12DeterministicCorpus.requestFor(identity, correlationId);
    }
  }
}
