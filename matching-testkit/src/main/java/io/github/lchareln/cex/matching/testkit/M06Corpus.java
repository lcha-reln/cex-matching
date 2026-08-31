package io.github.lchareln.cex.matching.testkit;

import io.github.lchareln.cex.matching.reference.M06MarketRuleSetArtifact;
import io.github.lchareln.cex.matching.reference.M06ReferenceCommand;
import io.github.lchareln.cex.matching.reference.M06RuleSetIdentity;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;

/** Strict parser for the immutable M06 fixed corpus and generator profile. */
final class M06Corpus {
  static final String FIXED_PATH = M06StartCheckRunner.FIXED_CORPUS_PATH;
  static final String PROFILE_PATH = M06StartCheckRunner.GENERATOR_PATH;

  private M06Corpus() {}

  static Fixed loadFixed(Path root) {
    byte[] bytes = read(root.resolve(FIXED_PATH));
    require(
        M06StartCheckRunner.FIXED_CORPUS_SHA256.equals(Hashing.sha256Hex(bytes)),
        "M06 fixed corpus SHA-256 changed");
    JsonNode document = JsonSupport.parse(bytes);
    JsonSupport.validate(
        document, readString(root.resolve(M06StartCheckRunner.FIXED_CORPUS_SCHEMA_PATH)), false);
    List<Scenario> scenarios = new ArrayList<>();
    Map<String, Scenario> byId = new LinkedHashMap<>();
    int commands = 0;
    for (JsonNode scenarioNode : document.path("scenarios")) {
      List<String> obligations = strings(scenarioNode.path("proofObligations"));
      List<Case> cases = new ArrayList<>();
      for (JsonNode commandNode : scenarioNode.path("commands")) {
        cases.add(
            new Case(
                commandNode.path("caseId").stringValue(),
                commandNode.path("type").stringValue(),
                command(commandNode)));
        commands++;
      }
      Scenario scenario =
          new Scenario(
              scenarioNode.path("scenarioId").stringValue(), obligations, List.copyOf(cases));
      require(byId.put(scenario.id(), scenario) == null, "duplicate M06 scenario id");
      scenarios.add(scenario);
    }
    require(scenarios.size() == 15 && commands == 64, "M06 fixed corpus size changed");
    require(
        scenarios.stream().map(Scenario::id).toList().equals(M06StartCheckRunner.SCENARIO_IDS),
        "M06 fixed scenario order changed");
    return new Fixed(document, List.copyOf(scenarios), Map.copyOf(byId), commands);
  }

  static Profile loadProfile(Path root) {
    byte[] bytes = read(root.resolve(PROFILE_PATH));
    require(
        M06StartCheckRunner.GENERATOR_SHA256.equals(Hashing.sha256Hex(bytes)),
        "M06 generator SHA-256 changed");
    JsonNode document = JsonSupport.parse(bytes);
    JsonSupport.validate(
        document, readString(root.resolve(M06StartCheckRunner.GENERATOR_SCHEMA_PATH)), false);
    List<Lane> lanes = new ArrayList<>();
    for (JsonNode lane : document.path("lanes")) {
      lanes.add(
          new Lane(
              lane.path("id").stringValue(),
              lane.path("historyModulo").intValue(),
              lane.path("prefixScenario").stringValue()));
    }
    JsonNode domain = document.path("randomDomain");
    Domain randomDomain =
        new Domain(
            domain.path("placeWeight").intValue(),
            domain.path("cancelWeight").intValue(),
            domain.path("changeModeWeight").intValue(),
            domain.path("massCancelWeight").intValue(),
            domain.path("prepareWeight").intValue(),
            domain.path("activateWeight").intValue(),
            domain.path("invalidFieldOneIn").intValue(),
            domain.path("staleApplicationOneIn").intValue(),
            domain.path("staleModeOneIn").intValue(),
            domain.path("massCancelOutsideHaltedOneIn").intValue(),
            domain.path("minimumPriceTicks").longValue(),
            domain.path("maximumPriceTicks").longValue(),
            domain.path("maximumQuantityLots").longValue(),
            strings(domain.path("executionPolicies")),
            strings(domain.path("targetModes")));
    Profile profile =
        new Profile(
            document,
            Long.parseUnsignedLong(document.path("baseSeed").stringValue()),
            document.path("histories").intValue(),
            document.path("commandsPerHistory").intValue(),
            List.copyOf(lanes),
            randomDomain,
            strings(document.path("coverageRequirements")),
            strings(document.path("requiredMutants")));
    require(
        profile.histories() == 160
            && profile.commandsPerHistory() == 64
            && profile.lanes().size() == 5,
        "M06 generator dimensions changed");
    require(
        profile.lanes().stream().map(Lane::id).toList().equals(M06StartCheckRunner.LANE_IDS),
        "M06 generator lanes changed");
    require(
        profile.coverage().equals(M06StartCheckRunner.COVERAGE_IDS),
        "M06 coverage requirements changed");
    require(
        profile.mutants().equals(M06StartCheckRunner.REQUIRED_MUTANTS),
        "M06 required mutants changed");
    return profile;
  }

