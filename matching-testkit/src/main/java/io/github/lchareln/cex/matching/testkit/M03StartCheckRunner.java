package io.github.lchareln.cex.matching.testkit;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Validates the frozen M03 generator contract and writes the intentional RED report. */
public final class M03StartCheckRunner {
  public static final String STATUS = "GOAL_NOT_IMPLEMENTED";
  public static final String FROZEN_GENERATOR_SHA256 =
      "3e051347b9bd42aac431d02949c0c1b72daa667d10a03cc8aeb09a6b5a74d24e";

  static final String GENERATOR_PATH =
      "matching-testkit/src/test/resources/m03/fixtures/property-suite-v1.json";
  static final String GENERATOR_SCHEMA_PATH = "schemas/matching.m03.generator.v1.schema.json";
  static final String CHECK_SCHEMA_PATH = "schemas/matching.m03.check.v1.schema.json";
  static final int HISTORIES = 256;
  static final int COMMANDS_PER_HISTORY = 64;
  static final int TOTAL_COMMANDS = HISTORIES * COMMANDS_PER_HISTORY;
  static final int LANES = 4;
  static final int HISTORIES_PER_LANE = HISTORIES / LANES;
  static final int SCHEMA_PROBES = 6;
  private static final List<String> LANE_IDS =
      List.of("BEST_PRICE", "SAME_PRICE_FIFO", "MAKER_PRICE", "CANCELED_IDENTITY");
  private static final List<Integer> PREFIX_SIZES = List.of(3, 3, 2, 3);

  public Result run(Path repositoryRoot, Path reportDirectory) {
    return run(repositoryRoot, reportDirectory, repositoryRoot);
  }

  Result run(Path repositoryRoot, Path reportDirectory, Path trustedOutputRoot) {
    Path root = repositoryRoot.toAbsolutePath().normalize();
    Path reports = SafeOutputPaths.resolveTrustedOutput(trustedOutputRoot, reportDirectory);
    clearStaleReport(reports);

    byte[] generatorBytes = readBytes(root.resolve(GENERATOR_PATH));
    String generatorSha256 = Hashing.sha256Hex(generatorBytes);
    require(
        FROZEN_GENERATOR_SHA256.equals(generatorSha256),
        "M03 frozen generator contract SHA-256 changed");
    String generatorSchema = readString(root.resolve(GENERATOR_SCHEMA_PATH));
    JsonNode generator = JsonSupport.parse(generatorBytes);
    JsonSupport.validate(generator, generatorSchema, false);
    verifyFrozenGenerator(generator);
    int schemaProbes = verifySchemaBoundary(generator, generatorSchema);
    JsonNode inheritedM02 = runInheritedM02(root, reports);
    IndependenceBoundary independence = verifyIndependenceBoundary(root);

    ObjectNode report = JsonSupport.MAPPER.createObjectNode();
    report.put("schemaVersion", "matching.m03.check.v1");
    report.put("unit", "M03");
    report.put("status", STATUS);
    report.put("contractPlanVersion", "0.5");
    report.put(
        "objective",
        "Prove the M02 matcher against an independent generated reference model with persisted minimal counterexamples.");
    ObjectNode inherited = report.putObject("inheritedM02");
    inherited.put("status", "PASS");
    inherited.put("checkSchemaVersion", "matching.m02.check.v2");
    inherited.put("scenarios", 10);
    inherited.put("commands", 34);
    inherited.put("canonicalDigest", inheritedM02.path("canonical").path("digest").stringValue());
    ObjectNode generatorReport = report.putObject("generator");
    generatorReport.put("path", GENERATOR_PATH);
    generatorReport.put("schemaPath", GENERATOR_SCHEMA_PATH);
    generatorReport.put("sha256", generatorSha256);
    generatorReport.put("algorithm", "splitmix64-v1");
    generatorReport.put("baseSeed", "6824");
    generatorReport.put("histories", HISTORIES);
    generatorReport.put("commandsPerHistory", COMMANDS_PER_HISTORY);
    generatorReport.put("totalCommands", TOTAL_COMMANDS);
    generatorReport.put("lanes", LANES);
    generatorReport.put("historiesPerLane", HISTORIES_PER_LANE);
    generatorReport.put("schemaProbes", schemaProbes);
    ObjectNode independenceReport = report.putObject("independenceBoundary");
    independenceReport.put("module", "matching-reference");
    independenceReport.put("coreDependency", independence.coreDependency());
    independenceReport.put("testkitDependency", independence.testkitDependency());
    independenceReport.put("semanticSources", independence.semanticSources());
    independenceReport.put("representation", "REFERENCE_OWNED");
    ArrayNode missing = report.putArray("missingCapabilities");
    missing.add("INDEPENDENT_REFERENCE_MODEL");
    missing.add("DETERMINISTIC_HISTORY_GENERATOR");
    missing.add("STATEFUL_DIFFERENTIAL_PROPERTY_JUDGE");
    missing.add("DETERMINISTIC_COUNTEREXAMPLE_SHRINKER");
    missing.add("REPLAYABLE_COUNTEREXAMPLE_ARTIFACT");

    JsonSupport.validate(report, readString(root.resolve(CHECK_SCHEMA_PATH)), false);
    Path reportPath = reports.resolve("check.json");
    AtomicFiles.write(reportPath, JsonSupport.prettyBytes(report));
    return new Result(STATUS, reportPath);
  }

