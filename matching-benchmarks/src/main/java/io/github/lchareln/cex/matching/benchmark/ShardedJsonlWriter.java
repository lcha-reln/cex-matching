package io.github.lchareln.cex.matching.benchmark;

import java.io.BufferedInputStream;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.zip.GZIPOutputStream;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Complete, non-sampled JSONL written as bounded deterministic-gzip shards. */
final class ShardedJsonlWriter implements AutoCloseable {
  static final int RECORDS_PER_SHARD = 25_000;
  static final long MAX_COMPRESSED_SHARD_BYTES = 90L * 1024 * 1024;

  private final ObjectMapper mapper;
  private final Path outputRoot;
  private final String artifactName;
  private final String recordSchemaVersion;
  private final List<ShardInfo> shards = new ArrayList<>();

  private BufferedWriter writer;
  private Path currentPath;
  private int currentShard;
  private int recordsInCurrentShard;
  private long totalRecords;
  private boolean closed;

  ShardedJsonlWriter(
      ObjectMapper mapper, Path outputRoot, String artifactName, String recordSchemaVersion)
      throws IOException {
    this.mapper = Objects.requireNonNull(mapper, "mapper");
    this.outputRoot = Objects.requireNonNull(outputRoot, "outputRoot");
    this.artifactName = requireToken(artifactName, "artifactName");
    this.recordSchemaVersion = requireToken(recordSchemaVersion, "recordSchemaVersion");
    Files.createDirectories(outputRoot.resolve(artifactName));
  }

  synchronized void write(ObjectNode record) throws IOException {
    if (closed) {
      throw new IllegalStateException("artifact writer is closed");
    }
    Objects.requireNonNull(record, "record");
    if (writer == null || recordsInCurrentShard == RECORDS_PER_SHARD) {
      closeCurrentShard();
      openShard();
    }
    if (!recordSchemaVersion.equals(record.path("schemaVersion").stringValue())) {
      throw new IllegalArgumentException("record does not carry the artifact schema version");
    }
    writer.write(mapper.writeValueAsString(record));
    writer.write('\n');
    recordsInCurrentShard++;
    totalRecords++;
  }

  synchronized long totalRecords() {
    return totalRecords;
  }

  synchronized List<ShardInfo> finish() throws IOException {
    close();
    return List.copyOf(shards);
  }

  /** Seals the current shard for verification while allowing a later write to open the next one. */
  synchronized List<ShardInfo> snapshot() throws IOException {
    if (closed) {
      throw new IllegalStateException("artifact writer is closed");
    }
    closeCurrentShard();
    return List.copyOf(shards);
  }

  @Override
  public synchronized void close() throws IOException {
    if (closed) {
      return;
    }
    closeCurrentShard();
    closed = true;
  }

  private void openShard() throws IOException {
    String fileName = "part-%05d.jsonl.gz".formatted(currentShard);
    currentPath = outputRoot.resolve(artifactName).resolve(fileName);
    OutputStream file =
        Files.newOutputStream(currentPath, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
    writer =
        new BufferedWriter(
            new java.io.OutputStreamWriter(
                new GZIPOutputStream(file, 64 * 1024), StandardCharsets.UTF_8),
            64 * 1024);
    recordsInCurrentShard = 0;
    currentShard++;
  }

  private void closeCurrentShard() throws IOException {
    if (writer == null) {
      return;
    }
    writer.close();
    long compressedBytes = Files.size(currentPath);
    if (compressedBytes > MAX_COMPRESSED_SHARD_BYTES) {
      throw new IOException(
          "compressed raw shard exceeds the 90 MiB publication bound: " + currentPath);
    }
    shards.add(
        new ShardInfo(
            outputRoot.relativize(currentPath).toString(),
            recordsInCurrentShard,
            compressedBytes,
            sha256(currentPath)));
    writer = null;
    currentPath = null;
    recordsInCurrentShard = 0;
  }

  private static String sha256(Path path) throws IOException {
    final MessageDigest digest;
    try {
      digest = MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is unavailable", impossible);
    }
    try (var input = new BufferedInputStream(Files.newInputStream(path), 64 * 1024)) {
      byte[] buffer = new byte[64 * 1024];
      int count;
      while ((count = input.read(buffer)) >= 0) {
        digest.update(buffer, 0, count);
      }
    }
    return HexFormat.of().formatHex(digest.digest());
  }

  private static String requireToken(String value, String name) {
    Objects.requireNonNull(value, name);
    if (!value.matches("[a-z0-9][a-z0-9.-]*")) {
      throw new IllegalArgumentException(name + " is not a stable lower-case token");
    }
    return value;
  }

  record ShardInfo(String relativePath, long recordCount, long compressedBytes, String sha256) {
    ShardInfo {
      Objects.requireNonNull(relativePath, "relativePath");
      Objects.requireNonNull(sha256, "sha256");
      if (recordCount <= 0
          || recordCount > RECORDS_PER_SHARD
          || compressedBytes <= 0
          || compressedBytes > MAX_COMPRESSED_SHARD_BYTES) {
        throw new IllegalArgumentException("invalid shard inventory");
      }
    }
  }
}
