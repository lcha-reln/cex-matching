package io.github.lchareln.cex.matching.testkit;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Validates the frozen M01 oracle and writes the intentional start-state gap report. */
public final class M01StartCheckRunner {
  public static final String STATUS = "GOAL_NOT_IMPLEMENTED";
  public static final String FROZEN_FIXTURE_SHA256 =
      "d050bc2fc029e3ac0afb5047e3030412412f3a7aecf0938a19a5953618ff9ed7";

  private static final List<String> FROZEN_SCENARIO_IDS =
      List.of(
          "invalid-does-not-consume-sequence",
          "empty-and-noncrossing-rest",
          "exact-touch-maker-price",
          "better-price-before-time",
          "same-price-fifo-three-makers",
          "maker-partially-filled",
          "taker-sweeps-three-levels-and-rests",
          "sell-side-mirror");
  private static final List<String> FROZEN_CASE_IDS =
      List.of(
          "reject-zero-price",
          "first-valid-still-sequence-one",
          "empty-buy-rests",
          "noncrossing-sell-rests",
          "resting-sell",
          "touching-buy-fills",
          "earlier-worse-ask",
          "later-better-ask",
          "buy-takes-better-price-first",
          "fifo-maker-one",
          "fifo-maker-two",
          "fifo-maker-three",
          "fifo-taker",
          "large-resting-maker",
          "small-buy-partial-maker",
          "ask-level-one",
          "ask-level-two",
          "ask-level-three",
          "sweep-and-rest-buy",
          "resting-bid-low",
          "resting-bid-high",
          "sell-takes-high-bid-first-and-rests");

  private static final String FIXTURE_PATH =
      "matching-testkit/src/test/resources/m01/fixtures/price-time-v1.json";
  private static final String FIXTURE_SCHEMA_PATH = "schemas/matching.m01.scenario.v1.schema.json";
  private static final String CHECK_SCHEMA_PATH = "schemas/matching.m01.check.v1.schema.json";

  public Result run(Path repositoryRoot, Path reportDirectory) {
    Path root = repositoryRoot.toAbsolutePath().normalize();
    Path reports = SafeOutputPaths.resolveTrustedOutput(root, reportDirectory);
    clearStaleReport(reports);

    byte[] fixtureBytes = readBytes(root.resolve(FIXTURE_PATH));
    String fixtureSha256 = Hashing.sha256Hex(fixtureBytes);
    if (!FROZEN_FIXTURE_SHA256.equals(fixtureSha256)) {
      throw new IllegalStateException("M01 frozen scenario corpus SHA-256 changed");
    }
    JsonNode fixture = JsonSupport.parse(fixtureBytes);
    JsonSupport.validate(fixture, readString(root.resolve(FIXTURE_SCHEMA_PATH)), false);
    int scenarios = fixture.path("scenarios").size();
    if (scenarios != 8) {
      throw new IllegalStateException("M01 frozen scenario count changed: " + scenarios);
    }
    List<String> scenarioIds = new ArrayList<>();
    List<String> caseIds = new ArrayList<>();
    for (JsonNode scenario : fixture.path("scenarios")) {
      scenarioIds.add(scenario.path("scenarioId").stringValue());
      for (JsonNode command : scenario.path("commands")) {
        caseIds.add(command.path("caseId").stringValue());
      }
    }
    if (!FROZEN_SCENARIO_IDS.equals(scenarioIds) || !FROZEN_CASE_IDS.equals(caseIds)) {
      throw new IllegalStateException("M01 frozen scenario or case identity changed");
    }

    ObjectNode report = JsonSupport.MAPPER.createObjectNode();
    report.put("schemaVersion", "matching.m01.check.v1");
    report.put("unit", "M01");
    report.put("status", STATUS);
    report.put("contractPlanVersion", "0.3");
    report.put("objective", "Implement deterministic single-instrument GTC price-time matching.");
    ObjectNode corpus = report.putObject("scenarioCorpus");
    corpus.put("path", FIXTURE_PATH);
    corpus.put("schemaPath", FIXTURE_SCHEMA_PATH);
    corpus.put("sha256", fixtureSha256);
    corpus.put("scenarios", scenarios);
    ArrayNode missing = report.putArray("missingCapabilities");
    missing.add("ORDER_BOOK_STATE");
    missing.add("PRICE_TIME_MATCHING");
    missing.add("ORDERED_EVENT_BATCH");

    JsonSupport.validate(report, readString(root.resolve(CHECK_SCHEMA_PATH)), false);
    Path reportPath = reports.resolve("check.json");
    AtomicFiles.write(reportPath, JsonSupport.prettyBytes(report));
    return new Result(STATUS, reportPath);
  }

  private static void clearStaleReport(Path reports) {
    try {
      Files.createDirectories(reports);
      Files.deleteIfExists(reports.resolve("check.json"));
    } catch (IOException exception) {
      throw new IllegalStateException("cannot clear stale M01 report", exception);
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

  public record Result(String status, Path reportPath) {}
}