  private static JsonNode runInheritedM02(Path root, Path reports) {
    Path inheritedDirectory = reports.resolve(".inherited-m02");
    try {
      M02CheckRunner.Result result = new M02CheckRunner().run(root, inheritedDirectory, reports);
      require(M02CheckRunner.PASS.equals(result.status()), "inherited M02 check is not PASS");
      JsonNode report = JsonSupport.parse(readBytes(result.reportPath()));
      JsonSupport.validate(
          report, readString(root.resolve("schemas/matching.m02.check.v2.schema.json")), false);
      require(
          "matching.m02.check.v2".equals(report.path("schemaVersion").stringValue()),
          "inherited M02 check schema changed");
      require("PASS".equals(report.path("status").stringValue()), "inherited M02 status changed");
      require(
          report.path("scenarioCorpus").path("scenarios").intValue() == 10,
          "inherited M02 scenario count changed");
      require(
          report.path("scenarioCorpus").path("commands").intValue() == 34,
          "inherited M02 command count changed");
      require(
          "sha256:32054d63accba99b19db823c41f74bda73dc3b8a009b528f2834d2bc70839d16"
              .equals(report.path("canonical").path("digest").stringValue()),
          "inherited M02 canonical digest changed");
      return report.deepCopy();
    } finally {
      deleteTree(inheritedDirectory);
    }
  }

  private static IndependenceBoundary verifyIndependenceBoundary(Path root) {
    Path buildFile = root.resolve("matching-reference/build.gradle.kts");
    require(Files.isRegularFile(buildFile), "matching-reference build file is missing");
    String build = readString(buildFile);
    boolean coreDependency = build.contains("project(\":matching-core\")");
    boolean testkitDependency = build.contains("project(\":matching-testkit\")");
    require(!coreDependency, "matching-reference depends on matching-core");
    require(!testkitDependency, "matching-reference depends on matching-testkit");
    require(
        readString(root.resolve("settings.gradle.kts")).contains("include(\"matching-reference\")"),
        "matching-reference is absent from Gradle settings");

    Path sources = root.resolve("matching-reference/src/main/java");
    int semanticSources = 0;
    if (Files.exists(sources)) {
      try (var paths = Files.walk(sources)) {
        semanticSources =
            Math.toIntExact(paths.filter(path -> path.toString().endsWith(".java")).count());
      } catch (IOException exception) {
        throw new IllegalStateException("cannot inspect matching-reference sources", exception);
      }
    }
    require(semanticSources == 0, "M03 start must not contain reference semantics");
    return new IndependenceBoundary(coreDependency, testkitDependency, semanticSources);
  }

