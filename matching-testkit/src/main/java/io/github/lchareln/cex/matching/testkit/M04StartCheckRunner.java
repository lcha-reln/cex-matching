package io.github.lchareln.cex.matching.testkit;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Validates the frozen M04 execution-policy inputs and writes the intentional RED report. */
public final class M04StartCheckRunner {
  public static final String STATUS = "GOAL_NOT_IMPLEMENTED";
  public static final String FIXED_CORPUS_SHA256 =
      "a8bf834828847a24d316bf6f760d008809901d8e3e2ff132276225b0aa79f596";
  public static final String GENERATOR_SHA256 =
      "33a24417d56b565fe9b25868e70c1faa1637a7997d92486c5d6f30113e00575d";

  static final String FIXED_CORPUS_PATH =
      "matching-testkit/src/test/resources/m04/fixtures/execution-policy-v1.json";
  static final String FIXED_CORPUS_SCHEMA_PATH = "schemas/matching.m04.scenario.v1.schema.json";
  static final String GENERATOR_PATH =
      "matching-testkit/src/test/resources/m04/fixtures/property-suite-v1.json";
  static final String GENERATOR_SCHEMA_PATH = "schemas/matching.m04.generator.v1.schema.json";
  static final String CHECK_SCHEMA_PATH = "schemas/matching.m04.check.v1.schema.json";

  static final int SCENARIOS = 14;
  static final int FIXED_COMMANDS = 48;
  static final int PLACE_COMMANDS = 44;
  static final int CANCEL_COMMANDS = 4;
  static final int HISTORIES = 192;
  static final int COMMANDS_PER_HISTORY = 64;
  static final int TOTAL_GENERATED_COMMANDS = HISTORIES * COMMANDS_PER_HISTORY;
  static final int LANES = 6;
  static final int HISTORIES_PER_LANE = HISTORIES / LANES;
  static final int FIXED_SCHEMA_PROBES = 6;
  static final int GENERATOR_SCHEMA_PROBES = 6;

  static final List<String> SCENARIO_IDS =
      List.of(
          "legacy-gtc-and-cancel",
          "unknown-policy-priority",
          "ioc-zero-fill",
          "ioc-partial-fill",
          "ioc-full-multi-level",
          "ioc-price-protection",
          "fok-insufficient-atomic",
          "fok-exact-multi-level",
          "fok-requires-all-levels",
          "fok-limit-price",
          "post-only-empty-and-noncrossing",
          "post-only-touch-and-cross",
          "policy-rejection-sequence",
          "sell-side-symmetry");

  static final List<String> LANE_IDS =
      List.of(
          "LEGACY_GTC",
          "IOC_ZERO_PARTIAL_FULL",
          "FOK_INSUFFICIENT_EXACT_MULTI_LEVEL",
          "POST_ONLY_EMPTY_NON_CROSS_TOUCH_CROSS",
          "IDENTITY_AND_SEQUENCE_AFTER_REJECTION",
          "MIXED_POLICY_CANCEL_DUPLICATE");

  static final List<String> REQUIRED_MUTANTS =
      List.of(
          "M04-IOC-REMAINDER-RESTS",
          "M04-IOC-BEHAVES-LIKE-FOK",
          "M04-FOK-PARTIAL-STATE-LEAK",
          "M04-FOK-BEST-LEVEL-ONLY",
          "M04-FOK-IGNORES-LIMIT-PRICE",
          "M04-POST-ONLY-TOUCH-ACCEPTED",
          "M04-POLICY-REJECT-CONSUMES-IDENTITY",
          "M04-UNKNOWN-POLICY-DEFAULTS-GTC");

  public Result run(Path repositoryRoot, Path reportDirectory) {
    return run(repositoryRoot, reportDirectory, repositoryRoot);
  }

