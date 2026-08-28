package io.github.lchareln.cex.matching.testkit;

import io.github.lchareln.cex.matching.reference.ReferenceCommand;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import tools.jackson.databind.JsonNode;

/** Strict executable view of the frozen M04 generator profile. */
final class M04GeneratorProfile {
  static final String SCHEMA_VERSION = "matching.m04.generator.v1";
  static final String ALGORITHM = "splitmix64-v1";

  private final long baseSeed;
  private final int histories;
  private final int commandsPerHistory;
  private final List<Lane> lanes;
  private final RandomDomain randomDomain;
  private final CoverageRequirements coverageRequirements;
  private final List<String> requiredMutants;

  private M04GeneratorProfile(
      long baseSeed,
      int histories,
      int commandsPerHistory,
      List<Lane> lanes,
      RandomDomain randomDomain,
      CoverageRequirements coverageRequirements,
      List<String> requiredMutants) {
    this.baseSeed = baseSeed;
    this.histories = histories;
    this.commandsPerHistory = commandsPerHistory;
    this.lanes = List.copyOf(lanes);
    this.randomDomain = Objects.requireNonNull(randomDomain, "randomDomain");
    this.coverageRequirements =
        Objects.requireNonNull(coverageRequirements, "coverageRequirements");
    this.requiredMutants = List.copyOf(requiredMutants);
  }

  static M04GeneratorProfile load(Path fixturePath, Path schemaPath) {
    try {
      return load(
          Files.readAllBytes(fixturePath), Files.readString(schemaPath, StandardCharsets.UTF_8));
    } catch (IOException exception) {
      throw new FixtureSchemaException("cannot read M04 generator profile or schema", exception);
    }
  }

  static M04GeneratorProfile load(byte[] bytes, String schema) {
    JsonNode root = JsonSupport.parse(bytes);
    JsonSupport.validate(root, schema, false);
    requireLexicalIntegers(root);
    require(
        SCHEMA_VERSION.equals(text(root, "schemaVersion")),
        "unsupported M04 generator schemaVersion");
    require(ALGORITHM.equals(text(root, "algorithm")), "unsupported M04 generator algorithm");
    long baseSeed = unsignedLong(root.path("baseSeed"), "baseSeed");
    int histories = exactInt(root.path("histories"), "histories");
    int commandsPerHistory = exactInt(root.path("commandsPerHistory"), "commandsPerHistory");

    List<Lane> lanes = new ArrayList<>();
    Set<String> ids = new HashSet<>();
    Set<Integer> modulos = new HashSet<>();
    for (JsonNode lane : root.path("lanes")) {
      String id = text(lane, "id");
      int modulo = exactInt(lane.path("historyModulo"), "lane.historyModulo");
      require(ids.add(id), "duplicate M04 lane id: " + id);
      require(modulos.add(modulo), "duplicate M04 lane modulo: " + modulo);
      List<ReferenceCommand> prefix = new ArrayList<>();
      lane.path("prefix").forEach(command -> prefix.add(M04Json.command(command)));
      require(prefix.size() <= commandsPerHistory, "M04 lane prefix is too long: " + id);
      lanes.add(new Lane(id, modulo, prefix));
    }
    require(histories % lanes.size() == 0, "M04 histories must divide evenly across lanes");
    for (int modulo = 0; modulo < lanes.size(); modulo++) {
      require(modulos.contains(modulo), "M04 lane modulos must be contiguous");
    }

    JsonNode domain = root.path("randomDomain");
    List<WeightedPolicy> policies = new ArrayList<>();
    for (JsonNode policy : domain.path("executionPolicies")) {
      policies.add(
          new WeightedPolicy(text(policy, "id"), exactInt(policy.path("weight"), "policy.weight")));
    }
    RandomDomain randomDomain =
        new RandomDomain(
            exactInt(domain.path("placeWeight"), "placeWeight"),
            exactInt(domain.path("cancelWeight"), "cancelWeight"),
            exactInt(domain.path("invalidOneIn"), "invalidOneIn"),
            exactInt(domain.path("unknownPolicyOneIn"), "unknownPolicyOneIn"),
            text(domain, "validInstrumentId"),
            text(domain, "invalidInstrumentId"),
            integer(domain.path("minimumOrderId"), "minimumOrderId"),
            integer(domain.path("maximumOrderId"), "maximumOrderId"),
            strings(domain.path("validSides")),
            text(domain, "invalidSide"),
            integer(domain.path("minimumPriceTicks"), "minimumPriceTicks"),
            integer(domain.path("maximumPriceTicks"), "maximumPriceTicks"),
            integer(domain.path("minimumQuantityLots"), "minimumQuantityLots"),
            integer(domain.path("maximumQuantityLots"), "maximumQuantityLots"),
            policies,
            text(domain, "invalidExecutionPolicy"));
    randomDomain.validate();

    JsonNode coverage = root.path("coverageRequirements");
    CoverageRequirements coverageRequirements =
        new CoverageRequirements(
            strings(coverage.path("ioc")),
            strings(coverage.path("fok")),
            strings(coverage.path("postOnly")),
            coverage.path("rejectionIdentityAndSequence").booleanValue(),
            coverage.path("buyAndSell").booleanValue());
    coverageRequirements.validate();

    List<String> requiredMutants = strings(root.path("requiredMutants"));
    require(
        M04StartCheckRunner.REQUIRED_MUTANTS.equals(requiredMutants),
        "M04 required mutant order changed");
    return new M04GeneratorProfile(
        baseSeed,
        histories,
        commandsPerHistory,
        lanes,
        randomDomain,
        coverageRequirements,
        requiredMutants);
  }

