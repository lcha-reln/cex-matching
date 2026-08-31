package io.github.lchareln.cex.matching.local;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;

/** Strict canonical M08C1 envelope codec for local journal ingress and recovery. */
public final class M08EnvelopeCodec {
  public static final int MAGIC = 0x4D303843; // M08C
  public static final int VERSION = 1;
  public static final int MAX_ENVELOPE_BYTES = 1024 * 1024;

  private static final int MAX_PRODUCER_BYTES = 512;
  private static final int HASH_BYTES = 32;

  private final M08CommandCodec commandCodec;

  public M08EnvelopeCodec() {
    this(new M08CommandCodec());
  }

  public M08EnvelopeCodec(M08CommandCodec commandCodec) {
    this.commandCodec = Objects.requireNonNull(commandCodec, "commandCodec");
  }

  public byte[] encode(
      String producerId,
      long producerEpoch,
      long shardId,
      long producerSequence,
      UUID commandId,
      M08Command command) {
    byte[] payload = commandCodec.encode(command);
    M08Envelope envelope =
        new M08Envelope(
            new Slot(producerId, producerEpoch, shardId, producerSequence),
            commandId,
            HexFormat.of().formatHex(Sha256.digest(payload)),
            payload,
            command);
    return encodeDecoded(envelope);
  }

  public M08Envelope decodeCanonical(byte[] encoded, long expectedShard)
      throws StructuralRejectionException {
    Objects.requireNonNull(encoded, "encoded");
    if (encoded.length == 0 || encoded.length > MAX_ENVELOPE_BYTES) {
      throw new StructuralRejectionException(
          StructuralRejectionCode.ENVELOPE_SIZE_LIMIT,
          "M08C1 envelope is empty or exceeds the size limit");
    }
    M08Envelope decoded = decode(encoded);
    if (decoded.slot().shardId() != expectedShard) {
      throw new StructuralRejectionException(
          StructuralRejectionCode.WRONG_SHARD, "M08C1 envelope targets another shard");
    }
    if (!Arrays.equals(encoded, encodeDecoded(decoded))) {
      throw new StructuralRejectionException(
          StructuralRejectionCode.NON_CANONICAL_ENVELOPE,
          "M08C1 envelope does not round-trip byte-for-byte");
    }
    return decoded;
  }

  private M08Envelope decode(byte[] encoded) throws StructuralRejectionException {
    BinaryEncoding.Reader reader = new BinaryEncoding.Reader(encoded);
    if (reader.getInt() != MAGIC || reader.getInt() != VERSION) {
      throw new StructuralRejectionException(
          StructuralRejectionCode.MALFORMED_ENVELOPE, "unsupported M08C1 magic or version");
    }
    String producerId = reader.getString(MAX_PRODUCER_BYTES);
    long epoch = reader.getLong();
    long shard = reader.getLong();
    long sequence = reader.getLong();
    UUID commandId = new UUID(reader.getLong(), reader.getLong());
    byte[] claimedHash = reader.getBytes(HASH_BYTES, HASH_BYTES);
    byte[] payload = reader.getByteArray(M08CommandCodec.MAX_COMMAND_BYTES);
    if (reader.hasRemaining()) {
      throw new StructuralRejectionException(
          StructuralRejectionCode.NON_CANONICAL_ENVELOPE, "M08C1 envelope contains trailing bytes");
    }

    final Slot slot;
    try {
      slot = new Slot(producerId, epoch, shard, sequence);
    } catch (IllegalArgumentException failure) {
      throw new StructuralRejectionException(
          StructuralRejectionCode.INVALID_ENVELOPE_IDENTITY,
          "M08C1 producer identity is invalid",
          failure);
    }
    byte[] actualHash = Sha256.digest(payload);
    if (!Arrays.equals(claimedHash, actualHash)) {
      throw new StructuralRejectionException(
          StructuralRejectionCode.PAYLOAD_HASH_MISMATCH,
          "claimed M08C1 payload hash does not match canonical payload bytes");
    }
    M08Command command = commandCodec.decodeCanonical(payload);
    return new M08Envelope(slot, commandId, HexFormat.of().formatHex(actualHash), payload, command);
  }

  private static byte[] encodeDecoded(M08Envelope envelope) {
    BinaryEncoding.Writer writer = new BinaryEncoding.Writer();
    writer.putInt(MAGIC);
    writer.putInt(VERSION);
    writer.putString(envelope.slot().producerId());
    writer.putLong(envelope.slot().producerEpoch());
    writer.putLong(envelope.slot().shardId());
    writer.putLong(envelope.slot().producerSequence());
    writer.putLong(envelope.commandId().getMostSignificantBits());
    writer.putLong(envelope.commandId().getLeastSignificantBits());
    writer.putBytes(HexFormat.of().parseHex(envelope.payloadHash()));
    writer.putByteArray(envelope.commandPayload());
    byte[] encoded = writer.toByteArray();
    if (encoded.length > MAX_ENVELOPE_BYTES
        || envelope.slot().producerId().getBytes(StandardCharsets.UTF_8).length
            > MAX_PRODUCER_BYTES) {
      throw new IllegalArgumentException("M08C1 envelope exceeds canonical limits");
    }
    return encoded;
  }
}
