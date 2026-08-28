package io.github.lchareln.cex.matching.testkit;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Executes the public fixed M04 scenarios and produces a replayable golden-history artifact. */
final class M04FixedScenarioRunner {
  Result run(Path root, M04Candidate.Factory factory) {
    Objects.requireNonNull(root, "root");
    List<M04ScenarioCorpus.Scenario> scenarios =
        M04ScenarioCorpus.load(
            root.resolve(M04StartCheckRunner.FIXED_CORPUS_PATH),
            root.resolve(M04StartCheckRunner.FIXED_CORPUS_SCHEMA_PATH));
    ObjectNode scenarioPack = JsonSupport.MAPPER.createObjectNode();
    scenarioPack.put("schemaVersion", "matching.m04.fixed-scenario-pack.v1");
    scenarioPack.put("fixtureSha256", M04StartCheckRunner.FIXED_CORPUS_SHA256);
    scenarioPack.put("status", M04PropertyJudge.PASS);
    ArrayNode scenarioNodes = scenarioPack.putArray("scenarios");
    ObjectNode eventBatches = JsonSupport.MAPPER.createObjectNode();
    eventBatches.put("schemaVersion", "matching.m04.fixed-event-batches.v1");
    eventBatches.put("fixtureSha256", M04StartCheckRunner.FIXED_CORPUS_SHA256);
    eventBatches.put("status", M04PropertyJudge.PASS);
    ArrayNode eventScenarios = eventBatches.putArray("scenarios");
    int commands = 0;
    for (M04ScenarioCorpus.Scenario scenario : scenarios) {
      M04PropertyJudge.Observation observation =
          new M04PropertyJudge().judge(scenario.id(), "fixed", scenario.commands(), factory);
      if (M04PropertyJudge.SYSTEM_ERROR.equals(observation.classification())) {
        throw new IllegalStateException(
            "fixed scenario raised SYSTEM_ERROR: " + scenario.id() + ": " + observation.message());
      }
      if (!M04PropertyJudge.PASS.equals(observation.classification())) {
        throw new CandidateFailure(
            "fixed scenario failed: " + scenario.id() + ": " + observation.message());
      }
      require(
          observation.trace().size() == scenario.cases().size(),
          "fixed scenario trace length changed");
      ObjectNode scenarioNode = scenarioNodes.addObject();
      scenarioNode.put("scenarioId", scenario.id());
      ArrayNode cases = scenarioNode.putArray("commands");
      ObjectNode eventScenario = eventScenarios.addObject();
      eventScenario.put("scenarioId", scenario.id());
      ArrayNode eventCases = eventScenario.putArray("cases");
      for (int index = 0; index < scenario.cases().size(); index++) {
        M04ScenarioCorpus.Case input = scenario.cases().get(index);
        M04PropertyJudge.Step step = observation.trace().get(index);
        require(
            step.expected().equals(step.actual()),
            "fixed production/reference outcomes differ after PASS");
        cases.add(M04Json.replayCommand(input.id(), input.command(), step.expected()));
        ObjectNode eventCase = eventCases.addObject();
        ObjectNode commandNode = M04Json.command(input.command());
        eventCase.put("caseId", input.id());
        eventCase.put("type", commandNode.path("type").stringValue());
        eventCase.set("input", commandNode.path("input").deepCopy());
        eventCase.set("events", M04Json.outcome(step.expected()).path("events").deepCopy());
        eventCase.set("bookAfter", M04Json.book(step.expected().bookAfter()));
        commands++;
      }
    }
    require(commands == M04StartCheckRunner.FIXED_COMMANDS, "M04 fixed command count changed");
    byte[] canonical = canonicalize(scenarioPack);
    return new Result(
        scenarios,
        scenarioPack,
        eventBatches,
        canonical,
        Hashing.semanticDigest(canonical),
        scenarios.size(),
        commands,
        countLines(canonical));
  }

  private static byte[] canonicalize(ObjectNode scenarioPack) {
    StringBuilder result = new StringBuilder();
    ArrayNode scenarios = (ArrayNode) scenarioPack.path("scenarios");
    result
        .append("M04F1|fixtureSha256=")
        .append(scenarioPack.path("fixtureSha256").stringValue())
        .append("|scenarios=")
        .append(scenarios.size())
        .append('\n');
    for (int scenarioIndex = 0; scenarioIndex < scenarios.size(); scenarioIndex++) {
      ObjectNode scenario = (ObjectNode) scenarios.get(scenarioIndex);
      ArrayNode cases = (ArrayNode) scenario.path("commands");
      result
          .append("M04FS1|scenario=")
          .append(scenarioIndex)
          .append("|scenarioId=")
          .append(M04CommandCanonicalizer.framed(scenario.path("scenarioId").stringValue()))
          .append("|cases=")
          .append(cases.size())
          .append('\n');
      for (int caseIndex = 0; caseIndex < cases.size(); caseIndex++) {
        ObjectNode replayCase = (ObjectNode) cases.get(caseIndex);
        byte[] stableJson = JsonSupport.prettyBytes(replayCase);
        result
            .append("M04FC1|scenario=")
            .append(scenarioIndex)
            .append("|case=")
            .append(caseIndex)
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
      List<M04ScenarioCorpus.Scenario> scenarios,
      ObjectNode scenarioPack,
      ObjectNode eventBatches,
      byte[] canonicalBytes,
      String canonicalDigest,
      int scenarioCount,
      int commandCount,
      int canonicalLines) {
    Result {
      scenarios = List.copyOf(scenarios);
      canonicalBytes = canonicalBytes.clone();
    }

    @Override
    public byte[] canonicalBytes() {
      return canonicalBytes.clone();
    }
  }
}