  Result run(Path repositoryRoot, Path reportDirectory, Path trustedOutputRoot) {
    Path root = repositoryRoot.toAbsolutePath().normalize();
    Path reports = SafeOutputPaths.resolveTrustedOutput(trustedOutputRoot, reportDirectory);
    clearStaleReport(reports);

    FrozenInput fixed = verifyFixedCorpus(root);
    FrozenInput generated = verifyGenerator(root);
    JsonNode inheritedM03 = runInheritedM03(root, reports);

    ObjectNode report = JsonSupport.MAPPER.createObjectNode();
    report.put("schemaVersion", "matching.m04.check.v1");
    report.put("unit", "M04");
    report.put("status", STATUS);
    report.put("contractPlanVersion", "0.6");
    report.put(
        "objective",
        "Add one closed execution-policy axis to the proven limit matcher without changing the frozen five-field PlaceLimitOrderInput.");
    writeInheritedM03(report.putObject("inheritedM03"), inheritedM03);
    writeFixedCorpus(report.putObject("fixedCorpus"), fixed);
    writeGenerator(report.putObject("generator"), generated);
    writeExecutionContract(report.putObject("executionContract"));
    writeStrings(report.putArray("requiredMutants"), REQUIRED_MUTANTS);
    writeStrings(
        report.putArray("missingCapabilities"),
        List.of(
            "PLACE_LIMIT_ORDER_REQUEST",
            "EXECUTION_POLICY",
            "IOC_REMAINDER_CANCELLATION",
            "FOK_ATOMIC_PREFLIGHT",
            "POST_ONLY_ADMISSION",
            "M04_REFERENCE_MODEL",
            "M04_GENERATED_PROPERTY_JUDGE",
            "M04_REPLAYABLE_COUNTEREXAMPLES"));
    ObjectNode release = report.putObject("releaseTarget");
    release.put("unitTag", "course/m04-complete");
    release.putNull("productRelease");
    release.put("verification", "M04_EVIDENCE_ONLY");

    JsonSupport.validate(report, readString(root.resolve(CHECK_SCHEMA_PATH)), false);
    Path reportPath = reports.resolve("check.json");
    AtomicFiles.write(reportPath, JsonSupport.prettyBytes(report));
    return new Result(STATUS, reportPath);
  }

  private static FrozenInput verifyFixedCorpus(Path root) {
    byte[] bytes = readBytes(root.resolve(FIXED_CORPUS_PATH));
    String digest = Hashing.sha256Hex(bytes);
    require(FIXED_CORPUS_SHA256.equals(digest), "M04 fixed corpus SHA-256 changed");
    String schema = readString(root.resolve(FIXED_CORPUS_SCHEMA_PATH));
    JsonNode fixture = JsonSupport.parse(bytes);
    JsonSupport.validate(fixture, schema, false);

    List<String> scenarioIds = new ArrayList<>();
    Set<String> caseIds = new LinkedHashSet<>();
    Map<String, Integer> policies = new LinkedHashMap<>();
    policies.put("GTC", 0);
    policies.put("IOC", 0);
    policies.put("FOK", 0);
    policies.put("POST_ONLY", 0);
    policies.put("UNKNOWN", 0);
    int commands = 0;
    int places = 0;
    int cancels = 0;
    for (JsonNode scenario : fixture.path("scenarios")) {
      scenarioIds.add(scenario.path("scenarioId").stringValue());
      for (JsonNode command : scenario.path("commands")) {
        commands++;
        require(caseIds.add(command.path("caseId").stringValue()), "duplicate M04 caseId");
        if ("PLACE".equals(command.path("type").stringValue())) {
          places++;
          String policy = command.path("input").path("executionPolicy").stringValue();
          require(policies.containsKey(policy), "unexpected M04 fixed policy: " + policy);
          policies.put(policy, policies.get(policy) + 1);
        } else {
          cancels++;
        }
      }
    }
    require(SCENARIO_IDS.equals(scenarioIds), "M04 scenario identity or order changed");
    require(commands == FIXED_COMMANDS, "M04 fixed command count changed");
    require(places == PLACE_COMMANDS, "M04 fixed PLACE count changed");
    require(cancels == CANCEL_COMMANDS, "M04 fixed CANCEL count changed");
    require(
        policies.equals(Map.of("GTC", 25, "IOC", 5, "FOK", 6, "POST_ONLY", 6, "UNKNOWN", 2)),
        "M04 fixed policy counts changed");
    int probes = verifyFixedSchemaBoundary(fixture, schema);
    return new FrozenInput(digest, probes, policies);
  }

