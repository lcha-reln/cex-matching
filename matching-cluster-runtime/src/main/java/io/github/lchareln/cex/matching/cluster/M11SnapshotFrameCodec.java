package io.github.lchareln.cex.matching.cluster;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/** Strict bounded application framing over the Aeron snapshot Publication. */
public final class M11SnapshotFrameCodec {
  public static final int MAGIC = 0x4D313146; // M11F
  public static final int VERSION = 1;
  public static final int MAX_FRAME_PAYLOAD = 4 * 1024;

  private static final int DIGEST_BYTES = 32;
  private static final int HEADER_BYTES = Integer.BYTES * 7 + Long.BYTES + DIGEST_BYTES;

  public List<byte[]> encode(byte[] snapshot, long snapshotSequence) {
    Objects.requireNonNull(snapshot, "snapshot");
    if (snapshot.length == 0 || snapshot.length > M11SnapshotCodec.MAX_SNAPSHOT_BYTES) {
      throw new IllegalArgumentException("snapshot frame input is outside its bound");
    }
    if (snapshotSequence < 0) {
      throw new IllegalArgumentException("snapshot sequence must not be negative");
    }
    int frameCount = Math.floorDiv(snapshot.length - 1, MAX_FRAME_PAYLOAD) + 1;
    byte[] digest = M11Digests.sha256(snapshot);
    List<byte[]> frames = new ArrayList<>(frameCount);
    for (int index = 0, offset = 0; index < frameCount; index++) {
      int payloadLength = Math.min(MAX_FRAME_PAYLOAD, snapshot.length - offset);
      M11Binary.Writer writer = new M11Binary.Writer();
      writer.putInt(MAGIC);
      writer.putInt(VERSION);
      writer.putLong(snapshotSequence);
      writer.putInt(snapshot.length);
      writer.putInt(frameCount);
      writer.putInt(index);
      writer.putInt(offset);
      writer.putInt(payloadLength);
      writer.putBytes(digest);
      writer.putBytes(Arrays.copyOfRange(snapshot, offset, offset + payloadLength));
      frames.add(writer.toByteArray());
      offset += payloadLength;
    }
    return List.copyOf(frames);
  }

  public Accumulator accumulator() {
    return new Accumulator();
  }

  /** Single-use ordered frame accumulator. */
  public static final class Accumulator {
    private byte[] snapshot;
    private byte[] digest;
    private long snapshotSequence = -1;
    private int frameCount;
    private int nextFrame;
    private int nextOffset;

    public void accept(byte[] frame) throws M11ProtocolException {
      Objects.requireNonNull(frame, "frame");
      if (frame.length < HEADER_BYTES || frame.length > HEADER_BYTES + MAX_FRAME_PAYLOAD) {
        throw failure(M11ProtocolException.Code.LENGTH_LIMIT, "snapshot frame length is invalid");
      }
      M11Binary.Reader reader = new M11Binary.Reader(frame);
      if (reader.getInt() != MAGIC) {
        throw failure(M11ProtocolException.Code.INVALID_MAGIC, "snapshot frame magic is invalid");
      }
      if (reader.getInt() != VERSION) {
        throw failure(
            M11ProtocolException.Code.UNSUPPORTED_VERSION, "snapshot frame version is unsupported");
      }
      long incomingSequence = reader.getLong();
      int totalLength = reader.getInt();
      int incomingCount = reader.getInt();
      int incomingIndex = reader.getInt();
      int incomingOffset = reader.getInt();
      int payloadLength = reader.getInt();
      byte[] incomingDigest = reader.getBytes(DIGEST_BYTES);
      int expectedPayloadLength =
          incomingOffset < 0 || incomingOffset >= totalLength
              ? -1
              : Math.min(MAX_FRAME_PAYLOAD, totalLength - incomingOffset);
      if (totalLength <= 0
          || totalLength > M11SnapshotCodec.MAX_SNAPSHOT_BYTES
          || incomingCount <= 0
          || incomingCount != Math.floorDiv(totalLength - 1, MAX_FRAME_PAYLOAD) + 1
          || incomingIndex != nextFrame
          || incomingOffset != nextOffset
          || payloadLength <= 0
          || payloadLength > MAX_FRAME_PAYLOAD
          || payloadLength != expectedPayloadLength
          || frame.length != HEADER_BYTES + payloadLength) {
        throw failure(
            M11ProtocolException.Code.NON_CANONICAL,
            "snapshot frame order, bounds, or length are invalid");
      }
      if (snapshot == null) {
        if (incomingSequence < 0) {
          throw failure(
              M11ProtocolException.Code.INVALID_VALUE, "snapshot sequence must not be negative");
        }
        snapshot = new byte[totalLength];
        digest = incomingDigest;
        snapshotSequence = incomingSequence;
        frameCount = incomingCount;
      } else if (snapshot.length != totalLength
          || snapshotSequence != incomingSequence
          || frameCount != incomingCount
          || !Arrays.equals(digest, incomingDigest)) {
        throw failure(
            M11ProtocolException.Code.NON_CANONICAL, "snapshot frame metadata changed mid-stream");
      }
      byte[] payload = reader.getBytes(payloadLength);
      reader.requireExhausted();
      System.arraycopy(payload, 0, snapshot, incomingOffset, payloadLength);
      nextFrame++;
      nextOffset += payloadLength;
    }

    public boolean complete() {
      return snapshot != null && nextFrame == frameCount && nextOffset == snapshot.length;
    }

    public long snapshotSequence() throws M11ProtocolException {
      requireComplete();
      return snapshotSequence;
    }

    public byte[] finish() throws M11ProtocolException {
      requireComplete();
      if (!Arrays.equals(digest, M11Digests.sha256(snapshot))) {
        throw failure(
            M11ProtocolException.Code.CHECKSUM_MISMATCH, "reassembled snapshot digest disagrees");
      }
      return snapshot.clone();
    }

    private void requireComplete() throws M11ProtocolException {
      if (!complete()) {
        throw failure(M11ProtocolException.Code.TRUNCATED, "snapshot frame stream is incomplete");
      }
    }
  }

  private static M11ProtocolException failure(M11ProtocolException.Code code, String message) {
    return new M11ProtocolException(code, message);
  }
}
