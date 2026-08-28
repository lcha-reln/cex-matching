package io.github.lchareln.cex.matching.testkit;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Validates the frozen M02 oracle and writes the intentional start-state gap report. */
public final class M02StartCheckRunner {
  public static final String STATUS = "GOAL_NOT_IMPLEMENTED";
  public static final String FROZEN_FIXTURE_SHA256 =
      "7e0be70259dcf1b4b422d68742b5c24f1a4d11b05643e2d9e367b67733d4a90a";

  private static final int EXPECTED_SCENARIOS = 10;
  private static final int EXPECTED_COMMANDS = 34;
  private static final int EXPECTED_PLACE_COMMANDS = 22;
  private static final int EXPECTED_CANCEL_COMMANDS = 12;
  private static final int EXPECTED_SCHEMA_PROBES = 8;
  private static final List<Integer> EXPECTED_COMMAND_COUNTS =
      List.of(4, 2, 5, 3, 2, 4, 3, 3, 4, 4);
  private static final List<String> FROZEN_SCENARIO_IDS =
      List.of(
          "invalid-cancel-does-not-mutate-or-consume-sequence",
          "cancel-only-resting-order-removes-level",
          "cancel-middle-preserves-fifo",
          "cancel-partially-filled-remainder",
          "cancel-unknown-order",
          "late-cancel-filled-order",
          "repeat-cancel-stable",
          "duplicate-active-order-id",
          "duplicate-filled-order-id-does-not-resurrect",
          "duplicate-canceled-order-id-does-not-resurrect");
  private static final List<String> FROZEN_CASE_IDS =
      List.of(
          "seed-resting-bid-before-invalid-cancels",
          "reject-cancel-unknown-instrument",
          "reject-cancel-nonpositive-order-id",
          "next-place-still-sequence-two",
          "rest-only-ask-before-cancel",
          "cancel-only-ask-removes-level",
          "fifo-survivor-one-rests",
          "fifo-middle-maker-rests",
          "fifo-survivor-three-rests",
          "cancel-middle-maker-only",
          "taker-observes-survivor-fifo",
          "partial-maker-rests-five",
          "partial-maker-trades-two",
          "cancel-only-partial-remainder",
          "cancel-never-seen-order",
          "place-after-unknown-cancel-uses-sequence-one",
          "filled-maker-rests-before-trade",
          "maker-becomes-filled",
          "late-cancel-reports-filled-terminal",
          "late-cancel-filled-taker-reports-filled-terminal",
          "repeat-target-rests",
          "first-cancel-succeeds",
          "repeat-cancel-reports-canceled-terminal",
          "active-original-rests",
          "duplicate-active-place-rejected",
          "place-after-active-duplicate-uses-sequence-two",
          "filled-identity-maker-rests",
          "filled-identity-maker-completes",
          "duplicate-filled-place-rejected",
          "filled-identity-remains-filled-after-duplicate",
          "canceled-identity-original-rests",
          "canceled-identity-enters-terminal-state",
          "duplicate-canceled-place-rejected",
          "place-after-canceled-duplicate-uses-sequence-two");

  private static final String FIXTURE_PATH =
      "matching-testkit/src/test/resources/m02/fixtures/order-lifecycle-v1.json";
  private static final String FIXTURE_SCHEMA_PATH = "schemas/matching.m02.scenario.v1.schema.json";
  private static final String CHECK_SCHEMA_PATH = "schemas/matching.m02.check.v1.schema.json";

  public Result run(Path repositoryRoot, Path reportDirectory) {
    return run(repositoryRoot, reportDirectory, repositoryRoot);
  }

