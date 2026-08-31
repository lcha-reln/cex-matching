package io.github.lchareln.cex.matching.local;

import java.util.List;
import java.util.Objects;

/** Auditable result retained for durable duplicate replay; it deliberately omits full bookAfter. */
public record CanonicalResult(
    String resultType,
    long applicationSequence,
    List<String> events,
    String context,
    String semanticStateDigest,
    String resultDigest) {
  public CanonicalResult {
    Objects.requireNonNull(resultType, "resultType");
    if (applicationSequence <= 0) {
      throw new IllegalArgumentException("applicationSequence must be positive");
    }
    events = List.copyOf(events);
    if (events.isEmpty()) {
      throw new IllegalArgumentException("canonical result must retain at least one event");
    }
    Objects.requireNonNull(context, "context");
    Objects.requireNonNull(semanticStateDigest, "semanticStateDigest");
    Objects.requireNonNull(resultDigest, "resultDigest");
    String expected = digest(resultType, applicationSequence, events, context, semanticStateDigest);
    if (!expected.equals(resultDigest)) {
      throw new IllegalArgumentException("canonical result digest does not match its fields");
    }
  }

  static CanonicalResult create(
      String resultType,
      long applicationSequence,
      List<String> events,
      String context,
      String semanticStateDigest) {
    return new CanonicalResult(
        resultType,
        applicationSequence,
        events,
        context,
        semanticStateDigest,
        digest(resultType, applicationSequence, events, context, semanticStateDigest));
  }

  private static String digest(
      String resultType,
      long applicationSequence,
      List<String> events,
      String context,
      String semanticStateDigest) {
    BinaryEncoding.Writer writer = new BinaryEncoding.Writer();
    writer.putString(resultType);
    writer.putLong(applicationSequence);
    writer.putInt(events.size());
    events.forEach(writer::putString);
    writer.putString(context);
    writer.putString(semanticStateDigest);
    return Sha256.hex(writer.toByteArray());
  }

  static String semanticDigest(String controlState, String bookState) {
    BinaryEncoding.Writer writer = new BinaryEncoding.Writer();
    writer.putString(controlState);
    writer.putString(bookState);
    return Sha256.hex(writer.toByteArray());
  }

  public byte[] auditBytes() {
    BinaryEncoding.Writer writer = new BinaryEncoding.Writer();
    writer.putString(resultType);
    writer.putLong(applicationSequence);
    writer.putInt(events.size());
    events.forEach(writer::putString);
    writer.putString(context);
    writer.putString(semanticStateDigest);
    writer.putString(resultDigest);
    return writer.toByteArray();
  }
}
