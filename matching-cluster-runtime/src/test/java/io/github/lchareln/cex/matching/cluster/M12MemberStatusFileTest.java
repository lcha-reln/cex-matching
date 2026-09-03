package io.github.lchareln.cex.matching.cluster;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class M12MemberStatusFileTest {
  private static final String EMPTY_SEMANTIC_DIGEST =
      "d0b702688d3788ca13e15aef2a5a13a86b19a10d8ac3a0a8c22bcfa398ef0f50";
  private static final String EMPTY_IDENTITY_DIGEST =
      "df3f619804a92fdb4057192dc43dd748ea778adc52bc498ce80524c014b81119";

  @TempDir Path temporaryDirectory;

  @Test
  void atomicallyPublishesAndStrictlyReadsEscapedStatus() throws Exception {
    M12MemberStatus status =
        status(1, List.of("java.lang.IllegalStateException: bad \"line\"\nnext"));
    Path target = temporaryDirectory.resolve("node-0/diagnostics/member-status.json");

    M12MemberStatusFile.write(target, status);

    assertEquals(status, M12MemberStatusFile.read(target));
    try (var files = Files.list(target.getParent())) {
      assertFalse(files.anyMatch(path -> path.getFileName().toString().endsWith(".tmp")));
    }
  }

  @Test
  void replacementNeverLeavesAParsableHybrid() throws Exception {
    Path target = temporaryDirectory.resolve("member-status.json");
    for (int sequence = 1; sequence <= 100; sequence++) {
      M12MemberStatus expected = status(sequence, List.of());
      M12MemberStatusFile.write(target, expected);
      assertEquals(expected, M12MemberStatusFile.read(target));
    }
  }

  @Test
  void rejectsMalformedOrUnsupportedStatus() throws Exception {
    Path malformed = temporaryDirectory.resolve("malformed.json");
    Files.writeString(malformed, "{}", StandardCharsets.UTF_8);
    assertThrows(IOException.class, () -> M12MemberStatusFile.read(malformed));

    Path unsupported = temporaryDirectory.resolve("unsupported.json");
    M12MemberStatusFile.write(unsupported, status(1, List.of()));
    Files.writeString(
        unsupported,
        Files.readString(unsupported).replace(M12MemberStatus.SCHEMA, "unknown.schema"),
        StandardCharsets.UTF_8);
    assertThrows(IOException.class, () -> M12MemberStatusFile.read(unsupported));
  }

  private M12MemberStatus status(long sequence, List<String> errors) {
    Path root = temporaryDirectory.resolve("cluster").toAbsolutePath().normalize();
    return new M12MemberStatus(
        M12MemberStatus.SCHEMA,
        sequence,
        12_345,
        1_700_000_000_000L,
        1_700_000_000_100L + sequence,
        12,
        0,
        3,
        2,
        -1,
        true,
        "LEADER",
        "CLOSED",
        1,
        64,
        64,
        1,
        0,
        EMPTY_SEMANTIC_DIGEST,
        EMPTY_IDENTITY_DIGEST,
        22_000,
        root.toString(),
        root.resolve("node-0/aeron").toString(),
        root.resolve("node-0/archive").toString(),
        root.resolve("node-0/cluster").toString(),
        errors,
        List.of("io.aeron.cluster.client.ClusterEvent: WARN - leader heartbeat timeout"),
        0);
  }
}
