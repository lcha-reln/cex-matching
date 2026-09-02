package io.github.lchareln.cex.matching.benchmark;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/** Streaming replay input whose v2 bytes are reconstructible from accepted-trace JSONL. */
final class RecoveryTrace implements AutoCloseable {
  private static final int MAGIC = 0x4d313052; // M10R
  private static final int VERSION = 2;
  private static final int MAX_ENVELOPE_BYTES = 1024 * 1024;
  private static final int MAX_TEXT_BYTES = 4 * 1024;

  private final Path path;
  private final String traceId;
  private final DataOutputStream output;
  private long records;
  private boolean closed;

  RecoveryTrace(Path path, String traceId) throws IOException {
    this.path = Objects.requireNonNull(path, "path").toAbsolutePath().normalize();
    this.traceId = requireText(traceId, "traceId");
    output =
        new DataOutputStream(
            new BufferedOutputStream(
                Files.newOutputStream(
                    this.path,
                    java.nio.file.StandardOpenOption.CREATE_NEW,
                    java.nio.file.StandardOpenOption.WRITE),
                64 * 1024));
    output.writeInt(MAGIC);
    output.writeInt(VERSION);
    writeText(output, traceId);
  }

  long append(
      QualificationArtifactSink.PointIdentity point,
      String logicalOperationId,
      int attempt,
      byte[] envelope,
      String resultDigest,
      String semanticStateDigest)
      throws IOException {
    if (closed) {
      throw new IllegalStateException("recovery trace is closed");
    }
    Objects.requireNonNull(point, "point");
    requireText(logicalOperationId, "logicalOperationId");
    Objects.requireNonNull(envelope, "envelope");
    if (attempt < 0 || envelope.length == 0 || envelope.length > MAX_ENVELOPE_BYTES) {
      throw new IllegalArgumentException("invalid recovery trace entry");
    }
    long ordinal = Math.incrementExact(records);
    output.writeLong(ordinal);
    writeText(output, point.pointId());
    writeText(output, logicalOperationId);
    output.writeInt(attempt);
    output.writeInt(envelope.length);
    output.write(envelope);
    writeText(output, resultDigest);
    writeText(output, semanticStateDigest);
    records = ordinal;
    return ordinal;
  }

  String traceId() {
    return traceId;
  }

  long records() {
    return records;
  }

  Path path() {
    return path;
  }

  @Override
  public void close() throws IOException {
    if (!closed) {
      output.close();
      closed = true;
    }
  }

  String sha256() throws IOException {
    if (!closed) {
      throw new IllegalStateException("close recovery trace before hashing");
    }
    MessageDigest digest = sha256Digest();
    try (var input = new BufferedInputStream(Files.newInputStream(path), 64 * 1024)) {
      byte[] buffer = new byte[64 * 1024];
      int count;
      while ((count = input.read(buffer)) >= 0) {
        digest.update(buffer, 0, count);
      }
    }
    return HexFormat.of().formatHex(digest.digest());
  }

  static long read(Path path, String expectedTraceId, EntryConsumer consumer) throws IOException {
    long records = 0;
    try (var input =
        new DataInputStream(new BufferedInputStream(Files.newInputStream(path), 64 * 1024))) {
      if (input.readInt() != MAGIC || input.readInt() != VERSION) {
        throw new IOException("unsupported recovery trace header");
      }
      String traceId = readText(input);
      if (!expectedTraceId.equals(traceId)) {
        throw new IOException("recovery trace identity changed");
      }
      while (true) {
        final long ordinal;
        try {
          ordinal = input.readLong();
        } catch (EOFException end) {
          break;
        }
        if (ordinal != Math.incrementExact(records)) {
          throw new IOException("recovery trace ordinal is not contiguous");
        }
        String pointId = readText(input);
        String logicalOperationId = readText(input);
        int attempt = input.readInt();
        int envelopeLength = input.readInt();
        if (attempt < 0 || envelopeLength <= 0 || envelopeLength > MAX_ENVELOPE_BYTES) {
          throw new IOException("invalid recovery trace entry");
        }
        byte[] envelope = input.readNBytes(envelopeLength);
        if (envelope.length != envelopeLength) {
          throw new EOFException("truncated recovery trace envelope");
        }
        String resultDigest = readText(input);
        String semanticDigest = readText(input);
        consumer.accept(
            new Entry(
                ordinal,
                pointId,
                logicalOperationId,
                attempt,
                envelope,
                resultDigest,
                semanticDigest));
        records = ordinal;
      }
    }
    return records;
  }

  static MessageDigest beginReconstructedDigest(String traceId) {
    MessageDigest digest = sha256Digest();
    updateInt(digest, MAGIC);
    updateInt(digest, VERSION);
    updateText(digest, requireText(traceId, "traceId"));
    return digest;
  }

  static void updateReconstructedDigest(MessageDigest digest, Entry entry) {
    updateLong(digest, entry.ordinal());
    updateText(digest, entry.pointId());
    updateText(digest, entry.logicalOperationId());
    updateInt(digest, entry.attempt());
    byte[] envelope = entry.envelope();
    updateInt(digest, envelope.length);
    digest.update(envelope);
    updateText(digest, entry.resultDigest());
    updateText(digest, entry.semanticStateDigest());
  }

  private static void writeText(DataOutputStream output, String value) throws IOException {
    byte[] bytes = requireText(value, "trace text").getBytes(StandardCharsets.UTF_8);
    output.writeInt(bytes.length);
    output.write(bytes);
  }

  private static String readText(DataInputStream input) throws IOException {
    int length = input.readInt();
    if (length <= 0 || length > MAX_TEXT_BYTES) {
      throw new IOException("invalid recovery trace text length");
    }
    byte[] bytes = input.readNBytes(length);
    if (bytes.length != length) {
      throw new EOFException("truncated recovery trace text");
    }
    return new String(bytes, StandardCharsets.UTF_8);
  }

  private static String requireText(String value, String name) {
    Objects.requireNonNull(value, name);
    if (value.isBlank() || value.getBytes(StandardCharsets.UTF_8).length > MAX_TEXT_BYTES) {
      throw new IllegalArgumentException(name + " must be non-blank and bounded");
    }
    return value;
  }

  private static MessageDigest sha256Digest() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is unavailable", impossible);
    }
  }

  private static void updateText(MessageDigest digest, String value) {
    byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
    updateInt(digest, bytes.length);
    digest.update(bytes);
  }

  private static void updateInt(MessageDigest digest, int value) {
    digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(value).array());
  }

  private static void updateLong(MessageDigest digest, long value) {
    digest.update(ByteBuffer.allocate(Long.BYTES).putLong(value).array());
  }

  record Entry(
      long ordinal,
      String pointId,
      String logicalOperationId,
      int attempt,
      byte[] envelope,
      String resultDigest,
      String semanticStateDigest) {
    Entry {
      if (ordinal <= 0 || attempt < 0) {
        throw new IllegalArgumentException("invalid recovery trace ordinal or attempt");
      }
      requireText(pointId, "pointId");
      requireText(logicalOperationId, "logicalOperationId");
      envelope = envelope.clone();
      requireText(resultDigest, "resultDigest");
      requireText(semanticStateDigest, "semanticStateDigest");
    }

    @Override
    public byte[] envelope() {
      return envelope.clone();
    }
  }

  @FunctionalInterface
  interface EntryConsumer {
    void accept(Entry entry) throws IOException;
  }
}
