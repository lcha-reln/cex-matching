package io.github.lchareln.cex.matching.testkit;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;
import tools.jackson.databind.node.ArrayNode;

/** Independent namespace/size/hash inventory; it intentionally does not parse production files. */
final class M09FileInventory {
  private static final Pattern SNAPSHOT = Pattern.compile("snapshot-\\d{20}-\\d{20}\\.m09s1");
  private static final Pattern SNAPSHOT_TEMP =
      Pattern.compile("snapshot-\\d{20}-\\d{20}\\.m09s1\\.tmp");
  private static final Pattern SEGMENT = Pattern.compile("segment-\\d{20}\\.m08w1");
  private static final String DIRECTORY_LOCK = ".m08w1.lock";

  Inventory inspect(Path directory) {
    try (var paths = Files.list(directory)) {
      List<Entry> entries =
          paths
              .sorted(Comparator.comparing(path -> path.getFileName().toString()))
              .map(M09FileInventory::entry)
              .toList();
      return new Inventory(entries);
    } catch (IOException failure) {
      throw new IllegalStateException("cannot inventory M09 runtime directory", failure);
    }
  }

  private static Entry entry(Path path) {
    try {
      String name = path.getFileName().toString();
      boolean regularFile = Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS);
      if (!regularFile) {
        return new Entry(name, Kind.UNKNOWN, -1, "", false);
      }
      Kind kind =
          SNAPSHOT.matcher(name).matches()
              ? Kind.SNAPSHOT
              : SNAPSHOT_TEMP.matcher(name).matches()
                  ? Kind.SNAPSHOT_TEMP
                  : SEGMENT.matcher(name).matches()
                      ? Kind.WAL_SEGMENT
                      : DIRECTORY_LOCK.equals(name) ? Kind.DIRECTORY_LOCK : Kind.UNKNOWN;
      byte[] bytes = Files.readAllBytes(path);
      return new Entry(name, kind, bytes.length, Hashing.sha256Hex(bytes), true);
    } catch (IOException failure) {
      throw new IllegalStateException("cannot hash M09 runtime file " + path, failure);
    }
  }

  enum Kind {
    SNAPSHOT,
    SNAPSHOT_TEMP,
    WAL_SEGMENT,
    DIRECTORY_LOCK,
    UNKNOWN
  }

  record Entry(String name, Kind kind, long size, String sha256, boolean regularFile) {}

  record Inventory(List<Entry> entries) {
    Inventory {
      entries = List.copyOf(entries);
    }

    long count(Kind kind) {
      return entries.stream().filter(entry -> entry.kind() == kind).count();
    }

    List<Entry> snapshots() {
      return entries.stream().filter(entry -> entry.kind() == Kind.SNAPSHOT).toList();
    }

    List<Entry> segments() {
      return entries.stream().filter(entry -> entry.kind() == Kind.WAL_SEGMENT).toList();
    }

    List<Entry> directoryLocks() {
      return entries.stream().filter(entry -> entry.kind() == Kind.DIRECTORY_LOCK).toList();
    }

    List<Entry> unknown() {
      return entries.stream().filter(entry -> entry.kind() == Kind.UNKNOWN).toList();
    }

    ArrayNode report() {
      ArrayNode result = JsonSupport.MAPPER.createArrayNode();
      for (Entry entry : entries) {
        var node = result.addObject();
        node.put("name", entry.name());
        node.put("kind", entry.kind().name());
        node.put("bytes", entry.size());
        node.put("sha256", entry.sha256());
        node.put("regularFile", entry.regularFile());
      }
      return result;
    }
  }
}