  private static FrozenInput verifyGenerator(Path root) {
    byte[] bytes = readBytes(root.resolve(GENERATOR_PATH));
    String digest = Hashing.sha256Hex(bytes);
    require(GENERATOR_SHA256.equals(digest), "M04 generator profile SHA-256 changed");
    String schema = readString(root.resolve(GENERATOR_SCHEMA_PATH));
    JsonNode profile = JsonSupport.parse(bytes);
    JsonSupport.validate(profile, schema, false);
    require(
        "matching.m04.generator.v1".equals(profile.path("schemaVersion").stringValue()),
        "M04 generator schemaVersion changed");
    require(
        "splitmix64-v1".equals(profile.path("algorithm").stringValue()),
        "M04 generator algorithm changed");
    require("4404".equals(profile.path("baseSeed").stringValue()), "M04 base seed changed");
    require(profile.path("histories").intValue() == HISTORIES, "M04 history count changed");
    require(
        profile.path("commandsPerHistory").intValue() == COMMANDS_PER_HISTORY,
        "M04 commands-per-history changed");

    List<String> laneIds = new ArrayList<>();
    List<Integer> modulos = new ArrayList<>();
    List<Integer> prefixSizes = new ArrayList<>();
    for (JsonNode lane : profile.path("lanes")) {
      laneIds.add(lane.path("id").stringValue());
      modulos.add(lane.path("historyModulo").intValue());
      prefixSizes.add(lane.path("prefix").size());
    }
    require(LANE_IDS.equals(laneIds), "M04 generator lane identity or order changed");
    require(List.of(0, 1, 2, 3, 4, 5).equals(modulos), "M04 generator lane modulo changed");
    require(List.of(3, 5, 6, 5, 5, 6).equals(prefixSizes), "M04 lane prefix size changed");
    require(
        REQUIRED_MUTANTS.equals(strings(profile.path("requiredMutants"))),
        "M04 required mutant identity or order changed");

    JsonNode domain = profile.path("randomDomain");
    require(domain.path("placeWeight").intValue() == 70, "M04 PLACE weight changed");
    require(domain.path("cancelWeight").intValue() == 30, "M04 CANCEL weight changed");
    require(domain.path("invalidOneIn").intValue() == 32, "M04 invalid ratio changed");
    require(domain.path("unknownPolicyOneIn").intValue() == 16, "M04 unknown-policy ratio changed");
    List<String> policyDomain = new ArrayList<>();
    domain
        .path("executionPolicies")
        .forEach(policy -> policyDomain.add(policy.path("id").stringValue()));
    require(
        List.of("GTC", "IOC", "FOK", "POST_ONLY").equals(policyDomain),
        "M04 execution-policy domain changed");
    require(
        profile.path("coverageRequirements").path("rejectionIdentityAndSequence").booleanValue(),
        "M04 rejection continuity coverage changed");
    require(
        profile.path("coverageRequirements").path("buyAndSell").booleanValue(),
        "M04 side symmetry coverage changed");
    int probes = verifyGeneratorSchemaBoundary(profile, schema);
    return new FrozenInput(digest, probes, Map.of());
  }

  private static int verifyFixedSchemaBoundary(JsonNode valid, String schema) {
    List<JsonNode> invalid = new ArrayList<>();
    ObjectNode missingScenarios = (ObjectNode) valid.deepCopy();
    missingScenarios.remove("scenarios");
    invalid.add(missingScenarios);
    ObjectNode extraRoot = (ObjectNode) valid.deepCopy();
    extraRoot.put("clock", "forbidden");
    invalid.add(extraRoot);
    ObjectNode extraScenario = (ObjectNode) valid.deepCopy();
    ((ObjectNode) extraScenario.path("scenarios").get(0)).put("expected", "forbidden");
    invalid.add(extraScenario);
    ObjectNode missingPolicy = (ObjectNode) valid.deepCopy();
    ((ObjectNode) missingPolicy.path("scenarios").get(1).path("commands").get(1).path("input"))
        .remove("executionPolicy");
    invalid.add(missingPolicy);
    ObjectNode policyOnCancel = (ObjectNode) valid.deepCopy();
    ((ObjectNode) policyOnCancel.path("scenarios").get(0).path("commands").get(1).path("input"))
        .put("executionPolicy", "GTC");
    invalid.add(policyOnCancel);
    ObjectNode floatingQuantity = (ObjectNode) valid.deepCopy();
    ((ObjectNode) floatingQuantity.path("scenarios").get(2).path("commands").get(0).path("input"))
        .put("quantityLots", 1.5);
    invalid.add(floatingQuantity);
    return rejectedBySchema(invalid, schema, FIXED_SCHEMA_PROBES, "fixed corpus");
  }

