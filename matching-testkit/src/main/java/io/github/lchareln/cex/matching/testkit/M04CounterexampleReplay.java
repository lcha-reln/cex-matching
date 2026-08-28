package io.github.lchareln.cex.matching.testkit;

import io.github.lchareln.cex.matching.reference.LinearReferenceModel;
import io.github.lchareln.cex.matching.reference.ReferenceCommand;
import io.github.lchareln.cex.matching.reference.ReferenceMatcher;
import io.github.lchareln.cex.matching.reference.SemanticOutcome;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;

/** Strict fresh-state replay of persisted M04 generated counterexamples and provenance. */
final class M04CounterexampleReplay {
  ReplayReport replay(
      JsonNode persisted,
      M04GeneratorProfile profile,
      List<M04RequiredMutants.RequiredMutant> requiredMutants) {
    requireText(persisted, "schemaVersion", "matching.m04.counterexamples.v1");
    requireText(persisted, "fixtureSha256", M04StartCheckRunner.FIXED_CORPUS_SHA256);
    requireText(persisted, "profileSha256", M04StartCheckRunner.GENERATOR_SHA256);
    requireText(persisted, "generatorAlgorithm", M04GeneratorProfile.ALGORITHM);
    requireText(persisted, "seedDerivation", M04CommandCanonicalizer.SEED_DERIVATION);
    requireText(persisted, "modelVersion", "linear-scan-reference-v1");
    List<M04GeneratedHistory> histories = new M04HistoryGenerator().generate(profile);
    M04GeneratedCoverage.Result coverage = new M04GeneratedCoverage().analyze(profile, histories);
    Map<String, M04RequiredMutants.RequiredMutant> mutants = new LinkedHashMap<>();
    requiredMutants.forEach(mutant -> mutants.put(mutant.id(), mutant));
    JsonNode scenarios = persisted.path("scenarios");
    if (!scenarios.isArray() || scenarios.size() != requiredMutants.size()) {
      throw malformed("scenarios must be the exact required array");
    }
    List<ScenarioReplay> results = new ArrayList<>(scenarios.size());
    for (int scenarioIndex = 0; scenarioIndex < scenarios.size(); scenarioIndex++) {
      JsonNode scenario = scenarios.get(scenarioIndex);
      M04RequiredMutants.RequiredMutant expectedMutant = requiredMutants.get(scenarioIndex);
      String mutantId = text(scenario, "mutantId");
      M04RequiredMutants.RequiredMutant mutant = mutants.get(mutantId);
      if (mutant == null || mutant != expectedMutant) {
        throw malformed("unknown or reordered mutantId: " + mutantId);
      }
      requireText(scenario, "scenarioId", mutant.scenarioId());
      requireText(scenario, "classification", M04PropertyJudge.STUDENT_FAILURE);
      requireText(scenario, "propertyId", mutant.fingerprint().propertyId());
      requireText(scenario, "divergenceKind", mutant.fingerprint().divergenceKind());
      requireText(scenario, "sourceKind", "GENERATED");
      requireText(scenario, "coverageKey", mutant.generatedCoverageKey());

      int historyIndex = integer(scenario, "historyIndex");
      if (historyIndex < 0 || historyIndex >= histories.size()) {
        throw malformed("historyIndex is outside frozen generated suite");
      }
      M04GeneratedHistory source = histories.get(historyIndex);
      List<ReferenceCommand> persistedOriginal =
          M04Json.commands(scenario.path("originalCommands"));
      boolean provenanceExact =
          source.commands().equals(persistedOriginal)
              && source.laneId().equals(text(scenario, "lane"))
              && source.seedHex().equals(text(scenario, "seed"))
              && persistedOriginal.size() == integer(scenario, "originalCommandCount");
      M04PropertyJudge.Observation sourceObservation =
          new M04PropertyJudge().judge(source, mutant.factory());
      provenanceExact &=
          mutant.fingerprint().matches(sourceObservation)
              && sourceObservation.failure().commandIndex()
                  == integer(scenario, "sourceFailingCommandIndex")
              && coverage.observedAt(
                  mutant.generatedCoverageKey(),
                  source.historyIndex(),
                  sourceObservation.failure().commandIndex());

      List<ReferenceCommand> commands = new ArrayList<>();
      ReferenceMatcher reference = new LinearReferenceModel();
      boolean referenceExact = true;
      for (JsonNode commandNode : scenario.path("commands")) {
        ReferenceCommand command = M04Json.command(commandNode);
        SemanticOutcome expected = M04Json.outcome(commandNode.path("expected"));
        referenceExact &= expected.equals(reference.apply(command));
        commands.add(command);
      }
      M04PropertyJudge.Observation observation =
          new M04PropertyJudge()
              .judge(mutant.scenarioId(), source.seedHex(), commands, mutant.factory());
      M04PropertyJudge.Failure failure = observation.failure();
      boolean actualExact =
          failure != null
              && failure.actual() != null
              && M04Json.outcome(scenario.path("actualAtFailure")).equals(failure.actual());
      M04Shrinker.Result reverified =
          new M04Shrinker()
              .shrink(
                  "history-" + source.historyIndex(),
                  source.seedHex(),
                  persistedOriginal,
                  mutant.factory(),
                  mutant.fingerprint());
      boolean oneMinimalExact =
          scenario.path("oneMinimal").booleanValue()
              && reverified.oneMinimal()
              && reverified.commands().equals(commands)
              && reverified.trials() == integer(scenario, "shrinkTrials");
      boolean passed =
          provenanceExact
              && referenceExact
              && M04PropertyJudge.STUDENT_FAILURE.equals(observation.classification())
              && mutant.fingerprint().matches(observation)
              && failure.commandIndex() == integer(scenario, "firstFailingCommandIndex")
              && commands.size() == integer(scenario, "minimizedCommandCount")
              && actualExact
              && oneMinimalExact;
      results.add(
          new ScenarioReplay(
              mutant.scenarioId(),
              mutant.id(),
              commands.size(),
              mutant.fingerprint().value(),
              failure == null ? null : failure.fingerprint(),
              observation.classification(),
              referenceExact,
              actualExact,
              provenanceExact,
              oneMinimalExact,
              passed));
    }
    return new ReplayReport(results, results.stream().allMatch(ScenarioReplay::passed));
  }

