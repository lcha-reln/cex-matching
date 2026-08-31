package io.github.lchareln.cex.matching.testkit;

import io.github.lchareln.cex.matching.reference.M06SemanticEvent;
import io.github.lchareln.cex.matching.reference.M06SemanticOutcome;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Executes all 15 frozen scenarios through production, reference, and the event ledger. */
final class M06FixedScenarioRunner {
  Result run(Path root, M06Candidate.Factory production) {
    M06Corpus.Fixed corpus = M06Corpus.loadFixed(root);
    M06Canonical.Canonical canonical = M06Canonical.fixed(corpus);
    ArrayNode batches = JsonSupport.MAPPER.createArrayNode();
    Map<String, Integer> commandCounts = new LinkedHashMap<>();
    int comparisons = 0;
    int ledgers = 0;
    M06PropertyJudge judge = new M06PropertyJudge();
    for (M06Corpus.Scenario scenario : corpus.scenarios()) {
      M06PropertyJudge.Observation observation =
          judge.judge(scenario.cases().stream().map(M06Corpus.Case::command).toList(), production);
      if (M06PropertyJudge.SYSTEM_ERROR.equals(observation.classification())) {
        throw new IllegalStateException(
            "M06 fixed scenario " + scenario.id() + " raised " + observation.message());
      }
      if (!M06PropertyJudge.PASS.equals(observation.classification())) {
        throw new CandidateFailure(
            "M06 fixed scenario "
                + scenario.id()
                + " failed "
                + observation.fingerprint()
                + ": "
                + observation.message());
      }
      comparisons += observation.differentialComparisons();
      ledgers += observation.ledgerChecks();
      M06ReferenceCandidate reference = new M06ReferenceCandidate();
      for (M06Corpus.Case item : scenario.cases()) {
        commandCounts.merge(item.type(), 1, Integer::sum);
        M06SemanticOutcome outcome = reference.apply(item.command());
        ObjectNode batch = batches.addObject();
        batch.put("scenarioId", scenario.id());
        batch.put("caseId", item.id());
        batch.put("commandType", item.type());
        batch.put("applicationSequence", outcome.applicationSequence());
        ArrayNode events = batch.putArray("events");
        for (M06SemanticEvent event : outcome.events()) {
          ObjectNode value = events.addObject();
          value.put("type", event.getClass().getSimpleName());
          value.put("semantic", event.toString());
        }
        byte[] stateBytes =
            (outcome.stateAfter().toString() + "\n").getBytes(StandardCharsets.UTF_8);
        batch.put("stateAfterSha256", Hashing.sha256Hex(stateBytes));
      }
    }
    require(
        comparisons == 64 && ledgers == 64, "M06 fixed differential or event-ledger count changed");
    return new Result(
        corpus.document(),
        batches,
        canonical,
        corpus.scenarios().size(),
        corpus.commands(),
        Collections.unmodifiableMap(new LinkedHashMap<>(commandCounts)),
        comparisons,
        ledgers);
  }

  private static void require(boolean condition, String message) {
    if (!condition) {
      throw new IllegalStateException(message);
    }
  }

  record Result(
      JsonNode scenarioPack,
      ArrayNode eventBatches,
      M06Canonical.Canonical canonical,
      int scenarios,
      int commands,
      Map<String, Integer> commandCounts,
      int differentialComparisons,
      int ledgerChecks) {}

  static final class CandidateFailure extends RuntimeException {
    private static final long serialVersionUID = 1L;

    CandidateFailure(String message) {
      super(message);
    }
  }
}