  private static int verifyGeneratorSchemaBoundary(JsonNode valid, String schema) {
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
    ObjectNode duplicateLane = (ObjectNode) valid.deepCopy();
    ((ArrayNode) duplicateLane.path("lanes")).add(valid.path("lanes").get(0).deepCopy());
    invalid.add(duplicateLane);
    ObjectNode missingPolicy = (ObjectNode) valid.deepCopy();
    ((ObjectNode) missingPolicy.path("lanes").get(1).path("prefix").get(0).path("input"))
        .remove("executionPolicy");
    invalid.add(missingPolicy);
    ObjectNode reorderedMutant = (ObjectNode) valid.deepCopy();
    ArrayNode mutants = (ArrayNode) reorderedMutant.path("requiredMutants");
    JsonNode first = mutants.get(0).deepCopy();
    mutants.set(0, mutants.get(1).deepCopy());
    mutants.set(1, first);
    invalid.add(reorderedMutant);
    return rejectedBySchema(invalid, schema, GENERATOR_SCHEMA_PROBES, "generator");
  }

  private static int rejectedBySchema(
      List<JsonNode> invalid, String schema, int expected, String contract) {
    int rejected = 0;
    for (JsonNode probe : invalid) {
      try {
        JsonSupport.validate(probe, schema, false);
      } catch (FixtureSchemaException exception) {
        rejected++;
      }
    }
    require(rejected == expected, "M04 " + contract + " schema accepted a negative probe");
    return rejected;
  }

  private static JsonNode runInheritedM03(Path root, Path reports) {
    Path inheritedDirectory = reports.resolve(".inherited-m03");
    try {
      M03CheckRunner.Result result = new M03CheckRunner().run(root, inheritedDirectory, reports);
      require(M03CheckRunner.PASS.equals(result.status()), "inherited M03 check is not PASS");
      JsonNode report = JsonSupport.parse(readBytes(result.reportPath()));
      JsonSupport.validate(
          report, readString(root.resolve("schemas/matching.m03.check.v2.schema.json")), false);
      require(
          "matching.m03.check.v2".equals(report.path("schemaVersion").stringValue()),
          "inherited M03 check schema changed");
      require("PASS".equals(report.path("status").stringValue()), "inherited M03 status changed");
      require(
          report.path("properties").path("commands").intValue() == 16_384,
          "inherited M03 command count changed");
      require(
          "sha256:1920d6b8a480998825c72636d446854d9e795e91b0ab29520f203b12186979ce"
              .equals(report.path("determinism").path("commandDigest").stringValue()),
          "inherited M03 command digest changed");
      require(
          report.path("counterexamples").path("required").intValue() == 6,
          "inherited M03 counterexample count changed");
      require(
          report.path("mutants").path("killed").intValue() == 6,
          "inherited M03 mutant count changed");
      return report.deepCopy();
    } finally {
      deleteTree(inheritedDirectory);
    }
  }

  private static void writeInheritedM03(ObjectNode target, JsonNode inherited) {
    target.put("status", "PASS");
    target.put("checkSchemaVersion", "matching.m03.check.v2");
    target.put("histories", inherited.path("properties").path("histories").intValue());
    target.put("commands", inherited.path("properties").path("commands").intValue());
    target.put("commandDigest", inherited.path("determinism").path("commandDigest").stringValue());
    target.put("counterexamples", inherited.path("counterexamples").path("required").intValue());
    target.put("mutantsKilled", inherited.path("mutants").path("killed").intValue());
    target.put("productRelease", "matching-0.1.0");
  }

  private static void writeFixedCorpus(ObjectNode target, FrozenInput fixed) {
    target.put("path", FIXED_CORPUS_PATH);
    target.put("schemaPath", FIXED_CORPUS_SCHEMA_PATH);
    target.put("sha256", fixed.sha256());
    target.put("scenarios", SCENARIOS);
    target.put("commands", FIXED_COMMANDS);
    target.put("placeCommands", PLACE_COMMANDS);
    target.put("cancelCommands", CANCEL_COMMANDS);
    ObjectNode policies = target.putObject("policyCounts");
    policies.put("GTC", fixed.policyCounts().get("GTC"));
    policies.put("IOC", fixed.policyCounts().get("IOC"));
    policies.put("FOK", fixed.policyCounts().get("FOK"));
    policies.put("POST_ONLY", fixed.policyCounts().get("POST_ONLY"));
    policies.put("UNKNOWN", fixed.policyCounts().get("UNKNOWN"));
    target.put("schemaProbes", fixed.schemaProbes());
  }

