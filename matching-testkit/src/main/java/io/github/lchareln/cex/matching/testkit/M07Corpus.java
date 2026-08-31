package io.github.lchareln.cex.matching.testkit;

import io.github.lchareln.cex.matching.reference.M06MarketRuleSetArtifact;
import io.github.lchareln.cex.matching.reference.M06RuleSetIdentity;
import io.github.lchareln.cex.matching.reference.M07ReferenceCommand;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;

/** Strict parser for the immutable M07 fixed corpus and generator profile. */
final class M07Corpus {
  static final String FIXED_PATH = M07StartCheckRunner.FIXED_CORPUS_PATH;
  static final String PROFILE_PATH = M07StartCheckRunner.GENERATOR_PATH;

  private M07Corpus() {}

  static Fixed loadFixed(Path root) {
    byte[] bytes = read(root.resolve(FIXED_PATH));
    require(
        M07StartCheckRunner.FIXED_CORPUS_SHA256.equals(Hashing.sha256Hex(bytes)),
        "M07 fixed corpus SHA-256 changed");
    JsonNode document = JsonSupport.parse(bytes);
    JsonSupport.validate(
        document, readString(root.resolve(M07StartCheckRunner.FIXED_CORPUS_SCHEMA_PATH)), false);
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
      require(byId.put(scenario.id(), scenario) == null, "duplicate M07 scenario id");
      scenarios.add(scenario);
    }
    require(scenarios.size() == 16 && commands == 72, "M07 fixed corpus size changed");
    require(
        scenarios.stream().map(Scenario::id).toList().equals(M07StartCheckRunner.SCENARIO_IDS),
        "M07 fixed scenario order changed");
    return new Fixed(document, List.copyOf(scenarios), Map.copyOf(byId), commands);
  }

  static Profile loadProfile(Path root) {
    byte[] bytes = read(root.resolve(PROFILE_PATH));
    require(
        M07StartCheckRunner.GENERATOR_SHA256.equals(Hashing.sha256Hex(bytes)),
        "M07 generator SHA-256 changed");
    JsonNode document = JsonSupport.parse(bytes);
    JsonSupport.validate(
        document, readString(root.resolve(M07StartCheckRunner.GENERATOR_SCHEMA_PATH)), false);
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
            domain.path("legacyPlaceWeight").intValue(),
            domain.path("stpPlaceWeight").intValue(),
            domain.path("governedStpPlaceWeight").intValue(),
            domain.path("cancelWeight").intValue(),
            domain.path("prepareWeight").intValue(),
            domain.path("activateWeight").intValue(),
            domain.path("changeModeWeight").intValue(),
            domain.path("massCancelWeight").intValue(),
            domain.path("invalidGroupOneIn").intValue(),
            domain.path("invalidPolicyOneIn").intValue(),
            domain.path("invalidPairOneIn").intValue(),
            domain.path("sameGroupOneIn").intValue(),
            domain.path("minimumParticipantGroupId").longValue(),
            domain.path("maximumParticipantGroupId").longValue(),
            domain.path("minimumPriceTicks").longValue(),
            domain.path("maximumPriceTicks").longValue(),
            domain.path("maximumQuantityLots").longValue(),
            strings(domain.path("executionPolicies")),
            strings(domain.path("stpPolicies")),
            strings(domain.path("marketModes")));
    Profile profile =
        new Profile(
            document,
            Long.parseUnsignedLong(document.path("baseSeed").stringValue()),
            document.path("histories").intValue(),
            document.path("commandsPerHistory").intValue(),
            List.copyOf(lanes),
            randomDomain,
            strings(document.path("coverageRequirements")),
            strings(document.path("requiredMutants")),
            strings(document.path("tutorialPermalinks")));
    require(
        profile.histories() == 160
            && profile.commandsPerHistory() == 64
            && profile.lanes().size() == 5,
        "M07 generator dimensions changed");
    require(
        profile.lanes().stream().map(Lane::id).toList().equals(M07StartCheckRunner.LANE_IDS),
        "M07 generator lanes changed");
    require(
        profile.coverage().equals(M07StartCheckRunner.COVERAGE_IDS),
        "M07 coverage requirements changed");
    require(
        profile.mutants().equals(M07StartCheckRunner.REQUIRED_MUTANTS),
        "M07 required mutants changed");
    require(profile.tutorials().size() == 5, "M07 tutorial permalink count changed");
    return profile;
  }

  private static M07ReferenceCommand command(JsonNode node) {
    JsonNode input = node.path("input");
    return switch (node.path("type").stringValue()) {
      case "PLACE" -> place(node, input);
      case "CANCEL" ->
          new M07ReferenceCommand.Cancel(
              input.path("instrumentId").stringValue(), integer(input, "orderId"));
      case "PREPARE_RULE_SET" ->
          new M07ReferenceCommand.PrepareRuleSet(
              identity(input.path("expectedActive")), artifact(input.path("artifact")));
      case "ACTIVATE_RULE_SET" ->
          new M07ReferenceCommand.ActivateRuleSet(
              integer(input, "expectedApplicationSequence"),
              identity(input.path("expectedActive")),
              identity(input.path("target")));
      case "CHANGE_MARKET_MODE" ->
          new M07ReferenceCommand.ChangeMarketMode(
              integer(input, "expectedApplicationSequence"),
              input.path("expectedMode").stringValue(),
              input.path("targetMode").stringValue(),
              input.path("operatorId").stringValue());
      case "MASS_CANCEL" ->
          new M07ReferenceCommand.MassCancel(
              integer(input, "expectedApplicationSequence"),
              input.path("expectedMode").stringValue(),
              input.path("operatorId").stringValue());
      default -> throw new IllegalStateException("unsupported M07 command type");
    };
  }

  private static M07ReferenceCommand.Place place(JsonNode node, JsonNode input) {
    String instrument = input.path("instrumentId").stringValue();
    BigInteger orderId = integer(input, "orderId");
    String side = input.path("side").stringValue();
    BigInteger price = integer(input, "priceTicks");
    BigInteger quantity = integer(input, "quantityLots");
    String policy = input.path("executionPolicy").stringValue();
    String entrypoint = node.path("entrypoint").stringValue();
    return switch (entrypoint) {
      case "LEGACY" ->
          M07ReferenceCommand.Place.legacy(instrument, orderId, side, price, quantity, policy);
      case "GOVERNED" ->
          M07ReferenceCommand.Place.governed(
              identity(node.path("expectedRuleSet")),
              instrument,
              orderId,
              side,
              price,
              quantity,
              policy);
      case "STP" ->
          M07ReferenceCommand.Place.stp(
              instrument,
              orderId,
              side,
              price,
              quantity,
              policy,
              integer(node, "participantGroupId"),
              node.path("stpPolicy").stringValue());
      case "GOVERNED_STP" ->
          M07ReferenceCommand.Place.governedStp(
              identity(node.path("expectedRuleSet")),
              instrument,
              orderId,
              side,
              price,
              quantity,
              policy,
              integer(node, "participantGroupId"),
              node.path("stpPolicy").stringValue());
      default -> throw new IllegalStateException("unsupported M07 place entrypoint");
    };
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

  record Case(String id, String type, M07ReferenceCommand command) {}

  record Profile(
      JsonNode document,
      long baseSeed,
      int histories,
      int commandsPerHistory,
      List<Lane> lanes,
      Domain domain,
      List<String> coverage,
      List<String> mutants,
      List<String> tutorials) {}

  record Lane(String id, int modulo, String prefixScenario) {}

  record Domain(
      int legacyPlaceWeight,
      int stpPlaceWeight,
      int governedStpPlaceWeight,
      int cancelWeight,
      int prepareWeight,
      int activateWeight,
      int changeModeWeight,
      int massCancelWeight,
      int invalidGroupOneIn,
      int invalidPolicyOneIn,
      int invalidPairOneIn,
      int sameGroupOneIn,
      long minimumParticipantGroupId,
      long maximumParticipantGroupId,
      long minimumPriceTicks,
      long maximumPriceTicks,
      long maximumQuantityLots,
      List<String> executionPolicies,
      List<String> stpPolicies,
      List<String> marketModes) {}
}
