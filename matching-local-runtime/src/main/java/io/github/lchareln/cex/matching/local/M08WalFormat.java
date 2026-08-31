package io.github.lchareln.cex.matching.local;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.zip.CRC32C;

/** Fixed big-endian M08W1 segment/header/record encoding. */
final class M08WalFormat {
  static final int MAGIC = 0x4D303857; // M08W
  static final int VERSION = 1;
  static final int HEADER_BYTES = Integer.BYTES * 3 + Long.BYTES * 3;
  static final int RECORD_OVERHEAD = Integer.BYTES * 4 + Long.BYTES * 2;
  static final int MIN_RECORD_BYTES = RECORD_OVERHEAD;

  private M08WalFormat() {}

  static byte[] header(long shardId, long segmentId, long firstWalSequence) {
    ByteBuffer bytes = ByteBuffer.allocate(HEADER_BYTES);
    bytes.putInt(MAGIC);
    bytes.putInt(VERSION);
    bytes.putLong(shardId);
    bytes.putLong(segmentId);
    bytes.putLong(firstWalSequence);
    bytes.putInt(checksum(bytes.array(), 0, HEADER_BYTES - Integer.BYTES));
    return bytes.array();
  }

  static SegmentHeader decodeHeader(byte[] encoded) throws WalCorruptionException {
    if (encoded.length != HEADER_BYTES) {
      throw new WalCorruptionException("M08W1 segment header is incomplete");
    }
    int claimed = ByteBuffer.wrap(encoded).getInt(HEADER_BYTES - Integer.BYTES);
    int actual = checksum(encoded, 0, HEADER_BYTES - Integer.BYTES);
    if (claimed != actual) {
      throw new WalCorruptionException("M08W1 segment header CRC32C mismatch");
    }
    ByteBuffer bytes = ByteBuffer.wrap(encoded);
    if (bytes.getInt() != MAGIC || bytes.getInt() != VERSION) {
      throw new WalCorruptionException("unsupported M08W1 segment magic or version");
    }
    long shardId = bytes.getLong();
    long segmentId = bytes.getLong();
    long firstWalSequence = bytes.getLong();
    if (shardId <= 0 || segmentId <= 0 || firstWalSequence <= 0) {
      throw new WalCorruptionException("M08W1 segment header contains a non-positive identity");
    }
    return new SegmentHeader(shardId, segmentId, firstWalSequence);
  }

  static byte[] record(long walSequence, long applicationSequence, byte[] envelopeBytes) {
    int totalLength = Math.addExact(RECORD_OVERHEAD, envelopeBytes.length);
    ByteBuffer bytes = ByteBuffer.allocate(totalLength);
    bytes.putInt(totalLength);
    bytes.putInt(VERSION);
    bytes.putLong(walSequence);
    bytes.putLong(applicationSequence);
    bytes.putInt(envelopeBytes.length);
    bytes.put(envelopeBytes);
    bytes.putInt(checksum(bytes.array(), 0, totalLength - Integer.BYTES));
    return bytes.array();
  }

  static DecodedRecord decodeRecord(byte[] encoded) throws WalCorruptionException {
    if (encoded.length < MIN_RECORD_BYTES) {
      throw new WalCorruptionException("M08W1 record is shorter than its fixed fields");
    }
    ByteBuffer bytes = ByteBuffer.wrap(encoded);
    if (bytes.getInt() != encoded.length) {
      throw new WalCorruptionException("M08W1 record length does not match complete bytes");
    }
    if (bytes.getInt() != VERSION) {
      throw new WalCorruptionException("unsupported M08W1 record version");
    }
    long walSequence = bytes.getLong();
    long applicationSequence = bytes.getLong();
    int envelopeLength = bytes.getInt();
    if (walSequence <= 0 || applicationSequence <= 0 || envelopeLength < 0) {
      throw new WalCorruptionException("M08W1 record contains an invalid identity or length");
    }
    int expectedEnvelopeLength = encoded.length - RECORD_OVERHEAD;
    if (envelopeLength != expectedEnvelopeLength) {
      throw new WalCorruptionException("M08W1 envelope length disagrees with record framing");
    }
    byte[] envelope = new byte[envelopeLength];
    bytes.get(envelope);
    int claimed = bytes.getInt();
    int actual = checksum(encoded, 0, encoded.length - Integer.BYTES);
    if (claimed != actual) {
      throw new WalCorruptionException("M08W1 record CRC32C mismatch");
    }
    return new DecodedRecord(walSequence, applicationSequence, envelope);
  }

  private static int checksum(byte[] bytes, int offset, int length) {
    CRC32C checksum = new CRC32C();
    checksum.update(bytes, offset, length);
    return (int) checksum.getValue();
  }

  record SegmentHeader(long shardId, long segmentId, long firstWalSequence) {}

  record DecodedRecord(long walSequence, long applicationSequence, byte[] envelopeBytes) {
    DecodedRecord {
      envelopeBytes = Arrays.copyOf(envelopeBytes, envelopeBytes.length);
    }

    @Override
    public byte[] envelopeBytes() {
      return Arrays.copyOf(envelopeBytes, envelopeBytes.length);
    }
  }
}
