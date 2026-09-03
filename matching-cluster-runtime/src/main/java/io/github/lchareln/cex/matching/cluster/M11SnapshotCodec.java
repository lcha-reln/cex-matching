package io.github.lchareln.cex.matching.cluster;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;

/** Byte-exact M11S1/M11S2 codec; the current writer emits S2 only. */
public final class M11SnapshotCodec {
  public static final int MAGIC = 0x4D313153; // M11S
  public static final int TEMPLATE_ID = 3;
  public static final int MIN_READABLE_VERSION = 1;
  public static final int CURRENT_VERSION = 2;
  public static final int MAX_SNAPSHOT_BYTES = 256 * 1024 * 1024;

  private static final int SHA256_BYTES = 32;
  private static final int TRAILER_BYTES = Integer.BYTES + SHA256_BYTES;

  private final M11RuntimeStateCodec stateCodec = new M11RuntimeStateCodec();

  public byte[] encodeCurrent(M11RuntimeState state) {
    return encode(CURRENT_VERSION, state);
  }

  public byte[] encode(int schemaVersion, M11RuntimeState state) {
    Objects.requireNonNull(state, "state");
    if (schemaVersion < MIN_READABLE_VERSION || schemaVersion > CURRENT_VERSION) {
      throw new IllegalArgumentException("unsupported snapshot schema version");
    }
    byte[] stateBytes = stateCodec.encode(state);
    M11Binary.Writer writer = new M11Binary.Writer();
    writer.putInt(MAGIC);
    writer.putInt(schemaVersion);
    writer.putInt(TEMPLATE_ID);
    if (schemaVersion == CURRENT_VERSION) {
      writer.putInt(MIN_READABLE_VERSION);
      writer.putInt(CURRENT_VERSION);
    }
    writer.putByteArray(stateBytes);
    if (schemaVersion == CURRENT_VERSION) {
      writer.putBytes(M11Digests.sha256(stateCodec.encodeIdentityTable(state.identityBindings())));
      writer.putBytes(HexFormat.of().parseHex(state.commandState().semanticStateDigest()));
    }
    byte[] crcInput = writer.toByteArray();
    writer.putInt(M11Digests.crc32c(crcInput));
    writer.putBytes(M11Digests.sha256(writer.toByteArray()));
    byte[] encoded = writer.toByteArray();
    if (encoded.length > MAX_SNAPSHOT_BYTES) {
      throw new IllegalArgumentException("M11 snapshot exceeds the format bound");
    }
    return encoded;
  }

  public M11Snapshot decodeCanonical(byte[] encoded) throws M11ProtocolException {
    Objects.requireNonNull(encoded, "encoded");
    if (encoded.length < Integer.BYTES * 4 + TRAILER_BYTES || encoded.length > MAX_SNAPSHOT_BYTES) {
      throw failure(M11ProtocolException.Code.LENGTH_LIMIT, "snapshot length is outside its bound");
    }
    int digestOffset = encoded.length - SHA256_BYTES;
    if (!Arrays.equals(
        Arrays.copyOfRange(encoded, digestOffset, encoded.length),
        M11Digests.sha256(Arrays.copyOf(encoded, digestOffset)))) {
      throw failure(M11ProtocolException.Code.CHECKSUM_MISMATCH, "snapshot SHA-256 disagrees");
    }
    int crcOffset = digestOffset - Integer.BYTES;
    int claimedCrc = ByteBuffer.wrap(encoded, crcOffset, Integer.BYTES).getInt();
    if (claimedCrc != M11Digests.crc32c(Arrays.copyOf(encoded, crcOffset))) {
      throw failure(M11ProtocolException.Code.CHECKSUM_MISMATCH, "snapshot CRC32C disagrees");
    }

    M11Binary.Reader reader = new M11Binary.Reader(Arrays.copyOf(encoded, crcOffset));
    if (reader.getInt() != MAGIC) {
      throw failure(M11ProtocolException.Code.INVALID_MAGIC, "snapshot magic is invalid");
    }
    int version = reader.getInt();
    if (version < MIN_READABLE_VERSION || version > CURRENT_VERSION) {
      throw failure(
          M11ProtocolException.Code.UNSUPPORTED_VERSION, "snapshot version is unsupported");
    }
    if (reader.getInt() != TEMPLATE_ID) {
      throw failure(M11ProtocolException.Code.INVALID_VALUE, "snapshot template is unsupported");
    }
    if (version == CURRENT_VERSION
        && (reader.getInt() != MIN_READABLE_VERSION || reader.getInt() != CURRENT_VERSION)) {
      throw failure(
          M11ProtocolException.Code.UNSUPPORTED_VERSION,
          "snapshot readable/writer compatibility bounds disagree");
    }
    byte[] stateBytes = reader.getByteArray(M11RuntimeStateCodec.MAX_STATE_BYTES);
    byte[] claimedIdentityDigest = null;
    byte[] claimedSemanticDigest = null;
    if (version == CURRENT_VERSION) {
      claimedIdentityDigest = reader.getBytes(SHA256_BYTES);
      claimedSemanticDigest = reader.getBytes(SHA256_BYTES);
    }
    reader.requireExhausted();
    M11RuntimeState state = stateCodec.decodeCanonical(stateBytes);
    if (version == CURRENT_VERSION) {
      byte[] actualIdentityDigest =
          M11Digests.sha256(stateCodec.encodeIdentityTable(state.identityBindings()));
      byte[] actualSemanticDigest =
          HexFormat.of().parseHex(state.commandState().semanticStateDigest());
      if (!Arrays.equals(claimedIdentityDigest, actualIdentityDigest)
          || !Arrays.equals(claimedSemanticDigest, actualSemanticDigest)) {
        throw failure(
            M11ProtocolException.Code.CHECKSUM_MISMATCH,
            "snapshot application integrity fields disagree");
      }
    }
    final M11Snapshot snapshot;
    try {
      snapshot = new M11Snapshot(version, state);
    } catch (IllegalArgumentException failure) {
      throw new M11ProtocolException(
          M11ProtocolException.Code.INVALID_VALUE, "snapshot state is invalid", failure);
    }
    if (!Arrays.equals(encoded, encode(version, state))) {
      throw failure(M11ProtocolException.Code.NON_CANONICAL, "snapshot is not canonical");
    }
    return snapshot;
  }

  private static M11ProtocolException failure(M11ProtocolException.Code code, String message) {
    return new M11ProtocolException(code, message);
  }
}