  private static void requireText(JsonNode node, String field, String expected) {
    String actual = text(node, field);
    if (!expected.equals(actual)) {
      throw malformed(field + " changed");
    }
  }

  private static String text(JsonNode node, String field) {
    JsonNode value = node.path(field);
    if (!value.isString()) {
      throw malformed(field + " must be a string");
    }
    return value.stringValue();
  }

  private static int integer(JsonNode node, String field) {
    JsonNode value = node.path(field);
    if (!value.isIntegralNumber()) {
      throw malformed(field + " must be an integer");
    }
    try {
      return value.bigIntegerValue().intValueExact();
    } catch (ArithmeticException exception) {
      throw malformed(field + " must fit in int");
    }
  }

  private static FixtureSchemaException malformed(String message) {
    return new FixtureSchemaException("malformed M04 counterexample: " + message);
  }

  record ScenarioReplay(
      String scenarioId,
      String mutantId,
      int commandCount,
      String expectedFingerprint,
      String actualFingerprint,
      String classification,
      boolean referenceOutcomesExact,
      boolean actualOutcomeExact,
      boolean provenanceExact,
      boolean oneMinimalReverified,
      boolean passed) {}

  record ReplayReport(List<ScenarioReplay> scenarios, boolean allPassed) {
    ReplayReport {
      scenarios = List.copyOf(scenarios);
      if (allPassed != scenarios.stream().allMatch(ScenarioReplay::passed)) {
        throw new IllegalArgumentException("M04 replay aggregate disagrees with scenarios");
      }
    }
  }
}
