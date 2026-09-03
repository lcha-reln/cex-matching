package io.github.lchareln.cex.matching.cluster;

import static java.nio.file.StandardCopyOption.ATOMIC_MOVE;
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;
import static java.nio.file.StandardOpenOption.CREATE_NEW;
import static java.nio.file.StandardOpenOption.WRITE;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Atomic JSON transport for harness-only M12 member diagnostics. */
public final class M12MemberStatusFile {
  private static final int MAX_STATUS_BYTES = 1024 * 1024;

  private M12MemberStatusFile() {}

  public static void write(Path target, M12MemberStatus status) throws IOException {
    Path normalized = target.toAbsolutePath().normalize();
    Path parent = normalized.getParent();
    if (parent == null) {
      throw new IllegalArgumentException("status target must have a parent directory");
    }
    byte[] encoded = encode(status).getBytes(StandardCharsets.UTF_8);
    if (encoded.length > MAX_STATUS_BYTES) {
      throw new IllegalArgumentException("M12 member status exceeds the file bound");
    }
    Files.createDirectories(parent);
    Path temporary =
        parent.resolve(
            "."
                + normalized.getFileName()
                + "."
                + ProcessHandle.current().pid()
                + "."
                + UUID.randomUUID()
                + ".tmp");
    try {
      try (FileChannel channel = FileChannel.open(temporary, CREATE_NEW, WRITE)) {
        ByteBuffer bytes = ByteBuffer.wrap(encoded);
        while (bytes.hasRemaining()) {
          channel.write(bytes);
        }
        channel.force(true);
      }
      Files.move(temporary, normalized, ATOMIC_MOVE, REPLACE_EXISTING);
    } finally {
      Files.deleteIfExists(temporary);
    }
  }

  public static M12MemberStatus read(Path source) throws IOException {
    byte[] encoded = Files.readAllBytes(source.toAbsolutePath().normalize());
    if (encoded.length == 0 || encoded.length > MAX_STATUS_BYTES) {
      throw new IOException("M12 member status length is outside its bound");
    }
    String json = new String(encoded, StandardCharsets.UTF_8);
    try {
      return new M12MemberStatus(
          stringValue(json, "schema"),
          longValue(json, "statusSequence"),
          longValue(json, "processId"),
          longValue(json, "processStartedAtEpochMillis"),
          longValue(json, "observedAtEpochMillis"),
          intValue(json, "clusterId"),
          intValue(json, "memberId"),
          intValue(json, "memberCount"),
          intValue(json, "quorumSize"),
          intValue(json, "appointedLeaderId"),
          booleanValue(json, "freshStart"),
          stringValue(json, "role"),
          stringValue(json, "electionState"),
          longValue(json, "leadershipTermId"),
          longValue(json, "commitPosition"),
          longValue(json, "logPosition"),
          longValue(json, "nextApplicationSequence"),
          intValue(json, "identityResultCount"),
          stringValue(json, "semanticStateDigest"),
          stringValue(json, "identityResultDigest"),
          intValue(json, "udpPortBlockBase"),
          stringValue(json, "rootDirectory"),
          stringValue(json, "aeronDirectory"),
          stringValue(json, "archiveDirectory"),
          stringValue(json, "clusterDirectory"),
          stringArray(json, "componentErrors"),
          stringArray(json, "diagnosticWarnings"),
          longValue(json, "droppedDiagnosticWarnings"));
    } catch (IllegalArgumentException failure) {
      throw new IOException("invalid M12 member status", failure);
    }
  }

