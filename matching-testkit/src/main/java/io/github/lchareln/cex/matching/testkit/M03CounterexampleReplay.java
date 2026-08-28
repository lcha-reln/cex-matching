package io.github.lchareln.cex.matching.testkit;

import io.github.lchareln.cex.matching.reference.LinearReferenceModel;
import io.github.lchareln.cex.matching.reference.ReferenceCommand;
import io.github.lchareln.cex.matching.reference.ReferenceMatcher;
import io.github.lchareln.cex.matching.reference.SemanticOutcome;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import tools.jackson.databind.JsonNode;

/** Replays persisted M03 counterexamples against both the reference model and named mutant. */
final class M03CounterexampleReplay {
  private final M03PropertyJudge judge = new M03PropertyJudge();

  ReplayReport replay(
      JsonNode persisted,
      Map<String, M03Candidate.Factory> factories,
      M03GeneratorProfile profile) {
    Objects.requireNonNull(profile, "profile");
    List<M03GeneratedHistory> generated = new M03HistoryGenerator().generate(profile);
    ReplayReport semantic = replaySemanticOnly(persisted, factories);
    List<ScenarioReplay> verified = new ArrayList<>(semantic.scenarios().size());
    JsonNode scenarios = persisted.path("scenarios");
    for (int index = 0; index < semantic.scenarios().size(); index++) {
      JsonNode scenario = scenarios.get(index);
      ScenarioReplay base = semantic.scenarios().get(index);
      int historyIndex = requiredInt(scenario, "historyIndex");
      if (historyIndex < 0 || historyIndex >= generated.size()) {
        throw malformed("historyIndex is outside the frozen generated suite");
      }
      M03GeneratedHistory expectedHistory = generated.get(historyIndex);
      List<ReferenceCommand> persistedOriginal =
          M03Json.commands(scenario.path("originalCommands"));
      boolean provenanceExact =
          requiredInt(scenario, "originalCommandCount") == persistedOriginal.size()
              && persistedOriginal.equals(expectedHistory.commands())
              && requiredText(scenario, "lane").equals(expectedHistory.laneId())
              && requiredText(scenario, "seed").equals(expectedHistory.seedHex());

      M03Candidate.Factory factory = factories.get(base.mutantId());
      if (factory == null) {
        throw new IllegalArgumentException("no M03 replay factory for " + base.mutantId());
      }
      M03Shrinker.Fingerprint fingerprint =
          new M03Shrinker.Fingerprint(
              requiredText(scenario, "propertyId"), requiredText(scenario, "divergenceKind"));
      M03Shrinker.Result reverified =
          new M03Shrinker()
              .shrink(
                  base.scenarioId(),
                  expectedHistory.seedHex(),
                  persistedOriginal,
                  factory,
                  fingerprint);
      List<ReferenceCommand> persistedMinimized = M03Json.commands(scenario.path("commands"));
      boolean oneMinimalReverified =
          requiredBoolean(scenario, "oneMinimal")
              && reverified.oneMinimal()
              && reverified.commands().equals(persistedMinimized)
              && reverified.trials() == requiredInt(scenario, "shrinkTrials");
      verified.add(
          new ScenarioReplay(
              base.scenarioId(),
              base.mutantId(),
              base.commandCount(),
              base.expectedFingerprint(),
              base.actualFingerprint(),
              base.classification(),
              base.referenceOutcomesExact(),
              base.actualOutcomeExact(),
              provenanceExact,
              oneMinimalReverified,
              base.passed() && provenanceExact && oneMinimalReverified));
    }
    return new ReplayReport(verified, verified.stream().allMatch(ScenarioReplay::passed));
  }

  /**
   * Semantic-only helper for focused serialization tests; release checks use the profile overload.
   */
  ReplayReport replaySemanticOnly(JsonNode persisted, Map<String, M03Candidate.Factory> factories) {
    Objects.requireNonNull(persisted, "persisted");
    Map<String, M03Candidate.Factory> immutableFactories = Map.copyOf(factories);
    JsonNode scenarios = persisted.path("scenarios");
    if (!scenarios.isArray()) {
      throw malformed("scenarios must be an array");
    }
    List<ScenarioReplay> results = new ArrayList<>(scenarios.size());
    for (JsonNode scenario : scenarios) {
      String mutantId = requiredText(scenario, "mutantId");
      M03Candidate.Factory factory = immutableFactories.get(mutantId);
      if (factory == null) {
        throw new IllegalArgumentException("no M03 replay factory for " + mutantId);
      }
      results.add(replayScenario(scenario, factory));
    }
    boolean allPassed = results.stream().allMatch(ScenarioReplay::passed);
    return new ReplayReport(results, allPassed);
  }

