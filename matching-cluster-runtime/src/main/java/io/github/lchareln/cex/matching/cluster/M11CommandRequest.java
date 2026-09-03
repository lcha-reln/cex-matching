package io.github.lchareln.cex.matching.cluster;

import io.github.lchareln.cex.matching.local.M08Command;
import io.github.lchareln.cex.matching.local.M08Envelope;
import io.github.lchareln.cex.matching.local.Slot;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

/** Canonical M11 invocation wrapped around one complete canonical M08C1 envelope. */
public final class M11CommandRequest {
  private final int protocolVersion;
  private final UUID correlationId;
  private final int requestedResponseVersion;
  private final byte[] envelopeBytes;
  private final M08Envelope envelope;

  M11CommandRequest(
      int protocolVersion,
      UUID correlationId,
      int requestedResponseVersion,
      byte[] envelopeBytes,
      M08Envelope envelope) {
    if (protocolVersion < M11RequestCodec.MIN_READABLE_VERSION
        || protocolVersion > M11RequestCodec.CURRENT_VERSION) {
      throw new IllegalArgumentException("unsupported request protocol version");
    }
    if (protocolVersion == 1 && requestedResponseVersion != 1) {
      throw new IllegalArgumentException("request v1 implicitly selects response v1");
    }
    if (requestedResponseVersion < M11ResponseCodec.MIN_READABLE_VERSION
        || requestedResponseVersion > M11ResponseCodec.CURRENT_VERSION) {
      throw new IllegalArgumentException("unsupported requested response version");
    }
    this.protocolVersion = protocolVersion;
    this.correlationId = Objects.requireNonNull(correlationId, "correlationId");
    this.requestedResponseVersion = requestedResponseVersion;
    this.envelopeBytes = Objects.requireNonNull(envelopeBytes, "envelopeBytes").clone();
    this.envelope = Objects.requireNonNull(envelope, "envelope");
  }

  public int protocolVersion() {
    return protocolVersion;
  }

  public UUID correlationId() {
    return correlationId;
  }

  public int requestedResponseVersion() {
    return requestedResponseVersion;
  }

  public byte[] envelopeBytes() {
    return envelopeBytes.clone();
  }

  public M08Envelope envelope() {
    return envelope;
  }

  public Slot slot() {
    return envelope.slot();
  }

  public UUID commandId() {
    return envelope.commandId();
  }

  public String payloadHash() {
    return envelope.payloadHash();
  }

  public M08Command command() {
    return envelope.command();
  }

  public M11CommandRequest withCorrelationId(UUID replacement) {
    return new M11CommandRequest(
        protocolVersion, replacement, requestedResponseVersion, envelopeBytes, envelope);
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof M11CommandRequest that
        && protocolVersion == that.protocolVersion
        && requestedResponseVersion == that.requestedResponseVersion
        && correlationId.equals(that.correlationId)
        && Arrays.equals(envelopeBytes, that.envelopeBytes)
        && envelope.equals(that.envelope);
  }

  @Override
  public int hashCode() {
    return 31 * Objects.hash(protocolVersion, correlationId, requestedResponseVersion, envelope)
        + Arrays.hashCode(envelopeBytes);
  }
}
