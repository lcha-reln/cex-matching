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

/** Strict, immutable input profile for the deterministic M03 command generator. */
public final class M03GeneratorProfile {
  public static final String SCHEMA_VERSION = "matching.m03.generator.v1";
  public static final String ALGORITHM = "splitmix64-v1";

  private final String schemaVersion;
  private final String algorithm;
  private final long baseSeed;
  private final int histories;
  private final int commandsPerHistory;
  private final List<Lane> lanes;
  private final RandomDomain randomDomain;

  private M03GeneratorProfile(
      String schemaVersion,
      String algorithm,
      long baseSeed,
      int histories,
      int commandsPerHistory,
      List<Lane> lanes,
      RandomDomain randomDomain) {
    this.schemaVersion = Objects.requireNonNull(schemaVersion, "schemaVersion");
    this.algorithm = Objects.requireNonNull(algorithm, "algorithm");
    this.baseSeed = baseSeed;
    this.histories = histories;
    this.commandsPerHistory = commandsPerHistory;
    this.lanes = List.copyOf(lanes);
    this.randomDomain = Objects.requireNonNull(randomDomain, "randomDomain");
  }

  public static M03GeneratorProfile load(Path fixturePath, Path schemaPath) {
    try {
      return load(
          Files.readAllBytes(fixturePath), Files.readString(schemaPath, StandardCharsets.UTF_8));
    } catch (IOException exception) {
      throw new FixtureSchemaException("cannot read M03 generator profile or schema", exception);
    }
  }

  static M03GeneratorProfile load(byte[] fixtureBytes, String schemaSource) {
    JsonNode root = JsonSupport.parse(fixtureBytes);
    JsonSupport.validate(root, schemaSource, false);
    requireLexicalIntegers(root);

    String schemaVersion = scalarString(root.path("schemaVersion"), "schemaVersion");
    String algorithm = scalarString(root.path("algorithm"), "algorithm");
    require(SCHEMA_VERSION.equals(schemaVersion), "unsupported M03 generator schemaVersion");
    require(ALGORITHM.equals(algorithm), "unsupported M03 generator algorithm");

    long baseSeed = unsignedLong(root.path("baseSeed"), "baseSeed");
    int histories = exactInt(root.path("histories"), "histories");
    int commandsPerHistory = exactInt(root.path("commandsPerHistory"), "commandsPerHistory");

    List<Lane> lanes = new ArrayList<>();
    Set<String> laneIds = new HashSet<>();
    Set<Integer> laneModulos = new HashSet<>();
    for (JsonNode laneNode : root.path("lanes")) {
      String id = scalarString(laneNode.path("id"), "lane.id");
      int historyModulo = exactInt(laneNode.path("historyModulo"), "lane.historyModulo");
      require(laneIds.add(id), "duplicate M03 lane id: " + id);
      require(laneModulos.add(historyModulo), "duplicate M03 lane historyModulo: " + historyModulo);
      List<ReferenceCommand> prefix = new ArrayList<>();
      for (JsonNode commandNode : laneNode.path("prefix")) {
        prefix.add(command(commandNode));
      }
      require(
          prefix.size() <= commandsPerHistory, "M03 lane prefix exceeds commandsPerHistory: " + id);
      lanes.add(new Lane(id, historyModulo, prefix));
    }

    require(histories % lanes.size() == 0, "M03 histories must divide evenly across lanes");
    for (int expectedModulo = 0; expectedModulo < lanes.size(); expectedModulo++) {
      require(
          laneModulos.contains(expectedModulo),
          "M03 lane historyModulo values must cover zero through laneCount - 1");
    }

    RandomDomain domain = randomDomain(root.path("randomDomain"));
    return new M03GeneratorProfile(
        schemaVersion, algorithm, baseSeed, histories, commandsPerHistory, lanes, domain);
  }

  private static RandomDomain randomDomain(JsonNode node) {
    int placeWeight = exactInt(node.path("placeWeight"), "randomDomain.placeWeight");
    int cancelWeight = exactInt(node.path("cancelWeight"), "randomDomain.cancelWeight");
    int invalidOneIn = exactInt(node.path("invalidOneIn"), "randomDomain.invalidOneIn");
    String validInstrumentId =
        scalarString(node.path("validInstrumentId"), "randomDomain.validInstrumentId");
    String invalidInstrumentId =
        scalarString(node.path("invalidInstrumentId"), "randomDomain.invalidInstrumentId");
    BigInteger minimumOrderId = integer(node.path("minimumOrderId"), "minimumOrderId");
    BigInteger maximumOrderId = integer(node.path("maximumOrderId"), "maximumOrderId");
    List<String> validSides = new ArrayList<>();
    for (JsonNode side : node.path("validSides")) {
      validSides.add(scalarString(side, "randomDomain.validSides"));
    }
    String invalidSide = scalarString(node.path("invalidSide"), "randomDomain.invalidSide");
    BigInteger minimumPriceTicks = integer(node.path("minimumPriceTicks"), "minimumPriceTicks");
    BigInteger maximumPriceTicks = integer(node.path("maximumPriceTicks"), "maximumPriceTicks");
    BigInteger minimumQuantityLots =
        integer(node.path("minimumQuantityLots"), "minimumQuantityLots");
    BigInteger maximumQuantityLots =
        integer(node.path("maximumQuantityLots"), "maximumQuantityLots");

    require(placeWeight <= Integer.MAX_VALUE - cancelWeight, "M03 command weights overflow");
    require(!validInstrumentId.equals(invalidInstrumentId), "M03 invalid instrument is valid");
    require(!validSides.contains(invalidSide), "M03 invalid side is valid");
    requireRange(minimumOrderId, maximumOrderId, "orderId");
    requireRange(minimumPriceTicks, maximumPriceTicks, "priceTicks");
    requireRange(minimumQuantityLots, maximumQuantityLots, "quantityLots");

    return new RandomDomain(
        placeWeight,
        cancelWeight,
        invalidOneIn,
        validInstrumentId,
        invalidInstrumentId,
        minimumOrderId,
        maximumOrderId,
        validSides,
        invalidSide,
        minimumPriceTicks,
        maximumPriceTicks,
        minimumQuantityLots,
        maximumQuantityLots);
  }

