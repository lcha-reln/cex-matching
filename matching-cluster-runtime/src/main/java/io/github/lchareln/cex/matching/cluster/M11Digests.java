package io.github.lchareln.cex.matching.cluster;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.zip.CRC32C;

final class M11Digests {
  private M11Digests() {}

  static byte[] sha256(byte[] value) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(value);
    } catch (NoSuchAlgorithmException failure) {
      throw new IllegalStateException("the Java runtime does not provide SHA-256", failure);
    }
  }

  static String sha256Hex(byte[] value) {
    return HexFormat.of().formatHex(sha256(value));
  }

  static int crc32c(byte[] value) {
    CRC32C crc = new CRC32C();
    crc.update(value, 0, value.length);
    return (int) crc.getValue();
  }
}
