package io.github.lchareln.cex.matching.cluster;

import io.github.lchareln.cex.matching.local.CanonicalResult;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/** Reversible codec for the complete result retained by the cluster identity table. */
public final class M11FullResultCodec {
  public static final int MAX_RESULT_BYTES = 16 * 1024 * 1024;

  private static final int MAX_STRING_BYTES = 1024 * 1024;
  private static final int MAX_EVENTS = 1_000_000;

  public byte[] encode(CanonicalResult result) {
    Objects.requireNonNull(result, "result");
    M11Binary.Writer writer = new M11Binary.Writer();
    putResult(writer, result);
    byte[] encoded = writer.toByteArray();
    if (encoded.length > MAX_RESULT_BYTES) {
      throw new IllegalArgumentException("canonical result exceeds the M11 retention bound");
    }
    return encoded;
  }

  public CanonicalResult decodeCanonical(byte[] encoded) throws M11ProtocolException {
    Objects.requireNonNull(encoded, "encoded");
    if (encoded.length == 0 || encoded.length > MAX_RESULT_BYTES) {
      throw new M11ProtocolException(
          M11ProtocolException.Code.LENGTH_LIMIT,
          "canonical result length is outside the retention bound");
    }
    M11Binary.Reader reader = new M11Binary.Reader(encoded);
    CanonicalResult result = readResult(reader);
    reader.requireExhausted();
    if (!Arrays.equals(encoded, encode(result))) {
      throw new M11ProtocolException(
          M11ProtocolException.Code.NON_CANONICAL, "retained result is not canonical");
    }
    return result;
  }

  static void putResult(M11Binary.Writer writer, CanonicalResult result) {
    writer.putString(result.resultType());
    writer.putLong(result.applicationSequence());
    writer.putInt(result.events().size());
    result.events().forEach(writer::putString);
    writer.putString(result.context());
    writer.putString(result.semanticStateDigest());
    writer.putString(result.resultDigest());
  }

  static CanonicalResult readResult(M11Binary.Reader reader) throws M11ProtocolException {
    String resultType = reader.getString(MAX_STRING_BYTES);
    long applicationSequence = reader.getLong();
    int eventCount = reader.getCount(MAX_EVENTS);
    List<String> events = new ArrayList<>(eventCount);
    for (int index = 0; index < eventCount; index++) {
      events.add(reader.getString(MAX_STRING_BYTES));
    }
    String context = reader.getString(MAX_STRING_BYTES);
    String semanticDigest = reader.getString(MAX_STRING_BYTES);
    String resultDigest = reader.getString(MAX_STRING_BYTES);
    try {
      return new CanonicalResult(
          resultType, applicationSequence, events, context, semanticDigest, resultDigest);
    } catch (IllegalArgumentException failure) {
      throw new M11ProtocolException(
          M11ProtocolException.Code.INVALID_VALUE,
          "retained canonical result is internally inconsistent",
          failure);
    }
  }
}
