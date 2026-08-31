package io.github.lchareln.cex.matching.local;

/** Durable M08W1 coordinates for one command. */
public record WalPosition(
    long segmentId, long walSequence, long applicationSequence, long offset, int recordLength) {
  public WalPosition {
    if (segmentId <= 0
        || walSequence <= 0
        || applicationSequence <= 0
        || offset < M08WalFormat.HEADER_BYTES
        || recordLength < M08WalFormat.MIN_RECORD_BYTES) {
      throw new IllegalArgumentException("invalid WAL position");
    }
  }
}