  static String encode(M12MemberStatus status) {
    StringBuilder json = new StringBuilder(1_024);
    json.append("{\n");
    field(json, "schema", status.schema(), true);
    field(json, "statusSequence", status.statusSequence(), true);
    field(json, "processId", status.processId(), true);
    field(json, "processStartedAtEpochMillis", status.processStartedAtEpochMillis(), true);
    field(json, "observedAtEpochMillis", status.observedAtEpochMillis(), true);
    field(json, "clusterId", status.clusterId(), true);
    field(json, "memberId", status.memberId(), true);
    field(json, "memberCount", status.memberCount(), true);
    field(json, "quorumSize", status.quorumSize(), true);
    field(json, "appointedLeaderId", status.appointedLeaderId(), true);
    field(json, "freshStart", status.freshStart(), true);
    field(json, "role", status.role(), true);
    field(json, "electionState", status.electionState(), true);
    field(json, "leadershipTermId", status.leadershipTermId(), true);
    field(json, "commitPosition", status.commitPosition(), true);
    field(json, "logPosition", status.logPosition(), true);
    field(json, "nextApplicationSequence", status.nextApplicationSequence(), true);
    field(json, "identityResultCount", status.identityResultCount(), true);
    field(json, "semanticStateDigest", status.semanticStateDigest(), true);
    field(json, "identityResultDigest", status.identityResultDigest(), true);
    field(json, "udpPortBlockBase", status.udpPortBlockBase(), true);
    field(json, "rootDirectory", status.rootDirectory(), true);
    field(json, "aeronDirectory", status.aeronDirectory(), true);
    field(json, "archiveDirectory", status.archiveDirectory(), true);
    field(json, "clusterDirectory", status.clusterDirectory(), true);
    stringArrayField(json, "componentErrors", status.componentErrors(), true);
    stringArrayField(json, "diagnosticWarnings", status.diagnosticWarnings(), true);
    field(json, "droppedDiagnosticWarnings", status.droppedDiagnosticWarnings(), false);
    json.append("}\n");
    return json.toString();
  }

  private static void stringArrayField(
      StringBuilder json, String name, List<String> values, boolean comma) {
    json.append("  ");
    quoted(json, name);
    json.append(": [");
    for (int index = 0; index < values.size(); index++) {
      if (index > 0) {
        json.append(", ");
      }
      quoted(json, values.get(index));
    }
    json.append(comma ? "],\n" : "]\n");
  }

  private static void field(StringBuilder json, String name, String value, boolean comma) {
    json.append("  ");
    quoted(json, name);
    json.append(": ");
    quoted(json, value);
    json.append(comma ? ",\n" : "\n");
  }

  private static void field(StringBuilder json, String name, long value, boolean comma) {
    json.append("  ");
    quoted(json, name);
    json.append(": ").append(value).append(comma ? ",\n" : "\n");
  }

  private static void field(StringBuilder json, String name, boolean value, boolean comma) {
    json.append("  ");
    quoted(json, name);
    json.append(": ").append(value).append(comma ? ",\n" : "\n");
  }

  private static void quoted(StringBuilder json, String value) {
    json.append('"');
    for (int index = 0; index < value.length(); index++) {
      char character = value.charAt(index);
      switch (character) {
        case '"' -> json.append("\\\"");
        case '\\' -> json.append("\\\\");
        case '\b' -> json.append("\\b");
        case '\f' -> json.append("\\f");
        case '\n' -> json.append("\\n");
        case '\r' -> json.append("\\r");
        case '\t' -> json.append("\\t");
        default -> {
          if (character < 0x20) {
            json.append(String.format("\\u%04x", (int) character));
          } else {
            json.append(character);
          }
        }
      }
    }
    json.append('"');
  }

  private static String stringValue(String json, String field) {
    String raw = rawValue(json, field);
    if (raw.length() < 2 || raw.charAt(0) != '"' || raw.charAt(raw.length() - 1) != '"') {
      throw new IllegalArgumentException(field + " is not a JSON string");
    }
    return unescape(raw.substring(1, raw.length() - 1));
  }

  private static int intValue(String json, String field) {
    return Math.toIntExact(longValue(json, field));
  }

  private static long longValue(String json, String field) {
    return Long.parseLong(rawValue(json, field));
  }

  private static boolean booleanValue(String json, String field) {
    String raw = rawValue(json, field);
    if (!"true".equals(raw) && !"false".equals(raw)) {
      throw new IllegalArgumentException(field + " is not a JSON boolean");
    }
    return Boolean.parseBoolean(raw);
  }