  Result run(Path repositoryRoot, Path reportDirectory, Path trustedOutputRoot) {
    Path root = repositoryRoot.toAbsolutePath().normalize();
    Path reports = SafeOutputPaths.resolveTrustedOutput(trustedOutputRoot, reportDirectory);
    clearStaleReport(reports);

    byte[] fixtureBytes = readBytes(root.resolve(FIXTURE_PATH));
    String fixtureSha256 = Hashing.sha256Hex(fixtureBytes);
    require(
        FROZEN_FIXTURE_SHA256.equals(fixtureSha256), "M02 frozen scenario corpus SHA-256 changed");
    String fixtureSchema = readString(root.resolve(FIXTURE_SCHEMA_PATH));
    JsonNode fixture = JsonSupport.parse(fixtureBytes);
    JsonSupport.validate(fixture, fixtureSchema, false);
    requireLexicalIntegers(fixture);
    CorpusFacts facts = verifyFrozenCorpus(fixture);
    int schemaProbes = verifyScenarioBoundary(fixtureBytes, fixtureSchema);

    ObjectNode report = JsonSupport.MAPPER.createObjectNode();
    report.put("schemaVersion", "matching.m02.check.v1");
    report.put("unit", "M02");
    report.put("status", STATUS);
    report.put("contractPlanVersion", "0.4");
    report.put(
        "objective", "Implement addressable cancellation and irreversible order terminal states.");
    ObjectNode corpus = report.putObject("scenarioCorpus");
    corpus.put("path", FIXTURE_PATH);
    corpus.put("schemaPath", FIXTURE_SCHEMA_PATH);
    corpus.put("sha256", fixtureSha256);
    corpus.put("scenarios", facts.scenarios());
    corpus.put("commands", facts.commands());
    corpus.put("placeCommands", facts.placeCommands());
    corpus.put("cancelCommands", facts.cancelCommands());
    corpus.put("schemaProbes", schemaProbes);
    ArrayNode missing = report.putArray("missingCapabilities");
    missing.add("ADDRESSABLE_LIFECYCLE_REGISTRY");
    missing.add("ADDRESSABLE_CANCEL");
    missing.add("IRREVERSIBLE_TERMINAL_STATE");

    JsonSupport.validate(report, readString(root.resolve(CHECK_SCHEMA_PATH)), false);
    Path reportPath = reports.resolve("check.json");
    AtomicFiles.write(reportPath, JsonSupport.prettyBytes(report));
    return new Result(STATUS, reportPath);
  }

  private static CorpusFacts verifyFrozenCorpus(JsonNode fixture) {
    List<String> scenarioIds = new ArrayList<>();
    List<String> caseIds = new ArrayList<>();
    List<Integer> commandCounts = new ArrayList<>();
    Set<String> uniqueScenarioIds = new HashSet<>();
    Set<String> uniqueCaseIds = new HashSet<>();
    int commands = 0;
    int placeCommands = 0;
    int cancelCommands = 0;

    for (JsonNode scenario : fixture.path("scenarios")) {
      String scenarioId = scenario.path("scenarioId").stringValue();
      require(uniqueScenarioIds.add(scenarioId), "duplicate M02 scenarioId: " + scenarioId);
      scenarioIds.add(scenarioId);
      int scenarioCommands = 0;
      for (JsonNode command : scenario.path("commands")) {
        String caseId = command.path("caseId").stringValue();
        require(uniqueCaseIds.add(caseId), "duplicate M02 caseId: " + caseId);
        caseIds.add(caseId);
        String type = command.path("type").stringValue();
        if ("PLACE".equals(type)) {
          placeCommands++;
        } else if ("CANCEL".equals(type)) {
          cancelCommands++;
        } else {
          throw new IllegalStateException("unknown M02 command type: " + type);
        }
        verifyExpectedGrammar(command);
        verifyBook(command.path("expected").path("bookAfter"), caseId);
        commands++;
        scenarioCommands++;
      }
      commandCounts.add(scenarioCommands);
    }

    require(FROZEN_SCENARIO_IDS.equals(scenarioIds), "M02 frozen scenario identity changed");
    require(FROZEN_CASE_IDS.equals(caseIds), "M02 frozen case identity changed");
    require(EXPECTED_COMMAND_COUNTS.equals(commandCounts), "M02 per-scenario counts changed");
    require(scenarioIds.size() == EXPECTED_SCENARIOS, "M02 frozen scenario count changed");
    require(commands == EXPECTED_COMMANDS, "M02 frozen command count changed");
    require(placeCommands == EXPECTED_PLACE_COMMANDS, "M02 frozen PLACE command count changed");
    require(cancelCommands == EXPECTED_CANCEL_COMMANDS, "M02 frozen CANCEL command count changed");
    return new CorpusFacts(scenarioIds.size(), commands, placeCommands, cancelCommands);
  }

