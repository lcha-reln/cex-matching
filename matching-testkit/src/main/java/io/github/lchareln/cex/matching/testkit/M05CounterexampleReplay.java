package io.github.lchareln.cex.matching.testkit;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;

/** Strict fresh-state replay of persisted M05 generated counterexamples and provenance. */
final class M05CounterexampleReplay {
  ReplayReport replay(
      JsonNode persisted,
      M05GeneratorProfile profile,
      M05ScenarioCorpus.Corpus corpus,
      List<M05RequiredMutants.RequiredMutant> requiredMutants) {
    requireText(persisted, "schemaVersion", "matching.m05.counterexamples.v1");
    requireText(persisted, "fixtureSha256", M05StartCheckRunner.FIXED_CORPUS_SHA256);
    requireText(persisted, "profileSha256", M05StartCheckRunner.GENERATOR_SHA256);
    requireText(persisted, "generatorAlgorithm", M05GeneratorProfile.ALGORITHM);
    requireText(persisted, "seedDerivation", M05CommandCanonicalizer.SEED_DERIVATION);
    requireText(persisted, "modelVersion", M05CounterexampleSuite.MODEL_VERSION);
    List<M05GeneratedHistory> histories = new M05HistoryGenerator().generate(profile, corpus);
    M05GeneratedCoverage.Result coverage = new M05GeneratedCoverage().analyze(profile, histories);
    coverage.assertRequired();
    Map<String, M05RequiredMutants.RequiredMutant> mutants = new LinkedHashMap<>();
    requiredMutants.forEach(mutant -> mutants.put(mutant.id(), mutant));
    JsonNode scenarios = persisted.path("scenarios");
    if (!scenarios.isArray() || scenarios.size() != requiredMutants.size()) {
      throw malformed("scenarios must be the exact required array");
    }
    List<ReplayScenario> results = new ArrayList<>(scenarios.size());
    for (int scenarioIndex = 0; scenarioIndex < scenarios.size(); scenarioIndex++) {
      JsonNode scenario = scenarios.get(scenarioIndex);
      M05RequiredMutants.RequiredMutant expectedMutant = requiredMutants.get(scenarioIndex);
      String mutantId = text(scenario, "mutantId");
      M05RequiredMutants.RequiredMutant mutant = mutants.get(mutantId);
      if (mutant == null || mutant != expectedMutant) {
        throw malformed("unknown or reordered mutantId: " + mutantId);
      }
      requireText(scenario, "scenarioId", mutant.scenarioId());
      requireText(scenario, "classification", M05PropertyJudge.STUDENT_FAILURE);
      requireText(scenario, "propertyId", mutant.fingerprint().propertyId());
      requireText(scenario, "divergenceKind", mutant.fingerprint().divergenceKind());
      requireText(scenario, "sourceKind", "GENERATED");
      requireText(scenario, "coverageKey", mutant.generatedCoverageKey());

      int historyIndex = integer(scenario, "historyIndex");
      if (historyIndex < 0 || historyIndex >= histories.size()) {
        throw malformed("historyIndex is outside frozen generated suite");
      }
      M05GeneratedHistory source = histories.get(historyIndex);
      List<M05Command> persistedOriginal = commands(scenario.path("originalCommands"));
      boolean provenanceExact =
          source.commands().equals(persistedOriginal)
              && source.laneId().equals(text(scenario, "lane"))
              && source.seedHex().equals(text(scenario, "seed"))
              && persistedOriginal.size() == integer(scenario, "originalCommandCount");
      M05PropertyJudge.Observation sourceObservation =
          new M05PropertyJudge().judge(source, mutant.factory());
      provenanceExact &=
          mutant.fingerprint().matches(sourceObservation)
              && sourceObservation.failure().commandIndex()
                  == integer(scenario, "sourceFailingCommandIndex")
              && coverage.observedAt(
                  mutant.generatedCoverageKey(),
                  source.historyIndex(),
                  sourceObservation.failure().commandIndex());

      List<M05Command> minimized = new ArrayList<>();
      M05Candidate reference = new M05ReferenceCandidate();
      boolean referenceExact = true;
      JsonNode replayCommands = scenario.path("commands");
      if (!replayCommands.isArray()) {
        throw malformed("commands must be an array");
      }
      for (int commandIndex = 0; commandIndex < replayCommands.size(); commandIndex++) {
        JsonNode commandNode = replayCommands.get(commandIndex);
        requireText(commandNode, "caseId", "command-" + (commandIndex + 1));
        M05Command command = M05ScenarioCorpus.parseCommand(commandNode);
        referenceExact &=
            jsonExact(M05Json.outcome(reference.apply(command)), commandNode.path("expected"));
        minimized.add(command);
      }
      M05PropertyJudge.Observation observation =
          new M05PropertyJudge()
              .judge(mutant.scenarioId(), source.seedHex(), minimized, mutant.factory());
      M05PropertyJudge.Failure failure = observation.failure();
      boolean actualExact =
          failure != null
              && failure.actual() != null
              && jsonExact(M05Json.outcome(failure.actual()), scenario.path("actualAtFailure"));
      M05Shrinker.Result reverified =
          new M05Shrinker()
              .shrink(
                  "history-" + source.historyIndex(),
                  source.seedHex(),
                  persistedOriginal,
                  mutant.factory(),
                  mutant.fingerprint());
      boolean oneMinimalExact =
          scenario.path("oneMinimal").booleanValue()
              && reverified.oneMinimal()
              && reverified.commands().equals(minimized)
              && reverified.trials() == integer(scenario, "shrinkTrials");
      boolean passed =
          provenanceExact
              && referenceExact
              && M05PropertyJudge.STUDENT_FAILURE.equals(observation.classification())
              && mutant.fingerprint().matches(observation)
              && failure.commandIndex() == integer(scenario, "firstFailingCommandIndex")
              && minimized.size() == integer(scenario, "minimizedCommandCount")
              && actualExact
              && oneMinimalExact;
      results.add(
          new ReplayScenario(
              mutant.scenarioId(),
              mutant.id(),
              minimized.size(),
              mutant.fingerprint().value(),
              failure == null ? null : failure.fingerprint(),
              observation.classification(),
              referenceExact,
              actualExact,
              provenanceExact,
              oneMinimalExact,
              passed));
    }
    return new ReplayReport(results, results.stream().allMatch(ReplayScenario::passed));
  }

