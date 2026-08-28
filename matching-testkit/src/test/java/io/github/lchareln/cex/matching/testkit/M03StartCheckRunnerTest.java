package io.github.lchareln.cex.matching.testkit;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

final class M03StartCheckRunnerTest {
  @Test
  void preservesTheImmutableStartProfileAfterReferenceImplementationBegins() {
    byte[] profile =
        M03TestPaths.readBytes(M02TestPaths.root().resolve(M03StartCheckRunner.GENERATOR_PATH));

    assertEquals("GOAL_NOT_IMPLEMENTED", M03StartCheckRunner.STATUS);
    assertEquals(M03StartCheckRunner.FROZEN_GENERATOR_SHA256, Hashing.sha256Hex(profile));
    assertEquals(256, M03StartCheckRunner.HISTORIES);
    assertEquals(64, M03StartCheckRunner.COMMANDS_PER_HISTORY);
    assertEquals(16384, M03StartCheckRunner.TOTAL_COMMANDS);
  }
}