  long baseSeed() {
    return baseSeed;
  }

  int histories() {
    return histories;
  }

  int commandsPerHistory() {
    return commandsPerHistory;
  }

  List<Lane> lanes() {
    return lanes;
  }

  RandomDomain randomDomain() {
    return randomDomain;
  }

  CoverageRequirements coverageRequirements() {
    return coverageRequirements;
  }

  List<String> requiredMutants() {
    return requiredMutants;
  }

  Lane laneForHistory(int historyIndex) {
    if (historyIndex < 0 || historyIndex >= histories) {
      throw new IllegalArgumentException("historyIndex is outside M04 profile");
    }
    int modulo = historyIndex % lanes.size();
    return lanes.stream()
        .filter(lane -> lane.historyModulo() == modulo)
        .findFirst()
        .orElseThrow(() -> new IllegalStateException("M04 lane modulo is missing"));
  }

  record Lane(String id, int historyModulo, List<ReferenceCommand> prefix) {
    Lane {
      Objects.requireNonNull(id, "id");
      prefix = List.copyOf(prefix);
    }
  }

  record WeightedPolicy(String id, int weight) {
    WeightedPolicy {
      Objects.requireNonNull(id, "id");
      if (weight <= 0) {
        throw new IllegalArgumentException("policy weight must be positive");
      }
    }
  }

  record CoverageRequirements(
      List<String> ioc,
      List<String> fok,
      List<String> postOnly,
      boolean rejectionIdentityAndSequence,
      boolean buyAndSell) {
    CoverageRequirements {
      ioc = List.copyOf(ioc);
      fok = List.copyOf(fok);
      postOnly = List.copyOf(postOnly);
    }

    void validate() {
      require(
          ioc.equals(List.of("ZERO_FILL", "PARTIAL_FILL", "FULL_FILL")),
          "M04 IOC coverage declarations changed");
      require(
          fok.equals(List.of("INSUFFICIENT", "EXACT", "MULTI_LEVEL", "OUTSIDE_LIMIT_EXCLUDED")),
          "M04 FOK coverage declarations changed");
      require(
          postOnly.equals(List.of("EMPTY_BOOK", "NON_CROSSING", "TOUCH", "CROSS")),
          "M04 Post-only coverage declarations changed");
      require(rejectionIdentityAndSequence, "M04 rejection identity coverage disabled");
      require(buyAndSell, "M04 side coverage disabled");
    }
  }

