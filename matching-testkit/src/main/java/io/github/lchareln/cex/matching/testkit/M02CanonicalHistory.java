package io.github.lchareln.cex.matching.testkit;

import java.util.Objects;

/** Exact UTF-8 M02 semantic history and its digest. */
public final class M02CanonicalHistory {
  private final byte[] bytes;
  private final String digest;
  private final int lineCount;

  M02CanonicalHistory(byte[] bytes, String digest, int lineCount) {
    this.bytes = bytes.clone();
    this.digest = Objects.requireNonNull(digest, "digest");
    this.lineCount = lineCount;
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
}