  private static void verifyExpectedGrammar(JsonNode command) {
    String commandType = command.path("type").stringValue();
    JsonNode input = command.path("input");
    JsonNode events = command.path("expected").path("events");
    String firstType = events.get(0).path("type").stringValue();
    if ("CANCEL".equals(commandType)) {
      require(events.size() == 1, "CANCEL expectation must contain exactly one event");
      require(
          Set.of("REJECTED", "CANCEL_REJECTED", "CANCELED").contains(firstType),
          "CANCEL expectation has an invalid event grammar");
      if (!"REJECTED".equals(firstType)) {
        require(
            input
                .path("orderId")
                .bigIntegerValue()
                .equals(events.get(0).path("orderId").bigIntegerValue()),
            "CANCEL result orderId differs from its input");
      }
      return;
    }

    if ("REJECTED".equals(firstType) || "PLACE_REJECTED".equals(firstType)) {
      require(events.size() == 1, "rejected PLACE expectation must contain exactly one event");
      if ("PLACE_REJECTED".equals(firstType)) {
        require(
            input
                .path("orderId")
                .bigIntegerValue()
                .equals(events.get(0).path("orderId").bigIntegerValue()),
            "PLACE_REJECTED orderId differs from its input");
      }
      return;
    }

    require("ACCEPTED".equals(firstType), "accepted PLACE must start with ACCEPTED");
    JsonNode accepted = events.get(0);
    require(
        input.path("orderId").bigIntegerValue().equals(accepted.path("orderId").bigIntegerValue()),
        "ACCEPTED orderId differs from its input");
    require(events.size() >= 2, "accepted PLACE must trade or rest");
    BigInteger remaining = accepted.path("quantityLots").bigIntegerValue();
    boolean rested = false;
    for (int index = 1; index < events.size(); index++) {
      JsonNode event = events.get(index);
      String eventType = event.path("type").stringValue();
      if ("TRADE".equals(eventType)) {
        require(!rested, "TRADE cannot follow RESTED");
        require(
            accepted.path("sequence").equals(event.path("takerSequence")),
            "TRADE taker sequence differs from ACCEPTED");
        require(
            accepted.path("orderId").equals(event.path("takerOrderId")),
            "TRADE taker order differs from ACCEPTED");
        remaining = remaining.subtract(event.path("quantityLots").bigIntegerValue());
        require(remaining.signum() >= 0, "TRADE quantity exceeds accepted quantity");
      } else if ("RESTED".equals(eventType)) {
        require(index == events.size() - 1, "RESTED must be final");
        require(
            accepted.path("sequence").equals(event.path("sequence")),
            "RESTED sequence differs from ACCEPTED");
        require(
            accepted.path("orderId").equals(event.path("orderId")),
            "RESTED order differs from ACCEPTED");
        require(
            remaining.equals(event.path("remainingQuantityLots").bigIntegerValue()),
            "RESTED quantity violates taker conservation");
        rested = true;
      } else {
        throw new IllegalStateException("accepted PLACE has an invalid event grammar");
      }
    }
    require(
        rested ? remaining.signum() > 0 : remaining.signum() == 0,
        "accepted PLACE has an inconsistent terminal remainder");
  }

  private static void verifyBook(JsonNode book, String caseId) {
    Set<BigInteger> orderIds = new HashSet<>();
    long bestBid = verifyLevels(book.path("bids"), true, orderIds, caseId);
    long bestAsk = verifyLevels(book.path("asks"), false, orderIds, caseId);
    require(
        bestBid == 0 || bestAsk == 0 || bestBid < bestAsk,
        "M02 expectation leaves a crossed book at " + caseId);
  }

  private static long verifyLevels(
      JsonNode levels, boolean descending, Set<BigInteger> orderIds, String caseId) {
    long previousPrice = 0;
    long bestPrice = 0;
    boolean firstLevel = true;
    for (JsonNode level : levels) {
      long price = level.path("priceTicks").longValue();
      if (firstLevel) {
        bestPrice = price;
      } else {
        require(
            descending ? price < previousPrice : price > previousPrice,
            "M02 price levels are out of order at " + caseId);
      }
      long previousSequence = 0;
      for (JsonNode order : level.path("orders")) {
        long sequence = order.path("sequence").longValue();
        require(sequence > previousSequence, "M02 level is not FIFO at " + caseId);
        require(
            orderIds.add(order.path("orderId").bigIntegerValue()),
            "M02 book repeats an active orderId at " + caseId);
        previousSequence = sequence;
      }
      previousPrice = price;
      firstLevel = false;
    }
    return bestPrice;
  }

