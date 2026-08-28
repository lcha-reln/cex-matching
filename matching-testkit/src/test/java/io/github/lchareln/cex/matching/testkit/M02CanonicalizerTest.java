package io.github.lchareln.cex.matching.testkit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.lchareln.cex.matching.PlaceLimitOrderInput;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

final class M02CanonicalizerTest {
  @Test
  void productionAndFrozenOracleProduceTheCheckedInM02H1Bytes() throws IOException {
    M02ScenarioPack pack = M02TestPaths.load();
    M02Assertions.Observation observation =
        new M02Assertions().judge(pack, M02ProductionCandidate::new);
    assertEquals(M02Assertions.PASS, observation.classification(), observation.message());
    M02Canonicalizer canonicalizer = new M02Canonicalizer();
    M02CanonicalHistory actual = canonicalizer.canonicalize(observation.history());
    M02CanonicalHistory expected = canonicalizer.canonicalize(canonicalizer.expectedHistory(pack));

    assertArrayEquals(expected.bytes(), actual.bytes());
    assertArrayEquals(
        Files.readAllBytes(M02TestPaths.root().resolve(M02CheckRunner.GOLDEN_PATH)),
        actual.bytes());
    assertEquals(M02CheckRunner.EXPECTED_DIGEST, actual.digest());
    assertEquals(M02CheckRunner.EXPECTED_LINES, actual.lineCount());
    assertEquals(M02CheckRunner.EXPECTED_BYTES, actual.bytes().length);
    String text = new String(actual.bytes(), StandardCharsets.UTF_8);
    assertTrue(text.startsWith("M02H1|scenarios=10|commands=34\n"));
    assertTrue(text.contains("|type=CANCEL|instrumentId="));
    assertTrue(text.contains("|type=CANCELED|sequence="));
    assertTrue(text.contains("|type=PLACE_REJECTED|orderId="));
  }

  @Test
  void commandInputChangesTheDigest() {
    M02RunHistory history = new M02Canonicalizer().expectedHistory(M02TestPaths.load());
    List<M02RunHistory.ScenarioRun> scenarios = new ArrayList<>(history.scenarios());
    M02RunHistory.ScenarioRun firstScenario = scenarios.getFirst();
    List<M02RunHistory.CommandRun> commands = new ArrayList<>(firstScenario.commands());
    M02RunHistory.PlaceRun first = (M02RunHistory.PlaceRun) commands.getFirst();
    PlaceLimitOrderInput changed =
        new PlaceLimitOrderInput(
            first.input().instrumentId(),
            first.input().orderId(),
            first.input().side(),
            first.input().priceTicks().add(java.math.BigInteger.ONE),
            first.input().quantityLots());
    commands.set(
        0, new M02RunHistory.PlaceRun(first.caseId(), changed, first.events(), first.bookAfter()));
    scenarios.set(0, new M02RunHistory.ScenarioRun(firstScenario.scenarioId(), commands));

    M02Canonicalizer canonicalizer = new M02Canonicalizer();
    assertNotEquals(
        canonicalizer.canonicalize(history).digest(),
        canonicalizer.canonicalize(new M02RunHistory(scenarios)).digest());
  }
}
