package io.github.lchareln.cex.matching.testkit;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.JsonNode;

/** Strict loader for the frozen M05 fresh-engine scenario corpus. */
final class M05ScenarioCorpus {
  private M05ScenarioCorpus() {}

  static Corpus load(Path repositoryRoot) {
    Path root = repositoryRoot.toAbsolutePath().normalize();
    Path fixture = root.resolve(M05StartCheckRunner.FIXED_CORPUS_PATH);
    Path schema = root.resolve(M05StartCheckRunner.FIXED_CORPUS_SCHEMA_PATH);
    try {
      byte[] bytes = Files.readAllBytes(fixture);
      if (!M05StartCheckRunner.FIXED_CORPUS_SHA256.equals(Hashing.sha256Hex(bytes))) {
        throw new IllegalStateException("M05 fixed corpus SHA-256 changed");
      }
      JsonNode document = JsonSupport.parse(bytes);
      JsonSupport.validate(document, Files.readString(schema), false);
      List<Scenario> scenarios = new ArrayList<>();
      for (JsonNode scenario : document.path("scenarios")) {
        List<String> obligations = strings(scenario.path("proofObligations"));
        List<Step> commands = new ArrayList<>();
        for (JsonNode command : scenario.path("commands")) {
          commands.add(new Step(command.path("caseId").stringValue(), parseCommand(command)));
        }
        scenarios.add(
            new Scenario(
                scenario.path("scenarioId").stringValue(), obligations, List.copyOf(commands)));
      }
      return new Corpus(List.copyOf(scenarios));
    } catch (IOException failure) {
      throw new IllegalStateException("cannot load M05 scenario corpus", failure);
    }
  }

  static M05Command parseCommand(JsonNode command) {
    JsonNode input = command.path("input");
    return switch (command.path("type").stringValue()) {
      case "PLACE" ->
          new M05Command.Place(
              command.path("entrypoint").stringValue(),
              input.path("instrumentId").stringValue(),
              integer(input.path("orderId")),
              input.path("side").stringValue(),
              integer(input.path("priceTicks")),
              integer(input.path("quantityLots")),
              input.path("executionPolicy").stringValue(),
              command.has("expectedRuleSet") ? identity(command.path("expectedRuleSet")) : null);
      case "CANCEL" ->
          new M05Command.Cancel(
              input.path("instrumentId").stringValue(), integer(input.path("orderId")));
      case "PREPARE_RULE_SET" ->
          new M05Command.PrepareRuleSet(
              identity(input.path("expectedActive")), artifact(input.path("artifact")));
      case "ACTIVATE_RULE_SET" ->
          new M05Command.ActivateRuleSet(
              integer(input.path("expectedApplicationSequence")),
              identity(input.path("expectedActive")),
              identity(input.path("target")));
      default -> throw new IllegalStateException("unknown M05 command type");
    };
  }

  private static M05Command.Artifact artifact(JsonNode node) {
    return new M05Command.Artifact(
        node.path("schemaVersion").stringValue(),
        node.path("instrumentId").stringValue(),
        integer(node.path("version")),
        integer(node.path("lowerInclusive")),
        integer(node.path("upperInclusive")),
        node.path("contentHash").stringValue());
  }

  private static M05Command.Identity identity(JsonNode node) {
    return new M05Command.Identity(
        integer(node.path("version")), node.path("contentHash").stringValue());
  }

  private static BigInteger integer(JsonNode node) {
    if (!node.isIntegralNumber()) {
      throw new IllegalStateException("M05 fixture integer changed type");
    }
    return node.bigIntegerValue();
  }

  private static List<String> strings(JsonNode nodes) {
    List<String> values = new ArrayList<>();
    nodes.forEach(node -> values.add(node.stringValue()));
    return List.copyOf(values);
  }

  record Corpus(List<Scenario> scenarios) {
    Corpus {
      scenarios = List.copyOf(scenarios);
    }

    Scenario scenario(String scenarioId) {
      return scenarios.stream()
          .filter(scenario -> scenario.scenarioId().equals(scenarioId))
          .findFirst()
          .orElseThrow(() -> new IllegalArgumentException("unknown M05 scenario " + scenarioId));
    }

    int commandCount() {
      return scenarios.stream().mapToInt(scenario -> scenario.steps().size()).sum();
    }
  }

  record Scenario(String scenarioId, List<String> proofObligations, List<Step> steps) {
    Scenario {
      proofObligations = List.copyOf(proofObligations);
      steps = List.copyOf(steps);
    }
  }

  record Step(String caseId, M05Command command) {}
}