  private static int verifyScenarioBoundary(byte[] fixtureBytes, String fixtureSchema) {
    String source = new String(fixtureBytes, StandardCharsets.UTF_8);
    List<String> invalidFixtures =
        List.of(
            replaceOnce(
                source,
                "\"schemaVersion\": \"matching.m02.scenario.v1\",",
                "\"schemaVersion\": \"matching.m02.scenario.v1\", \"schemaVersion\": \"matching.m02.scenario.v1\","),
            replaceOnce(source, "\"type\": \"PLACE\",", "\"type\": \"REPLACE\","),
            replaceOnce(
                source,
                "\"input\": { \"instrumentId\": \"ETH-USDT\", \"orderId\": 0 }",
                "\"input\": { \"instrumentId\": \"ETH-USDT\", \"orderId\": 0, \"side\": \"BUY\" }"),
            replaceOnce(
                source,
                "\"input\": { \"instrumentId\": \"BTC-USDT\", \"orderId\": 500 }",
                "\"input\": { \"instrumentId\": \"BTC-USDT\" }"),
            replaceOnce(
                source,
                "\"caseId\": \"seed-resting-bid-before-invalid-cancels\",",
                "\"caseId\": \"seed-resting-bid-before-invalid-cancels\", \"unexpected\": true,"),
            replaceOnce(source, "\"type\": \"CANCELED\",", "\"type\": \"CANCELLED\","),
            replaceOnce(
                source,
                "\"events\": [\n              { \"type\": \"CANCEL_REJECTED\", \"orderId\": 500, \"code\": \"ORDER_NOT_FOUND\" }\n            ]",
                "\"events\": []"),
            replaceOnce(source, "\"quantityLots\": 4", "\"quantityLots\": 4.0"));
    for (String invalid : invalidFixtures) {
      try {
        JsonNode document = JsonSupport.parse(invalid.getBytes(StandardCharsets.UTF_8));
        JsonSupport.validate(document, fixtureSchema, false);
        requireLexicalIntegers(document);
        throw new IllegalStateException("M02 loader accepted an invalid boundary probe");
      } catch (FixtureSchemaException expected) {
        // Expected fail-closed boundary rejection.
      }
    }
    require(
        invalidFixtures.size() == EXPECTED_SCHEMA_PROBES, "M02 frozen schema probe count changed");
    return invalidFixtures.size();
  }

  private static String replaceOnce(String source, String target, String replacement) {
    int index = source.indexOf(target);
    if (index < 0) {
      throw new IllegalStateException("fixture probe target missing: " + target);
    }
    return source.substring(0, index) + replacement + source.substring(index + target.length());
  }

  private static void requireLexicalIntegers(JsonNode node) {
    if (node.isNumber() && !node.isIntegralNumber()) {
      throw new FixtureSchemaException("M02 numeric values must use integer JSON tokens");
    }
    for (JsonNode child : node) {
      requireLexicalIntegers(child);
    }
  }

  private static void clearStaleReport(Path reports) {
    try {
      Files.createDirectories(reports);
      Files.deleteIfExists(reports.resolve("check.json"));
    } catch (IOException exception) {
      throw new IllegalStateException("cannot clear stale M02 report", exception);
    }
  }

  private static byte[] readBytes(Path path) {
    try {
      return Files.readAllBytes(path);
    } catch (IOException exception) {
      throw new IllegalStateException("cannot read " + path, exception);
    }
  }

  private static String readString(Path path) {
    return new String(readBytes(path), StandardCharsets.UTF_8);
  }

  private static void require(boolean condition, String message) {
    if (!condition) {
      throw new IllegalStateException(message);
    }
  }

  public record Result(String status, Path reportPath) {}

  private record CorpusFacts(int scenarios, int commands, int placeCommands, int cancelCommands) {}
}