  private static M06ReferenceCommand command(JsonNode node) {
    JsonNode input = node.path("input");
    return switch (node.path("type").stringValue()) {
      case "PLACE" -> place(node, input);
      case "CANCEL" ->
          new M06ReferenceCommand.Cancel(
              input.path("instrumentId").stringValue(), integer(input, "orderId"));
      case "PREPARE_RULE_SET" ->
          new M06ReferenceCommand.PrepareRuleSet(
              identity(input.path("expectedActive")), artifact(input.path("artifact")));
      case "ACTIVATE_RULE_SET" ->
          new M06ReferenceCommand.ActivateRuleSet(
              integer(input, "expectedApplicationSequence"),
              identity(input.path("expectedActive")),
              identity(input.path("target")));
      case "CHANGE_MARKET_MODE" ->
          new M06ReferenceCommand.ChangeMarketMode(
              integer(input, "expectedApplicationSequence"),
              input.path("expectedMode").stringValue(),
              input.path("targetMode").stringValue(),
              input.path("operatorId").stringValue());
      case "MASS_CANCEL" ->
          new M06ReferenceCommand.MassCancel(
              integer(input, "expectedApplicationSequence"),
              input.path("expectedMode").stringValue(),
              input.path("operatorId").stringValue());
      default -> throw new IllegalStateException("unsupported M06 command type");
    };
  }

  private static M06ReferenceCommand.Place place(JsonNode node, JsonNode input) {
    String instrument = input.path("instrumentId").stringValue();
    BigInteger orderId = integer(input, "orderId");
    String side = input.path("side").stringValue();
    BigInteger price = integer(input, "priceTicks");
    BigInteger quantity = integer(input, "quantityLots");
    String policy = input.path("executionPolicy").stringValue();
    if ("GOVERNED".equals(node.path("entrypoint").stringValue())) {
      return M06ReferenceCommand.Place.governed(
          identity(node.path("expectedRuleSet")),
          instrument,
          orderId,
          side,
          price,
          quantity,
          policy);
    }
    return M06ReferenceCommand.Place.legacy(instrument, orderId, side, price, quantity, policy);
  }

  private static M06RuleSetIdentity identity(JsonNode node) {
    return new M06RuleSetIdentity(integer(node, "version"), node.path("contentHash").stringValue());
  }

  private static M06MarketRuleSetArtifact artifact(JsonNode node) {
    return new M06MarketRuleSetArtifact(
        node.path("schemaVersion").stringValue(),
        node.path("instrumentId").stringValue(),
        integer(node, "version"),
        integer(node, "lowerInclusive"),
        integer(node, "upperInclusive"),
        node.path("contentHash").stringValue());
  }

  private static BigInteger integer(JsonNode node, String field) {
    return node.path(field).bigIntegerValue();
  }

  private static List<String> strings(JsonNode node) {
    List<String> values = new ArrayList<>();
    node.forEach(value -> values.add(value.stringValue()));
    return List.copyOf(values);
  }

  private static byte[] read(Path path) {
    try {
      return Files.readAllBytes(path);
    } catch (IOException failure) {
      throw new IllegalStateException("cannot read " + path, failure);
    }
  }

  private static String readString(Path path) {
    try {
      return Files.readString(path);
    } catch (IOException failure) {
      throw new IllegalStateException("cannot read " + path, failure);
    }
  }

  private static void require(boolean condition, String message) {
    if (!condition) {
      throw new IllegalStateException(message);
    }
  }

  record Fixed(
      JsonNode document, List<Scenario> scenarios, Map<String, Scenario> byId, int commands) {}

  record Scenario(String id, List<String> obligations, List<Case> cases) {}

  record Case(String id, String type, M06ReferenceCommand command) {}

  record Profile(
      JsonNode document,
      long baseSeed,
      int histories,
      int commandsPerHistory,
      List<Lane> lanes,
      Domain domain,
      List<String> coverage,
      List<String> mutants) {}

  record Lane(String id, int modulo, String prefixScenario) {}

  record Domain(
      int placeWeight,
      int cancelWeight,
      int changeModeWeight,
      int massCancelWeight,
      int prepareWeight,
      int activateWeight,
      int invalidFieldOneIn,
      int staleApplicationOneIn,
      int staleModeOneIn,
      int massCancelOutsideHaltedOneIn,
      long minimumPriceTicks,
      long maximumPriceTicks,
      long maximumQuantityLots,
      List<String> executionPolicies,
      List<String> targetModes) {}
}