  private static List<M05Command> commands(JsonNode nodes) {
    if (!nodes.isArray()) {
      throw malformed("originalCommands must be an array");
    }
    List<M05Command> result = new ArrayList<>();
    nodes.forEach(node -> result.add(M05ScenarioCorpus.parseCommand(node)));
    return List.copyOf(result);
  }

  /**
   * Jackson deliberately retains the narrowest parsed integer node, while M05Json writes BigInteger
   * nodes. Their Java node classes differ even when the lossless JSON bytes are equal.
   */
  private static boolean jsonExact(JsonNode computed, JsonNode persisted) {
    return Arrays.equals(JsonSupport.prettyBytes(computed), JsonSupport.prettyBytes(persisted));
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
    } catch (ArithmeticException failure) {
      throw malformed(field + " must fit in int");
    }
  }

  private static FixtureSchemaException malformed(String message) {
    return new FixtureSchemaException("malformed M05 counterexample: " + message);
  }

  record ReplayScenario(
      String scenarioId,
      String mutantId,
      int commandCount,
      String expectedFingerprint,
      String fingerprint,
      String classification,
      boolean referenceOutcomesExact,
      boolean actualOutcomeExact,
      boolean provenanceExact,
      boolean oneMinimalReverified,
      boolean passed) {}

  record ReplayReport(List<ReplayScenario> scenarios, boolean allPassed) {
    ReplayReport {
      scenarios = List.copyOf(scenarios);
      if (allPassed != scenarios.stream().allMatch(ReplayScenario::passed)) {
        throw new IllegalArgumentException("M05 replay aggregate disagrees with scenarios");
      }
    }
  }
}
