package io.github.lchareln.cex.matching.testkit;

import io.github.lchareln.cex.matching.reference.ReferenceCommand;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import tools.jackson.databind.JsonNode;

/** Strict loader for the frozen 14-scenario M04 input corpus. */
final class M04ScenarioCorpus {
  private M04ScenarioCorpus() {}

  static List<Scenario> load(Path fixture, Path schema) {
    try {
      byte[] bytes = Files.readAllBytes(fixture);
      JsonNode root = JsonSupport.parse(bytes);
      JsonSupport.validate(root, Files.readString(schema, StandardCharsets.UTF_8), false);
      List<Scenario> scenarios = new ArrayList<>();
      Set<String> scenarioIds = new HashSet<>();
      Set<String> caseIds = new HashSet<>();
      for (JsonNode source : root.path("scenarios")) {
        String scenarioId = source.path("scenarioId").stringValue();
        require(scenarioIds.add(scenarioId), "duplicate M04 scenarioId: " + scenarioId);
        List<Case> cases = new ArrayList<>();
        for (JsonNode command : source.path("commands")) {
          String caseId = command.path("caseId").stringValue();
          require(caseIds.add(caseId), "duplicate M04 caseId: " + caseId);
          cases.add(new Case(caseId, M04Json.command(command)));
        }
        scenarios.add(new Scenario(scenarioId, cases));
      }
      require(scenarios.size() == M04StartCheckRunner.SCENARIOS, "M04 scenario count changed");
      return List.copyOf(scenarios);
    } catch (IOException exception) {
      throw new FixtureSchemaException("cannot read M04 scenario corpus", exception);
    }
  }

  record Scenario(String id, List<Case> cases) {
    Scenario {
      cases = List.copyOf(cases);
    }

    List<ReferenceCommand> commands() {
      return cases.stream().map(Case::command).toList();
    }
  }

  record Case(String id, ReferenceCommand command) {}

  private static void require(boolean condition, String message) {
    if (!condition) {
      throw new FixtureSchemaException(message);
    }
  }
}
