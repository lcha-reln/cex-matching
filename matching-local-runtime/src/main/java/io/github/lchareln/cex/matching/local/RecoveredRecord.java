package io.github.lchareln.cex.matching.local;

import java.util.Arrays;
import java.util.Objects;

/** Complete CRC-verified record discovered during recovery. */
public record RecoveredRecord(WalPosition position, byte[] envelopeBytes) {
  public RecoveredRecord {
    Objects.requireNonNull(position, "position");
    envelopeBytes =
        Arrays.copyOf(Objects.requireNonNull(envelopeBytes, "envelopeBytes"), envelopeBytes.length);
  }

  @Override
  public byte[] envelopeBytes() {
    return Arrays.copyOf(envelopeBytes, envelopeBytes.length);
  }
}
