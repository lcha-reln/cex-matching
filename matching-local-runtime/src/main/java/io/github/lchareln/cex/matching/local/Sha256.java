package io.github.lchareln.cex.matching.local;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

final class Sha256 {
  private Sha256() {}

  static byte[] digest(byte[] bytes) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(bytes);
    } catch (NoSuchAlgorithmException failure) {
      throw new IllegalStateException("the Java runtime does not provide SHA-256", failure);
    }
  }

  static String hex(byte[] bytes) {
    return HexFormat.of().formatHex(digest(bytes));
  }
}
