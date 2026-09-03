package io.github.lchareln.cex.matching.cluster;

import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;

/** Byte-exact implementation of the frozen bounded M11R response commitment. */
public final class M11ResponseCodec {
  public static final int MAGIC = 0x4D313152; // M11R
  public static final int TEMPLATE_ID = 2;
  public static final int MIN_READABLE_VERSION = 1;
  public static final int CURRENT_VERSION = 2;
  public static final int MAX_MESSAGE_BYTES = 16 * 1024;

  private static final int MAX_REJECTION_BYTES = 1024;

  public byte[] encode(M11CommandResponse response) {
    Objects.requireNonNull(response, "response");
    M11Binary.Writer writer = new M11Binary.Writer();
    writer.putInt(MAGIC);
    writer.putInt(response.protocolVersion());
    writer.putInt(TEMPLATE_ID);
    writer.putLong(response.correlationId().getMostSignificantBits());
    writer.putLong(response.correlationId().getLeastSignificantBits());
    writer.putInt(response.status().wireId());
    if (response.status() == M11ResponseStatus.REJECTED) {
      writer.putString(response.rejectionCode().orElseThrow());
    } else {
      writer.putLong(response.applicationSequence().orElseThrow());
      writer.putBytes(HexFormat.of().parseHex(response.resultDigest().orElseThrow()));
    }
    if (response.protocolVersion() == CURRENT_VERSION) {
      writer.putByte(response.commandId().isPresent() ? 1 : 0);
      if (response.commandId().isPresent()) {
        UUID commandId = response.commandId().orElseThrow();
        writer.putLong(commandId.getMostSignificantBits());
        writer.putLong(commandId.getLeastSignificantBits());
        writer.putBytes(HexFormat.of().parseHex(response.semanticStateDigest().orElseThrow()));
      }
    }
    byte[] encoded = writer.toByteArray();
    if (encoded.length > MAX_MESSAGE_BYTES) {
      throw new IllegalArgumentException("M11 response exceeds the message limit");
    }
    return encoded;
  }

  public M11CommandResponse decodeCanonical(byte[] encoded) throws M11ProtocolException {
    Objects.requireNonNull(encoded, "encoded");
    if (encoded.length == 0 || encoded.length > MAX_MESSAGE_BYTES) {
      throw failure(M11ProtocolException.Code.LENGTH_LIMIT, "response length is outside its bound");
    }
    M11Binary.Reader reader = new M11Binary.Reader(encoded);
    if (reader.getInt() != MAGIC) {
      throw failure(M11ProtocolException.Code.INVALID_MAGIC, "response magic is invalid");
    }
    int version = reader.getInt();
    if (version < MIN_READABLE_VERSION || version > CURRENT_VERSION) {
      throw failure(
          M11ProtocolException.Code.UNSUPPORTED_VERSION, "response version is unsupported");
    }
    if (reader.getInt() != TEMPLATE_ID) {
      throw failure(M11ProtocolException.Code.INVALID_VALUE, "response template is unsupported");
    }
    UUID correlation = new UUID(reader.getLong(), reader.getLong());
    M11ResponseStatus status = M11ResponseStatus.fromWire(reader.getInt());
    OptionalLong applicationSequence;
    Optional<String> resultDigest;
    Optional<String> rejectionCode;
    if (status == M11ResponseStatus.REJECTED) {
      applicationSequence = OptionalLong.empty();
      resultDigest = Optional.empty();
      rejectionCode = Optional.of(reader.getString(MAX_REJECTION_BYTES));
    } else {
      applicationSequence = OptionalLong.of(reader.getLong());
      resultDigest = Optional.of(HexFormat.of().formatHex(reader.getBytes(32)));
      rejectionCode = Optional.empty();
    }
    Optional<UUID> commandId = Optional.empty();
    Optional<String> semanticDigest = Optional.empty();
    if (version == CURRENT_VERSION) {
      int identityPresent = reader.getUnsignedByte();
      if (identityPresent != 0 && identityPresent != 1) {
        throw failure(M11ProtocolException.Code.NON_CANONICAL, "identity flag is not canonical");
      }
      if (identityPresent == 1) {
        commandId = Optional.of(new UUID(reader.getLong(), reader.getLong()));
        semanticDigest = Optional.of(HexFormat.of().formatHex(reader.getBytes(32)));
      }
    }
    reader.requireExhausted();
    final OptionalLong finalApplicationSequence = applicationSequence;
    final Optional<String> finalResultDigest = resultDigest;
    final Optional<String> finalRejectionCode = rejectionCode;
    final Optional<UUID> finalCommandId = commandId;
    final Optional<String> finalSemanticDigest = semanticDigest;
    M11CommandResponse response =
        construct(
            () ->
                new M11CommandResponse(
                    version,
                    correlation,
                    status,
                    finalApplicationSequence,
                    finalResultDigest,
                    finalRejectionCode,
                    finalCommandId,
                    finalSemanticDigest),
            "response");
    if (!Arrays.equals(encoded, encode(response))) {
      throw failure(M11ProtocolException.Code.NON_CANONICAL, "response is not canonical");
    }
    return response;
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
