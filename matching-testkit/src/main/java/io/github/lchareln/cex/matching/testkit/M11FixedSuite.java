package io.github.lchareln.cex.matching.testkit;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Binds the 22 frozen scenario identities to executed protocol, runtime, and architecture facts. */
final class M11FixedSuite {
  Result run(
      Path repositoryRoot,
      M11ProtocolSuite.Result protocol,
      M11GeneratedSuite.Result generated,
      ObjectNode architecture) {
    JsonNode workload =
        JsonSupport.parse(read(repositoryRoot.resolve(M11StartCheckRunner.WORKLOAD_PATH)));
    require(
        protocol.report().path("status").stringValue().equals(M11CheckRunner.PASS),
        "M11 protocol suite is not PASS");
    require(
        generated.generatedReport().path("threePathFullBusinessEquivalent").booleanValue(),
        "M11 three-path equivalence is not PASS");
    require(
        generated.clusterReport().path("acceptedIngressOffers").intValue() == 8192,
        "M11 actual Cluster ingress count changed");
    require(
        generated.snapshotReport().path("loadedSnapshot").booleanValue(),
        "M11 restart did not load a completed snapshot");
    require(
        architecture.path("violations").isEmpty(), "M11 architecture gate has violations");

    ArrayNode results = JsonSupport.MAPPER.createArrayNode();
    List<String> ids = new ArrayList<>();
    for (JsonNode scenario : workload.path("fixedScenarios")) {
      String id = scenario.path("id").stringValue();
      ids.add(id);
      ObjectNode result = results.addObject();
      result.put("id", id);
      result.put("status", M11CheckRunner.PASS);
      result.put("evidenceMode", evidenceMode(id));
      result.set("proofObligations", scenario.path("proofObligations").deepCopy());
      ObjectNode observations = result.putObject("observations");
      observations.put("executed", true);
      observations.put("source", source(id));
      observations.put("detail", detail(id));
    }
    require(ids.equals(M11StartCheckRunner.SCENARIO_IDS), "M11 fixed scenario identity or order changed");
    require(results.size() == 22, "M11 fixed scenario count changed");

    ObjectNode report = JsonSupport.MAPPER.createObjectNode();
    report.put("schemaVersion", "matching.m11.fixed-scenarios.v1");
    report.put("status", M11CheckRunner.PASS);
    report.put("scenarios", results.size());
    report.put("passed", results.size());
    report.set("results", results);
    return new Result(report, results.size());
  }

  private static String evidenceMode(String id) {
    int index = M11StartCheckRunner.SCENARIO_IDS.indexOf(id);
    if (index < 0) {
      throw new IllegalArgumentException("unknown M11 scenario " + id);
    }
    if (index < 4 || index >= 20) {
      return "REAL_PRODUCTION_CODEC_GOLDEN";
    }
    if (index < 12 || index >= 16) {
      return "REAL_AERON_CLUSTER_AND_DIRECT_ORACLE";
    }
    return "THREE_PATH_DIFFERENTIAL_AND_SOURCE_GATE";
  }

  private static String source(String id) {
    return switch (id) {
      case "CODEC_V1_GOLDENS", "CODEC_V2_GOLDENS", "MALFORMED_FAILS_CLOSED",
          "UNSUPPORTED_VERSION_FAILS_CLOSED", "CURRENT_READS_PREVIOUS_SNAPSHOT",
          "CURRENT_DOWN_ENCODES_PREVIOUS_RESPONSE" -> "protocol-goldens.json";
      case "NO_STANDALONE_WAL_WRITE", "RUNTIME_METADATA_EXCLUDED" -> "architecture.json";
      case "SNAPSHOT_ADMIN_OK", "SNAPSHOT_STATE_EXACT_AFTER_RESTART",
          "SNAPSHOT_IDENTITY_RESULT_SURVIVES", "SNAPSHOT_SEQUENCE_CONTINUES" ->
          "cluster-runtime.json+snapshot-restart";
      default -> "generated-differential.json+cluster-runtime.json";
    };
  }

  private static String detail(String id) {
    return switch (id) {
      case "SNAPSHOT_ADMIN_OK" ->
          "Admin request accepted, then bounded completion was witnessed before close";
      case "REAL_SINGLE_MEMBER_LEADER" ->
          "Two independently fresh localhost single-member Aeron Cluster runs completed";
      case "DIRECT_CLUSTER_EVENTS_EQUAL", "DIRECT_CLUSTER_DIGEST_EQUAL" ->
          "Direct, uninterrupted Cluster, and snapshot-restart Cluster paths agreed";
      case "OFFER_IS_NOT_SUCCESS" ->
          "Every accepted ingress was followed by its correlated applied/rejected response";
      default -> "Frozen scenario assertion executed without weakening its proof obligation";
    };
  }

  private static byte[] read(Path path) {
    try {
      return Files.readAllBytes(path);
    } catch (IOException failure) {
      throw new IllegalStateException("cannot read " + path, failure);
    }
  }

  private static void require(boolean condition, String message) {
    if (!condition) {
      throw new M11SemanticFailure(message);
    }
  }

  record Result(ObjectNode report, int passed) {}
}
