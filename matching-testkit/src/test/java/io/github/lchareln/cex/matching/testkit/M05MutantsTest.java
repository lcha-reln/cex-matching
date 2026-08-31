package io.github.lchareln.cex.matching.testkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.math.BigInteger;
import java.util.List;
import org.junit.jupiter.api.Test;

final class M05MutantsTest {
  @Test
  void frozenGeneratedHistoriesKillEveryRequiredMutantAsStudentFailure() {
    M05ScenarioCorpus.Corpus corpus = M05ScenarioCorpus.load(M05TestPaths.root());
    M05GeneratorProfile profile = M05GeneratorProfile.load(M05TestPaths.root());
    assertEquals(M05StartCheckRunner.REQUIRED_MUTANTS, profile.requiredMutants());
    assertEquals(
        profile.requiredMutants(),
        M05RequiredMutants.all().stream().map(item -> item.id()).toList());
    List<M05GeneratedHistory> histories = new M05HistoryGenerator().generate(profile, corpus);

    for (M05RequiredMutants.RequiredMutant mutant : M05RequiredMutants.all()) {
      M05PropertyJudge.Observation found = null;
      for (M05GeneratedHistory history : histories) {
        M05PropertyJudge.Observation observation =
            new M05PropertyJudge().judge(history, mutant.factory());
        assertNotEquals(
            M05PropertyJudge.SYSTEM_ERROR,
            observation.classification(),
            mutant.id() + ": " + observation.message());
        if (mutant.fingerprint().matches(observation)) {
          found = observation;
          break;
        }
      }
      assertNotNull(found, mutant.id());
      assertEquals(M05PropertyJudge.STUDENT_FAILURE, found.classification(), mutant.id());
      assertEquals(mutant.fingerprint().value(), found.failure().fingerprint(), mutant.id());
    }
  }

  @Test
  void throwingControlRemainsSystemErrorAndCannotBeCountedAsAKill() {
    M05PropertyJudge.Observation observation =
        new M05PropertyJudge()
            .judge(
                "throwing-control",
                "0000000000000000",
                List.of(new M05Command.Cancel("BTC-USDT", BigInteger.ONE)),
                M05Mutants.throwingControl());

    assertEquals(M05PropertyJudge.SYSTEM_ERROR, observation.classification());
    assertEquals(null, observation.failure());
  }
}
