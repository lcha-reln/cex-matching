package io.github.lchareln.cex.matching.testkit;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Executes every frozen M05 scenario from a fresh state and freezes complete result histories. */
final class M05FixedScenarioRunner {
  Result run(Path root, M05Candidate.Factory factory) {
    Objects.requireNonNull(root, "root");
    Objects.requireNonNull(factory, "factory");
    M05ScenarioCorpus.Corpus corpus = M05ScenarioCorpus.load(root);
    M05CommandCanonicalizer.CanonicalCommands commandInputs =
        new M05CommandCanonicalizer().fixed(corpus);
    require(corpus.scenarios().size() == 12, "M05 fixed scenario count changed");
    require(corpus.commandCount() == 54, "M05 fixed command count changed");
    require(commandInputs.commandCount() == 54, "M05 fixed command canonicalization changed");

    ObjectNode scenarioPack = JsonSupport.MAPPER.createObjectNode();
    scenarioPack.put("schemaVersion", "matching.m05.fixed-scenario-pack.v1");
    scenarioPack.put("fixtureSha256", M05StartCheckRunner.FIXED_CORPUS_SHA256);
    scenarioPack.put("commandInputDigest", commandInputs.digest());
    scenarioPack.put("status", M05PropertyJudge.PASS);
    ArrayNode scenarioNodes = scenarioPack.putArray("scenarios");

    ObjectNode eventBatches = JsonSupport.MAPPER.createObjectNode();
    eventBatches.put("schemaVersion", "matching.m05.fixed-event-batches.v1");
    eventBatches.put("fixtureSha256", M05StartCheckRunner.FIXED_CORPUS_SHA256);
    eventBatches.put("commandInputDigest", commandInputs.digest());
    eventBatches.put("status", M05PropertyJudge.PASS);
    ArrayNode eventScenarios = eventBatches.putArray("scenarios");

    int commands = 0;
    int comparisons = 0;
    int ledgerChecks = 0;
    LinkedHashMap<String, Integer> commandCounts = new LinkedHashMap<>();
    List.of("PLACE", "CANCEL", "PREPARE_RULE_SET", "ACTIVATE_RULE_SET")
        .forEach(type -> commandCounts.put(type, 0));
    for (M05ScenarioCorpus.Scenario scenario : corpus.scenarios()) {
      M05PropertyJudge.Observation observation =
          new M05PropertyJudge().judge(scenario.scenarioId(), "fixed", commands(scenario), factory);
      if (M05PropertyJudge.SYSTEM_ERROR.equals(observation.classification())) {
        throw new IllegalStateException(
            "fixed scenario raised SYSTEM_ERROR: "
                + scenario.scenarioId()
                + ": "
                + observation.message());
      }
      if (!M05PropertyJudge.PASS.equals(observation.classification())) {
        throw new CandidateFailure(
            "fixed scenario failed: " + scenario.scenarioId() + ": " + observation.message());
      }
      require(
          observation.trace().size() == scenario.steps().size(),
          "M05 fixed scenario trace length changed");

      ObjectNode scenarioNode = scenarioNodes.addObject();
      scenarioNode.put("scenarioId", scenario.scenarioId());
      ArrayNode obligations = scenarioNode.putArray("proofObligations");
      scenario.proofObligations().forEach(obligations::add);
      ArrayNode replayCommands = scenarioNode.putArray("commands");

      ObjectNode eventScenario = eventScenarios.addObject();
      eventScenario.put("scenarioId", scenario.scenarioId());
      ArrayNode eventCases = eventScenario.putArray("cases");
      for (int index = 0; index < scenario.steps().size(); index++) {
        M05ScenarioCorpus.Step input = scenario.steps().get(index);
        M05PropertyJudge.Step step = observation.trace().get(index);
        require(
            step.expected().equals(step.actual()),
            "M05 fixed production/reference outcomes differ after PASS");
        replayCommands.add(M05Json.replayCommand(input.caseId(), input.command(), step.expected()));

        ObjectNode eventCase = eventCases.addObject();
        ObjectNode commandNode = M05Json.command(input.command());
        eventCase.put("caseId", input.caseId());
        eventCase.put("type", commandNode.path("type").stringValue());
        if (commandNode.has("entrypoint")) {
          eventCase.put("entrypoint", commandNode.path("entrypoint").stringValue());
        }
        eventCase.set("input", commandNode.path("input").deepCopy());
        if (commandNode.has("expectedRuleSet")) {
          eventCase.set("expectedRuleSet", commandNode.path("expectedRuleSet").deepCopy());
        }
        ObjectNode outcome = M05Json.outcome(step.expected());
        eventCase.put("applicationSequence", step.expected().applicationSequence());
        eventCase.set("events", outcome.path("events").deepCopy());
        eventCase.set("stateAfter", outcome.path("stateAfter").deepCopy());
        String commandType = commandNode.path("type").stringValue();
        commandCounts.put(commandType, commandCounts.get(commandType) + 1);
        commands++;
      }
      comparisons += observation.differentialComparisons();
      ledgerChecks += observation.ledgerChecks();
    }

    require(commands == 54, "M05 fixed command execution count changed");
    require(comparisons == commands, "M05 fixed differential count changed");
    require(ledgerChecks == commands, "M05 fixed ledger count changed");
    byte[] canonical = canonicalize(scenarioPack);
    return new Result(
        corpus.scenarios(),
        scenarioPack,
        eventBatches,
        canonical,
        Hashing.semanticDigest(canonical),
        corpus.scenarios().size(),
        commands,
        countLines(canonical),
        comparisons,
        ledgerChecks,
        commandCounts,
        commandInputs.digest());
  }

