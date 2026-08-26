package io.github.lchareln.cex.matching.testkit;

import io.github.lchareln.cex.matching.ValidationResult;
import java.util.List;
import java.util.Objects;

/** Immutable result of one M00 canonical replay. */
public final class CanonicalHistory {
  private final byte[] bytes;
  private final String digest;
  private final int lineCount;
  private final List<ValidationResult> validationResults;

  CanonicalHistory(
      byte[] bytes, String digest, int lineCount, List<ValidationResult> validationResults) {
    this.bytes = bytes.clone();
    this.digest = Objects.requireNonNull(digest, "digest");
    this.lineCount = lineCount;
    this.validationResults = List.copyOf(validationResults);
  }

  public byte[] bytes() {
    return bytes.clone();
  }

  public String digest() {
    return digest;
  }

  public int lineCount() {
    return lineCount;
  }

  public List<ValidationResult> validationResults() {
    return validationResults;
  }
}
