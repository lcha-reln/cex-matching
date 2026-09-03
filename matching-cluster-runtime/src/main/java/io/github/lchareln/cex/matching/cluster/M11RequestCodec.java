package io.github.lchareln.cex.matching.cluster;

import io.github.lchareln.cex.matching.local.M08Command;
import io.github.lchareln.cex.matching.local.M08Envelope;
import io.github.lchareln.cex.matching.local.M08EnvelopeCodec;
import io.github.lchareln.cex.matching.local.StructuralRejectionException;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

/** Byte-exact implementation of the frozen M11Q request contract. */
public final class M11RequestCodec {
  public static final int MAGIC = 0x4D313151; // M11Q
  public static final int TEMPLATE_ID = 1;
  public static final int MIN_READABLE_VERSION = 1;
  public static final int CURRENT_VERSION = 2;
  public static final int MAX_MESSAGE_BYTES = M08EnvelopeCodec.MAX_ENVELOPE_BYTES + 64;

  private final M08EnvelopeCodec envelopeCodec = new M08EnvelopeCodec();

  public M11CommandRequest create(
      int requestVersion,
      int requestedResponseVersion,
      UUID correlationId,
      byte[] canonicalEnvelope,
      long expectedShard)
      throws M11ProtocolException {
    M08Envelope envelope = decodeEnvelope(canonicalEnvelope, expectedShard);
    M11CommandRequest request =
        construct(
            () ->
                new M11CommandRequest(
                    requestVersion,
                    correlationId,
                    requestedResponseVersion,
                    canonicalEnvelope,
                    envelope),
            "request");
    return decodeCanonical(encode(request), expectedShard);
  }

  public M11CommandRequest create(
      int requestVersion,
      int requestedResponseVersion,
      UUID correlationId,
      String producerId,
      long producerEpoch,
      long shardId,
      long producerSequence,
      UUID commandId,
      M08Command command)
      throws M11ProtocolException {
    byte[] envelope =
        envelopeCodec.encode(
            producerId, producerEpoch, shardId, producerSequence, commandId, command);
    return create(requestVersion, requestedResponseVersion, correlationId, envelope, shardId);
  }

  public byte[] encode(M11CommandRequest request) {
    Objects.requireNonNull(request, "request");
    M11Binary.Writer writer = new M11Binary.Writer();
    writer.putInt(MAGIC);
    writer.putInt(request.protocolVersion());
    writer.putInt(TEMPLATE_ID);
    writer.putLong(request.correlationId().getMostSignificantBits());
    writer.putLong(request.correlationId().getLeastSignificantBits());
    if (request.protocolVersion() == CURRENT_VERSION) {
      writer.putInt(request.requestedResponseVersion());
    }
    writer.putByteArray(request.envelopeBytes());
    byte[] encoded = writer.toByteArray();
    if (encoded.length > MAX_MESSAGE_BYTES) {
      throw new IllegalArgumentException("M11 request exceeds the message limit");
    }
    return encoded;
  }

  public M11CommandRequest decodeCanonical(byte[] encoded, long expectedShard)
      throws M11ProtocolException {
    Objects.requireNonNull(encoded, "encoded");
    if (encoded.length == 0 || encoded.length > MAX_MESSAGE_BYTES) {
      throw failure(M11ProtocolException.Code.LENGTH_LIMIT, "request length is outside its bound");
    }
    M11Binary.Reader reader = new M11Binary.Reader(encoded);
    if (reader.getInt() != MAGIC) {
      throw failure(M11ProtocolException.Code.INVALID_MAGIC, "request magic is invalid");
    }
    int version = reader.getInt();
    if (version < MIN_READABLE_VERSION || version > CURRENT_VERSION) {
      throw failure(
          M11ProtocolException.Code.UNSUPPORTED_VERSION, "request version is unsupported");
    }
    if (reader.getInt() != TEMPLATE_ID) {
      throw failure(M11ProtocolException.Code.INVALID_VALUE, "request template is unsupported");
    }
    UUID correlation = new UUID(reader.getLong(), reader.getLong());
    int responseVersion = version == 1 ? 1 : reader.getInt();
    byte[] envelopeBytes = reader.getByteArray(M08EnvelopeCodec.MAX_ENVELOPE_BYTES);
    reader.requireExhausted();
    M08Envelope envelope = decodeEnvelope(envelopeBytes, expectedShard);
    M11CommandRequest request =
        construct(
            () ->
                new M11CommandRequest(
                    version, correlation, responseVersion, envelopeBytes, envelope),
            "request");
    if (!Arrays.equals(encoded, encode(request))) {
      throw failure(M11ProtocolException.Code.NON_CANONICAL, "request is not canonical");
    }
    return request;
  }

  private M08Envelope decodeEnvelope(byte[] encoded, long expectedShard)
      throws M11ProtocolException {
    try {
      return envelopeCodec.decodeCanonical(encoded, expectedShard);
    } catch (StructuralRejectionException failure) {
      throw new M11ProtocolException(
          M11ProtocolException.Code.INVALID_VALUE, "embedded M08C1 envelope is invalid", failure);
    }
  }

  private static <T> T construct(Constructor<T> constructor, String field)
      throws M11ProtocolException {
    try {
      return constructor.create();
    } catch (IllegalArgumentException failure) {
      throw new M11ProtocolException(
          M11ProtocolException.Code.INVALID_VALUE, "invalid " + field, failure);
    }
  }

  private static M11ProtocolException failure(M11ProtocolException.Code code, String message) {
    return new M11ProtocolException(code, message);
  }

  @FunctionalInterface
  private interface Constructor<T> {
    T create();
  }
}