  private static List<String> stringArray(String json, String field) {
    String raw = rawValue(json, field);
    if (raw.length() < 2 || raw.charAt(0) != '[' || raw.charAt(raw.length() - 1) != ']') {
      throw new IllegalArgumentException(field + " is not a JSON array");
    }
    List<String> values = new ArrayList<>();
    int cursor = 1;
    while (true) {
      cursor = skipWhitespace(raw, cursor);
      if (cursor == raw.length() - 1) {
        return List.copyOf(values);
      }
      if (raw.charAt(cursor) != '"') {
        throw new IllegalArgumentException(field + " must contain only strings");
      }
      int end = endOfString(raw, cursor);
      values.add(unescape(raw.substring(cursor + 1, end)));
      cursor = skipWhitespace(raw, end + 1);
      if (cursor < raw.length() - 1 && raw.charAt(cursor) == ',') {
        cursor++;
      } else if (cursor != raw.length() - 1) {
        throw new IllegalArgumentException(field + " has invalid JSON separators");
      }
    }
  }

  private static String rawValue(String json, String field) {
    String needle = '"' + field + '"';
    int name = json.indexOf(needle);
    if (name < 0) {
      throw new IllegalArgumentException("missing JSON field: " + field);
    }
    int colon = json.indexOf(':', name + needle.length());
    if (colon < 0) {
      throw new IllegalArgumentException("missing JSON separator for: " + field);
    }
    int start = skipWhitespace(json, colon + 1);
    if (start >= json.length()) {
      throw new IllegalArgumentException("missing JSON value for: " + field);
    }
    int end;
    char first = json.charAt(start);
    if (first == '"') {
      end = endOfString(json, start) + 1;
    } else if (first == '[') {
      end = endOfArray(json, start) + 1;
    } else {
      end = start;
      while (end < json.length()
          && json.charAt(end) != ','
          && json.charAt(end) != '}'
          && !Character.isWhitespace(json.charAt(end))) {
        end++;
      }
    }
    return json.substring(start, end);
  }

  private static int endOfString(String json, int quote) {
    boolean escaped = false;
    for (int index = quote + 1; index < json.length(); index++) {
      char character = json.charAt(index);
      if (escaped) {
        escaped = false;
      } else if (character == '\\') {
        escaped = true;
      } else if (character == '"') {
        return index;
      }
    }
    throw new IllegalArgumentException("unterminated JSON string");
  }

  private static int endOfArray(String json, int opening) {
    boolean inString = false;
    boolean escaped = false;
    for (int index = opening + 1; index < json.length(); index++) {
      char character = json.charAt(index);
      if (inString) {
        if (escaped) {
          escaped = false;
        } else if (character == '\\') {
          escaped = true;
        } else if (character == '"') {
          inString = false;
        }
      } else if (character == '"') {
        inString = true;
      } else if (character == ']') {
        return index;
      }
    }
    throw new IllegalArgumentException("unterminated JSON array");
  }

  private static int skipWhitespace(String value, int start) {
    int cursor = start;
    while (cursor < value.length() && Character.isWhitespace(value.charAt(cursor))) {
      cursor++;
    }
    return cursor;
  }

  private static String unescape(String encoded) {
    StringBuilder decoded = new StringBuilder(encoded.length());
    for (int index = 0; index < encoded.length(); index++) {
      char character = encoded.charAt(index);
      if (character != '\\') {
        decoded.append(character);
        continue;
      }
      if (++index >= encoded.length()) {
        throw new IllegalArgumentException("incomplete JSON escape");
      }
      char escaped = encoded.charAt(index);
      switch (escaped) {
        case '"', '\\', '/' -> decoded.append(escaped);
        case 'b' -> decoded.append('\b');
        case 'f' -> decoded.append('\f');
        case 'n' -> decoded.append('\n');
        case 'r' -> decoded.append('\r');
        case 't' -> decoded.append('\t');
        case 'u' -> {
          if (index + 4 >= encoded.length()) {
            throw new IllegalArgumentException("incomplete JSON unicode escape");
          }
          decoded.append((char) Integer.parseInt(encoded.substring(index + 1, index + 5), 16));
          index += 4;
        }
        default -> throw new IllegalArgumentException("invalid JSON escape");
      }
    }
    return decoded.toString();
  }
}