  ScenarioReplay replayScenario(JsonNode scenario, M03Candidate.Factory factory) {
    Objects.requireNonNull(scenario, "scenario");
    Objects.requireNonNull(factory, "factory");
    String scenarioId = requiredText(scenario, "scenarioId");
    String mutantId = requiredText(scenario, "mutantId");
    String seed = requiredText(scenario, "seed");
    String expectedClassification = requiredText(scenario, "classification");
    M03Shrinker.Fingerprint expectedFingerprint =
        new M03Shrinker.Fingerprint(
            requiredText(scenario, "propertyId"), requiredText(scenario, "divergenceKind"));
    int expectedCommandCount = requiredInt(scenario, "minimizedCommandCount");
    int expectedFailureIndex = requiredInt(scenario, "firstFailingCommandIndex");

    JsonNode commandNodes = scenario.path("commands");
    if (!commandNodes.isArray()) {
      throw malformed("commands must be an array");
    }
    List<ReferenceCommand> commands = new ArrayList<>(commandNodes.size());
    ReferenceMatcher reference = new LinearReferenceModel();
    boolean referenceOutcomesExact = true;
    for (JsonNode commandNode : commandNodes) {
      ReferenceCommand command = M03Json.command(commandNode);
      SemanticOutcome persistedExpected = M03Json.outcome(commandNode.path("expected"));
      SemanticOutcome replayedExpected = reference.apply(command);
      referenceOutcomesExact &= persistedExpected.equals(replayedExpected);
      commands.add(command);
    }

    M03PropertyJudge.Observation observation = judge.judge(scenarioId, seed, commands, factory);
    M03PropertyJudge.Failure failure = observation.failure();
    String actualFingerprint = failure == null ? null : failure.fingerprint();
    boolean actualOutcomeExact =
        failure != null
            && failure.actual() != null
            && M03Json.outcome(scenario.path("actualAtFailure")).equals(failure.actual());
    boolean passed =
        expectedCommandCount == commands.size()
            && expectedClassification.equals(observation.classification())
            && expectedFingerprint.matches(observation)
            && failure != null
            && expectedFailureIndex == failure.commandIndex()
            && referenceOutcomesExact
            && actualOutcomeExact;

    return new ScenarioReplay(
        scenarioId,
        mutantId,
        commands.size(),
        expectedFingerprint.value(),
        actualFingerprint,
        observation.classification(),
        referenceOutcomesExact,
        actualOutcomeExact,
        false,
        false,
        passed);
  }

  private static String requiredText(JsonNode node, String field) {
    JsonNode value = node.path(field);
    if (!value.isString()) {
      throw malformed(field + " must be a string");
    }
    return value.stringValue();
  }

  private static int requiredInt(JsonNode node, String field) {
    JsonNode value = node.path(field);
    if (!value.isIntegralNumber()) {
      throw malformed(field + " must be an integer");
    }
    try {
      return value.bigIntegerValue().intValueExact();
    } catch (ArithmeticException exception) {
      throw malformed(field + " must fit in an int");
    }
  }

  private static boolean requiredBoolean(JsonNode node, String field) {
    JsonNode value = node.path(field);
    if (!value.isBoolean()) {
      throw malformed(field + " must be a boolean");
    }
    return value.booleanValue();
  }

  private static FixtureSchemaException malformed(String message) {
    return new FixtureSchemaException("malformed M03 counterexample: " + message);
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
      boolean passed) {
    ScenarioReplay {
      Objects.requireNonNull(scenarioId, "scenarioId");
      Objects.requireNonNull(mutantId, "mutantId");
      Objects.requireNonNull(expectedFingerprint, "expectedFingerprint");
      Objects.requireNonNull(classification, "classification");
    }
  }

  record ReplayReport(List<ScenarioReplay> scenarios, boolean allPassed) {
    ReplayReport {
      scenarios = List.copyOf(scenarios);
      if (allPassed != scenarios.stream().allMatch(ScenarioReplay::passed)) {
        throw new IllegalArgumentException("M03 replay aggregate does not match scenarios");
      }
    }
  }
}