  private static List<M05Command> commands(M05ScenarioCorpus.Scenario scenario) {
    return scenario.steps().stream().map(M05ScenarioCorpus.Step::command).toList();
  }

  private static byte[] canonicalize(ObjectNode scenarioPack) {
    StringBuilder result = new StringBuilder();
    ArrayNode scenarios = (ArrayNode) scenarioPack.path("scenarios");
    result
        .append("M05F1|fixtureSha256=")
        .append(scenarioPack.path("fixtureSha256").stringValue())
        .append("|commandInputDigest=")
        .append(scenarioPack.path("commandInputDigest").stringValue())
        .append("|scenarios=")
        .append(scenarios.size())
        .append('\n');
    for (int scenarioIndex = 0; scenarioIndex < scenarios.size(); scenarioIndex++) {
      ObjectNode scenario = (ObjectNode) scenarios.get(scenarioIndex);
      ArrayNode commands = (ArrayNode) scenario.path("commands");
      result
          .append("M05FS1|scenario=")
          .append(scenarioIndex)
          .append("|scenarioId=")
          .append(M05CommandCanonicalizer.framed(scenario.path("scenarioId").stringValue()))
          .append("|commands=")
          .append(commands.size())
          .append('\n');
      for (int commandIndex = 0; commandIndex < commands.size(); commandIndex++) {
        byte[] stableJson = JsonSupport.prettyBytes(commands.get(commandIndex));
        result
            .append("M05FC1|scenario=")
            .append(scenarioIndex)
            .append("|command=")
            .append(commandIndex)
            .append("|bytes=")
            .append(stableJson.length)
            .append("|sha256=")
            .append(Hashing.sha256Hex(stableJson))
            .append('|')
            .append(
                new String(stableJson, StandardCharsets.UTF_8)
                    .replace("\\", "\\\\")
                    .replace("\n", "\\n"))
            .append('\n');
      }
    }
    return result.toString().getBytes(StandardCharsets.UTF_8);
  }

  private static int countLines(byte[] bytes) {
    int count = 0;
    for (byte value : bytes) {
      if (value == '\n') {
        count++;
      }
    }
    return count;
  }

  private static void require(boolean condition, String message) {
    if (!condition) {
      throw new IllegalStateException(message);
    }
  }

  static final class CandidateFailure extends RuntimeException {
    private static final long serialVersionUID = 1L;

    CandidateFailure(String message) {
      super(message);
    }
  }

  record Result(
      List<M05ScenarioCorpus.Scenario> scenarios,
      ObjectNode scenarioPack,
      ObjectNode eventBatches,
      byte[] canonicalBytes,
      String canonicalDigest,
      int scenarioCount,
      int commandCount,
      int canonicalLines,
      int differentialComparisons,
      int ledgerChecks,
      Map<String, Integer> commandCounts,
      String commandInputDigest) {
    Result {
      scenarios = List.copyOf(scenarios);
      canonicalBytes = canonicalBytes.clone();
      commandCounts = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(commandCounts));
    }

    @Override
    public byte[] canonicalBytes() {
      return canonicalBytes.clone();
    }

    int comparisons() {
      return differentialComparisons;
    }
  }
}