  private static void writeGenerator(ObjectNode target, FrozenInput generated) {
    target.put("path", GENERATOR_PATH);
    target.put("schemaPath", GENERATOR_SCHEMA_PATH);
    target.put("sha256", generated.sha256());
    target.put("algorithm", "splitmix64-v1");
    target.put("baseSeed", "4404");
    target.put("histories", HISTORIES);
    target.put("commandsPerHistory", COMMANDS_PER_HISTORY);
    target.put("totalCommands", TOTAL_GENERATED_COMMANDS);
    target.put("lanes", LANES);
    target.put("historiesPerLane", HISTORIES_PER_LANE);
    writeStrings(target.putArray("laneIds"), LANE_IDS);
    target.put("schemaProbes", generated.schemaProbes());
  }

  private static void writeExecutionContract(ObjectNode target) {
    target.put("requestType", "PlaceLimitOrderRequest");
    target.put("policyType", "ExecutionPolicy");
    target.put("legacyMethod", "place(PlaceLimitOrderInput)");
    target.put("requestMethod", "placeRequest(PlaceLimitOrderRequest)");
    writeStrings(
        target.putArray("requestComponents"),
        List.of("orderInput:PlaceLimitOrderInput", "executionPolicy:String"));
    target.put("legacyPolicy", "GTC");
    writeStrings(target.putArray("policies"), List.of("GTC", "IOC", "FOK", "POST_ONLY"));
    writeStrings(
        target.putArray("legacyInputFields"),
        List.of("instrumentId", "orderId", "side", "priceTicks", "quantityLots"));
    writeStrings(
        target.putArray("precedence"),
        List.of(
            "M00_FIELD_VALIDATION",
            "EXECUTION_POLICY_VALIDATION",
            "DUPLICATE_ORDER_ID",
            "POLICY_STATE_PRECHECK",
            "ACCEPTANCE_SEQUENCE_CAPACITY",
            "ACCEPT_AND_EXECUTE"));
    target.put("unknownPolicyEvent", "Rejected");
    target.put("unknownPolicyCode", "INVALID_EXECUTION_POLICY");
    target.put("unknownPolicyField", "executionPolicy");
    target.put("placeRejectionEvent", "PlaceRejected");
    writeStrings(
        target.putArray("placeRejectionCodes"),
        List.of("DUPLICATE_ORDER_ID", "FOK_NOT_FILLABLE", "POST_ONLY_WOULD_TAKE"));
    target.put("acceptedPolicyField", "executionPolicy:ExecutionPolicy");
    target.put("remainderEvent", "RemainderCanceled");
    target.put("remainderReason", "IOC_REMAINDER");
    target.put("protectedLimitField", "priceTicks");
    target.put("fokAtomic", true);
    target.put("postOnlyRejectsTouch", true);
    target.put("policyRejectionsReserveIdentity", false);
    target.put("policyRejectionsConsumeSequence", false);
  }

  private static void writeStrings(ArrayNode target, List<String> values) {
    values.forEach(target::add);
  }

  private static List<String> strings(JsonNode values) {
    List<String> result = new ArrayList<>();
    values.forEach(value -> result.add(value.stringValue()));
    return List.copyOf(result);
  }

  private static void clearStaleReport(Path reports) {
    try {
      Files.createDirectories(reports);
      Files.deleteIfExists(reports.resolve("check.json"));
    } catch (IOException exception) {
      throw new IllegalStateException("cannot clear stale M04 start report", exception);
    }
  }

  private static void deleteTree(Path root) {
    if (!Files.exists(root)) {
      return;
    }
    try (var paths = Files.walk(root)) {
      paths.sorted(Comparator.reverseOrder()).forEach(M04StartCheckRunner::deletePath);
    } catch (IOException exception) {
      throw new IllegalStateException("cannot clear inherited M03 report", exception);
    }
  }

  private static void deletePath(Path path) {
    try {
      Files.deleteIfExists(path);
    } catch (IOException exception) {
      throw new IllegalStateException("cannot delete inherited M03 path " + path, exception);
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

  private record FrozenInput(String sha256, int schemaProbes, Map<String, Integer> policyCounts) {
    private FrozenInput {
      policyCounts = Map.copyOf(policyCounts);
    }
  }
}
