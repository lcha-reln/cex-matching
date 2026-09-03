package io.github.lchareln.cex.matching.cluster;

import io.github.lchareln.cex.matching.local.M08Envelope;
import io.github.lchareln.cex.matching.local.M08EnvelopeCodec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * One explicitly selected fault seam shared by fresh M11 production components.
 *
 * <p>The normal constructors always use {@link Mode#NONE}. Mutation qualification creates a new
 * policy per candidate and may select exactly one non-NONE mode. Keeping the seam here means codec,
 * state, snapshot, completion, and startup faults change the same production code paths used by the
 * Cluster adapter; the judge does not fabricate their effects in its trace interpreter.
 */
public final class M11FaultPolicy {
  public enum Mode {
    NONE,
    OFFER_AS_SUCCESS,
    SESSION_AS_IDENTITY,
    CORRELATION_AS_IDENTITY,
    RESPOND_BEFORE_BIND,
    DROP_IDENTITY_FROM_SNAPSHOT,
    CORRUPT_SNAPSHOT_TO_GENESIS,
    REJECT_N_MINUS_ONE,
    INCLUDE_RUNTIME_METADATA_IN_DIGEST,
    DOUBLE_WRITE_LOCAL_WAL,
    ACCEPT_UNSUPPORTED_VERSION,
    REQUEST_CODEC_SYSTEM_ERROR,
    CLUSTER_STARTUP_SYSTEM_ERROR
  }

  private final Mode mode;
  private final AtomicLong activations = new AtomicLong();
  private final AtomicLong standaloneWriteSignals = new AtomicLong();

  private M11FaultPolicy(Mode mode) {
    this.mode = Objects.requireNonNull(mode, "mode");
  }

  public static M11FaultPolicy none() {
    return new M11FaultPolicy(Mode.NONE);
  }

  public static M11FaultPolicy single(Mode mode) {
    Objects.requireNonNull(mode, "mode");
    if (mode == Mode.NONE) {
      throw new IllegalArgumentException("single-fault policy requires one non-NONE mode");
    }
    return new M11FaultPolicy(mode);
  }

  public Mode mode() {
    return mode;
  }

  public long activationCount() {
    return activations.get();
  }

  /** A narrow testkit signal; this policy deliberately has no filesystem or I/O capability. */
  public long drainStandaloneWriteSignals() {
    return standaloneWriteSignals.getAndSet(0);
  }

  boolean completesOnIngressOffer() {
    return activate(Mode.OFFER_AS_SUCCESS);
  }

  M11CommandRequest transportIdentity(M11CommandRequest request, String transportSession) {
    Objects.requireNonNull(request, "request");
    Objects.requireNonNull(transportSession, "transportSession");
    String token;
    if (activate(Mode.SESSION_AS_IDENTITY)) {
      token = "session:" + transportSession;
    } else if (activate(Mode.CORRELATION_AS_IDENTITY)) {
      token = "correlation:" + request.correlationId();
    } else {
      return request;
    }
    try {
      M08Envelope external = request.envelope();
      byte[] envelopeBytes =
          new M08EnvelopeCodec()
              .encode(
                  "m11-mutant-" + sha256(token).substring(0, 12),
                  external.slot().producerEpoch(),
                  external.slot().shardId(),
                  external.slot().producerSequence(),
                  UUID.nameUUIDFromBytes(token.getBytes(StandardCharsets.UTF_8)),
                  external.command());
      M08Envelope envelope =
          new M08EnvelopeCodec().decodeCanonical(envelopeBytes, external.slot().shardId());
      return new M11CommandRequest(
          request.protocolVersion(),
          request.correlationId(),
          request.requestedResponseVersion(),
          envelopeBytes,
          envelope);
    } catch (io.github.lchareln.cex.matching.local.StructuralRejectionException failure) {
      throw new IllegalStateException(
          "fault policy could not construct transport identity", failure);
    }
  }

  boolean bindBeforeResponse() {
    return !activate(Mode.RESPOND_BEFORE_BIND);
  }

  List<M11IdentityBinding> restoreIdentityBindings(List<M11IdentityBinding> bindings) {
    Objects.requireNonNull(bindings, "bindings");
    if (activate(Mode.DROP_IDENTITY_FROM_SNAPSHOT)) {
      return List.of();
    }
    return List.copyOf(bindings);
  }

  boolean fallbackCorruptSnapshotToGenesis() {
    return activate(Mode.CORRUPT_SNAPSHOT_TO_GENESIS);
  }

  boolean rejectPreviousVersion(int version, int currentVersion) {
    return version == currentVersion - 1 && activate(Mode.REJECT_N_MINUS_ONE);
  }

  int readableRequestVersion(int wireVersion, int currentVersion) {
    if (wireVersion > currentVersion && activate(Mode.ACCEPT_UNSUPPORTED_VERSION)) {
      return currentVersion;
    }
    return wireVersion;
  }

  void beforeRequestDecode() {
    if (activate(Mode.REQUEST_CODEC_SYSTEM_ERROR)) {
      throw new IllegalStateException("injected M11 request codec component failure");
    }
  }

  String exposedSemanticDigest(String businessDigest, String session, UUID correlationId) {
    Objects.requireNonNull(businessDigest, "businessDigest");
    Objects.requireNonNull(session, "session");
    Objects.requireNonNull(correlationId, "correlationId");
    if (activate(Mode.INCLUDE_RUNTIME_METADATA_IN_DIGEST)) {
      return sha256(businessDigest + "|" + session + "|" + correlationId);
    }
    return businessDigest;
  }

  void afterNewApplication(M11CommandRequest request) {
    Objects.requireNonNull(request, "request");
    if (activate(Mode.DOUBLE_WRITE_LOCAL_WAL)) {
      standaloneWriteSignals.incrementAndGet();
    }
  }

  void beforeClusterLaunch() {
    if (activate(Mode.CLUSTER_STARTUP_SYSTEM_ERROR)) {
      throw new IllegalStateException("injected M11 Cluster startup component failure");
    }
  }

  private boolean activate(Mode expected) {
    if (mode != expected) {
      return false;
    }
    activations.incrementAndGet();
    return true;
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
}