  private static void verifyFrozenGenerator(JsonNode generator) {
    require(
        "matching.m03.generator.v1".equals(generator.path("schemaVersion").stringValue()),
        "M03 generator schemaVersion changed");
    require(
        "splitmix64-v1".equals(generator.path("algorithm").stringValue()),
        "M03 generator algorithm changed");
    require("6824".equals(generator.path("baseSeed").stringValue()), "M03 base seed changed");
    require(generator.path("histories").intValue() == HISTORIES, "M03 history count changed");
    require(
        generator.path("commandsPerHistory").intValue() == COMMANDS_PER_HISTORY,
        "M03 command count changed");

    List<String> laneIds = new ArrayList<>();
    List<Integer> laneModulos = new ArrayList<>();
    List<Integer> prefixSizes = new ArrayList<>();
    for (JsonNode lane : generator.path("lanes")) {
      laneIds.add(lane.path("id").stringValue());
      laneModulos.add(lane.path("historyModulo").intValue());
      prefixSizes.add(lane.path("prefix").size());
    }
    require(LANE_IDS.equals(laneIds), "M03 lane identity or order changed");
    require(List.of(0, 1, 2, 3).equals(laneModulos), "M03 lane modulo changed");
    require(PREFIX_SIZES.equals(prefixSizes), "M03 lane prefix size changed");

    JsonNode domain = generator.path("randomDomain");
    require(domain.path("placeWeight").intValue() == 65, "M03 PLACE weight changed");
    require(domain.path("cancelWeight").intValue() == 35, "M03 CANCEL weight changed");
    require(domain.path("invalidOneIn").intValue() == 32, "M03 invalid ratio changed");
    require(
        "BTC-USDT".equals(domain.path("validInstrumentId").stringValue()),
        "M03 valid instrument changed");
    require(
        "ETH-USDT".equals(domain.path("invalidInstrumentId").stringValue()),
        "M03 invalid instrument changed");
    require(domain.path("minimumOrderId").intValue() == 1, "M03 minimum order ID changed");
    require(domain.path("maximumOrderId").intValue() == 32, "M03 maximum order ID changed");
    require(domain.path("minimumPriceTicks").intValue() == 98, "M03 minimum price changed");
    require(domain.path("maximumPriceTicks").intValue() == 102, "M03 maximum price changed");
    require(domain.path("minimumQuantityLots").intValue() == 1, "M03 minimum quantity changed");
    require(domain.path("maximumQuantityLots").intValue() == 5, "M03 maximum quantity changed");
  }

  private static int verifySchemaBoundary(JsonNode valid, String schema) {
    List<JsonNode> invalid = new ArrayList<>();

    ObjectNode missingLanes = (ObjectNode) valid.deepCopy();
    missingLanes.remove("lanes");
    invalid.add(missingLanes);

    ObjectNode extraRoot = (ObjectNode) valid.deepCopy();
    extraRoot.put("clock", "forbidden");
    invalid.add(extraRoot);

    ObjectNode wrongAlgorithm = (ObjectNode) valid.deepCopy();
    wrongAlgorithm.put("algorithm", "java-random");
    invalid.add(wrongAlgorithm);

    ObjectNode tooManyLanes = (ObjectNode) valid.deepCopy();
    ((ArrayNode) tooManyLanes.path("lanes")).add(valid.path("lanes").get(0).deepCopy());
    invalid.add(tooManyLanes);

    ObjectNode badCancel = (ObjectNode) valid.deepCopy();
    ((ObjectNode) badCancel.path("lanes").get(3).path("prefix").get(1).path("input"))
        .put("side", "BUY");
    invalid.add(badCancel);

    ObjectNode badSideSet = (ObjectNode) valid.deepCopy();
    ((ArrayNode) badSideSet.path("randomDomain").path("validSides")).remove(1);
    invalid.add(badSideSet);

    int rejected = 0;
    for (JsonNode probe : invalid) {
      try {
        JsonSupport.validate(probe, schema, false);
      } catch (FixtureSchemaException expected) {
        rejected++;
      }
    }
    require(rejected == SCHEMA_PROBES, "M03 generator schema accepted a negative probe");
    return rejected;
  }

  private static void clearStaleReport(Path reports) {
    try {
      Files.createDirectories(reports);
      Files.deleteIfExists(reports.resolve("check.json"));
    } catch (IOException exception) {
      throw new IllegalStateException("cannot clear stale M03 start report", exception);
    }
  }

  private static void deleteTree(Path root) {
    if (!Files.exists(root)) {
      return;
    }
    try (var paths = Files.walk(root)) {
      paths.sorted(Comparator.reverseOrder()).forEach(M03StartCheckRunner::deletePath);
    } catch (IOException exception) {
      throw new IllegalStateException("cannot clear inherited M02 report", exception);
    }
  }

  private static void deletePath(Path path) {
    try {
      Files.deleteIfExists(path);
    } catch (IOException exception) {
      throw new IllegalStateException("cannot delete inherited M02 path " + path, exception);
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

  private record IndependenceBoundary(
      boolean coreDependency, boolean testkitDependency, int semanticSources) {}
}
