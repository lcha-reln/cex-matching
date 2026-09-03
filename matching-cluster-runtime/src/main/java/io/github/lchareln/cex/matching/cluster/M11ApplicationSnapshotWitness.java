package io.github.lchareln.cex.matching.cluster;

/** Application-level identity of snapshot bytes written to or loaded from Aeron Archive. */
public record M11ApplicationSnapshotWitness(
    long snapshotSequence,
    String snapshotDigest,
    String identityTableDigest,
    String semanticStateDigest,
    long nextApplicationSequence) {
  public M11ApplicationSnapshotWitness {
    if (snapshotSequence < 0 || nextApplicationSequence != snapshotSequence + 1) {
      throw new IllegalArgumentException("snapshot application positions disagree");
    }
    requireDigest(snapshotDigest, "snapshotDigest");
    requireDigest(identityTableDigest, "identityTableDigest");
    requireDigest(semanticStateDigest, "semanticStateDigest");
  }

  private static void requireDigest(String value, String field) {
    if (value == null || !value.matches("[0-9a-f]{64}")) {
      throw new IllegalArgumentException(field + " must be lowercase SHA-256");
    }
  }
}