  record RandomDomain(
      int placeWeight,
      int cancelWeight,
      int invalidOneIn,
      int unknownPolicyOneIn,
      String validInstrumentId,
      String invalidInstrumentId,
      BigInteger minimumOrderId,
      BigInteger maximumOrderId,
      List<String> validSides,
      String invalidSide,
      BigInteger minimumPriceTicks,
      BigInteger maximumPriceTicks,
      BigInteger minimumQuantityLots,
      BigInteger maximumQuantityLots,
      List<WeightedPolicy> policies,
      String invalidExecutionPolicy) {
    RandomDomain {
      validSides = List.copyOf(validSides);
      policies = List.copyOf(policies);
    }

    void validate() {
      require(placeWeight > 0 && cancelWeight > 0, "M04 command weights must be positive");
      require(invalidOneIn > 0 && unknownPolicyOneIn > 0, "M04 ratios must be positive");
      require(!validInstrumentId.equals(invalidInstrumentId), "invalid instrument is valid");
      require(!validSides.contains(invalidSide), "invalid side is valid");
      require(
          !policies.stream().map(WeightedPolicy::id).toList().contains(invalidExecutionPolicy),
          "invalid policy is valid");
      requireRange(minimumOrderId, maximumOrderId, "orderId");
      requireRange(minimumPriceTicks, maximumPriceTicks, "priceTicks");
      requireRange(minimumQuantityLots, maximumQuantityLots, "quantityLots");
    }

    int totalCommandWeight() {
      return Math.addExact(placeWeight, cancelWeight);
    }

    int totalPolicyWeight() {
      return policies.stream().mapToInt(WeightedPolicy::weight).reduce(0, Math::addExact);
    }
  }

  private static void requireRange(BigInteger minimum, BigInteger maximum, String field) {
    require(minimum.compareTo(maximum) <= 0, "M04 " + field + " range is reversed");
    try {
      maximum.subtract(minimum).add(BigInteger.ONE).intValueExact();
    } catch (ArithmeticException exception) {
      throw new FixtureSchemaException("M04 " + field + " range is too wide", exception);
    }
  }

  private static List<String> strings(JsonNode nodes) {
    List<String> values = new ArrayList<>(nodes.size());
    nodes.forEach(node -> values.add(node.stringValue()));
    return List.copyOf(values);
  }

  private static String text(JsonNode node, String field) {
    JsonNode value = node.path(field);
    if (!value.isString()) {
      throw new FixtureSchemaException("M04 " + field + " must be a string");
    }
    return value.stringValue();
  }

  private static BigInteger integer(JsonNode node, String field) {
    if (!node.isIntegralNumber()) {
      throw new FixtureSchemaException("M04 " + field + " must be an integer");
    }
    return node.bigIntegerValue();
  }

  private static int exactInt(JsonNode node, String field) {
    try {
      return integer(node, field).intValueExact();
    } catch (ArithmeticException exception) {
      throw new FixtureSchemaException("M04 " + field + " is outside int range", exception);
    }
  }

  private static long unsignedLong(JsonNode node, String field) {
    try {
      return Long.parseUnsignedLong(node.stringValue());
    } catch (RuntimeException exception) {
      throw new FixtureSchemaException("M04 " + field + " is outside unsigned long", exception);
    }
  }

  private static void requireLexicalIntegers(JsonNode node) {
    if (node.isNumber() && !node.isIntegralNumber()) {
      throw new FixtureSchemaException("M04 numeric values must use integer JSON tokens");
    }
    node.forEach(M04GeneratorProfile::requireLexicalIntegers);
  }

  private static void require(boolean condition, String message) {
    if (!condition) {
      throw new FixtureSchemaException(message);
    }
  }
}