  private static ReferenceCommand command(JsonNode node) {
    JsonNode input = node.path("input");
    return switch (node.path("type").stringValue()) {
      case "PLACE" ->
          new ReferenceCommand.Place(
              scalarString(input.path("instrumentId"), "instrumentId"),
              integer(input.path("orderId"), "orderId"),
              scalarString(input.path("side"), "side"),
              integer(input.path("priceTicks"), "priceTicks"),
              integer(input.path("quantityLots"), "quantityLots"));
      case "CANCEL" ->
          new ReferenceCommand.Cancel(
              scalarString(input.path("instrumentId"), "instrumentId"),
              integer(input.path("orderId"), "orderId"));
      default -> throw new FixtureSchemaException("unknown M03 command type");
    };
  }

  private static void requireRange(BigInteger minimum, BigInteger maximum, String field) {
    require(minimum.compareTo(maximum) <= 0, "M03 " + field + " range is reversed");
    try {
      maximum.subtract(minimum).add(BigInteger.ONE).intValueExact();
    } catch (ArithmeticException exception) {
      throw new FixtureSchemaException("M03 " + field + " range is too wide", exception);
    }
  }

  private static void requireLexicalIntegers(JsonNode node) {
    if (node.isNumber() && !node.isIntegralNumber()) {
      throw new FixtureSchemaException("M03 numeric values must use integer JSON tokens");
    }
    for (JsonNode child : node) {
      requireLexicalIntegers(child);
    }
  }

  private static BigInteger integer(JsonNode node, String field) {
    if (!node.isIntegralNumber()) {
      throw new FixtureSchemaException(field + " must use an integer JSON token");
    }
    return node.bigIntegerValue();
  }

  private static int exactInt(JsonNode node, String field) {
    try {
      return integer(node, field).intValueExact();
    } catch (ArithmeticException exception) {
      throw new FixtureSchemaException(field + " is outside int range", exception);
    }
  }

  private static long unsignedLong(JsonNode node, String field) {
    String value = scalarString(node, field);
    try {
      return Long.parseUnsignedLong(value);
    } catch (NumberFormatException exception) {
      throw new FixtureSchemaException(field + " is outside unsigned 64-bit range", exception);
    }
  }

  private static String scalarString(JsonNode node, String field) {
    String value = node.stringValue();
    for (int index = 0; index < value.length(); index++) {
      char current = value.charAt(index);
      if (Character.isHighSurrogate(current)) {
        if (index + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(index + 1))) {
          throw new FixtureSchemaException(field + " contains an unpaired high surrogate");
        }
        index++;
      } else if (Character.isLowSurrogate(current)) {
        throw new FixtureSchemaException(field + " contains an unpaired low surrogate");
      }
    }
    return value;
  }

  private static void require(boolean condition, String message) {
    if (!condition) {
      throw new FixtureSchemaException(message);
    }
  }

  public String schemaVersion() {
    return schemaVersion;
  }

  public String algorithm() {
    return algorithm;
  }

  public long baseSeed() {
    return baseSeed;
  }

  public int histories() {
    return histories;
  }

  public int commandsPerHistory() {
    return commandsPerHistory;
  }

  public List<Lane> lanes() {
    return lanes;
  }

  public RandomDomain randomDomain() {
    return randomDomain;
  }

  public Lane laneForHistory(int historyIndex) {
    if (historyIndex < 0 || historyIndex >= histories) {
      throw new IllegalArgumentException("historyIndex is outside the profile");
    }
    int modulo = historyIndex % lanes.size();
    return lanes.stream()
        .filter(lane -> lane.historyModulo() == modulo)
        .findFirst()
        .orElseThrow(() -> new IllegalStateException("M03 lane modulo is missing"));
  }

  public record Lane(String id, int historyModulo, List<ReferenceCommand> prefix) {
    public Lane {
      Objects.requireNonNull(id, "id");
      prefix = List.copyOf(prefix);
    }
  }

  public record RandomDomain(
      int placeWeight,
      int cancelWeight,
      int invalidOneIn,
      String validInstrumentId,
      String invalidInstrumentId,
      BigInteger minimumOrderId,
      BigInteger maximumOrderId,
      List<String> validSides,
      String invalidSide,
      BigInteger minimumPriceTicks,
      BigInteger maximumPriceTicks,
      BigInteger minimumQuantityLots,
      BigInteger maximumQuantityLots) {
    public RandomDomain {
      Objects.requireNonNull(validInstrumentId, "validInstrumentId");
      Objects.requireNonNull(invalidInstrumentId, "invalidInstrumentId");
      Objects.requireNonNull(minimumOrderId, "minimumOrderId");
      Objects.requireNonNull(maximumOrderId, "maximumOrderId");
      validSides = List.copyOf(validSides);
      Objects.requireNonNull(invalidSide, "invalidSide");
      Objects.requireNonNull(minimumPriceTicks, "minimumPriceTicks");
      Objects.requireNonNull(maximumPriceTicks, "maximumPriceTicks");
      Objects.requireNonNull(minimumQuantityLots, "minimumQuantityLots");
      Objects.requireNonNull(maximumQuantityLots, "maximumQuantityLots");
    }

    public int totalWeight() {
      return placeWeight + cancelWeight;
    }
  }
}
