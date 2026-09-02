package io.github.lchareln.cex.matching.benchmark;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

class ShardedJsonlWriterTest {
  @TempDir java.nio.file.Path temporary;

  @Test
  void writesEveryRecordToBoundedGzipShardsWithAnInventory() throws Exception {
    var mapper = JsonMapper.builder().build();
    ShardedJsonlWriter writer =
        new ShardedJsonlWriter(mapper, temporary, "raw-arrivals", "matching.m10.raw-arrival.v1");
    int total = ShardedJsonlWriter.RECORDS_PER_SHARD + 1;
    for (int index = 0; index < total; index++) {
      var record = mapper.createObjectNode();
      record.put("schemaVersion", "matching.m10.raw-arrival.v1");
      record.put("index", index);
      writer.write(record);
    }

    var shards = writer.finish();
    assertEquals(2, shards.size());
    assertEquals(ShardedJsonlWriter.RECORDS_PER_SHARD, shards.getFirst().recordCount());
    assertEquals(1, shards.getLast().recordCount());
    assertTrue(
        shards.stream()
            .allMatch(
                value -> value.compressedBytes() <= ShardedJsonlWriter.MAX_COMPRESSED_SHARD_BYTES));

    List<Integer> actual = new ArrayList<>();
    for (var shard : shards) {
      try (var input =
              new GZIPInputStream(Files.newInputStream(temporary.resolve(shard.relativePath())));
          var reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
        String line;
        while ((line = reader.readLine()) != null) {
          actual.add(mapper.readTree(line).path("index").asInt());
        }
      }
    }
    assertEquals(total, actual.size());
    assertEquals(0, actual.getFirst());
    assertEquals(total - 1, actual.getLast());
  }
}
