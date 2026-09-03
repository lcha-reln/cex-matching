package io.github.lchareln.cex.matching.testkit;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Binds the 22 frozen scenario identities to executed protocol, runtime, and architecture facts.
 */
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
    require(architecture.path("violations").isEmpty(), "M11 architecture gate has violations");

    ArrayNode results = JsonSupport.MAPPER.createArrayNode();
    List<String> ids = new ArrayList<>();
    for (JsonNode scenario : workload.path("fixedScenarios")) {
      String id = scenario.path("id").stringValue();
      assertScenario(id, protocol, generated, architecture);
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
    require(
        ids.equals(M11StartCheckRunner.SCENARIO_IDS),
        "M11 fixed scenario identity or order changed");
    require(results.size() == 22, "M11 fixed scenario count changed");

    ObjectNode report = JsonSupport.MAPPER.createObjectNode();
    report.put("schemaVersion", "matching.m11.fixed-scenarios.v1");
    report.put("status", M11CheckRunner.PASS);
    report.put("scenarios", results.size());
    report.put("passed", results.size());
    report.set("results", results);
    return new Result(report, results.size());
  }

  private static void assertScenario(
      String id,
      M11ProtocolSuite.Result protocol,
      M11GeneratedSuite.Result generated,
      ObjectNode architecture) {
    JsonNode codec = protocol.report();
    JsonNode cluster = generated.clusterReport();
    JsonNode snapshot = generated.snapshotReport();
    switch (id) {
      case "CODEC_V1_GOLDENS" ->
          require(
              codec.path("requestV1Readable").booleanValue()
                  && codec.path("responseV1DownEncoded").booleanValue()
                  && codec.path("snapshotS1ReadableAndRestorable").booleanValue(),
              id);
      case "CODEC_V2_GOLDENS" ->
          require(
              codec.path("requestV2Current").booleanValue()
                  && codec.path("responseV2Current").booleanValue()
                  && codec.path("snapshotS2Current").booleanValue(),
              id);
      case "MALFORMED_FAILS_CLOSED" ->
          require(
              codec.path("malformedFailsClosed").booleanValue()
                  && codec.path("forgedPayloadHashPreApplyRejected").booleanValue()
                  && codec.path("forgedPayloadHashStateMutations").intValue() == 0,
              id);
      case "UNSUPPORTED_VERSION_FAILS_CLOSED" ->
          require(
              codec.path("unsupportedFailsClosed").booleanValue()
                  && codec.path("invalidRequestedResponseStateMutations").intValue() == 0
                  && codec.path("fabricatedBusinessResults").intValue() == 0,
              id);
      case "REAL_SINGLE_MEMBER_LEADER" ->
          require(
              "REAL_AERON_CLUSTER".equals(cluster.path("implementation").stringValue())
                  && cluster.path("memberCount").intValue() == 1
                  && cluster.path("clusterRuns").intValue() == 2
                  && cluster.path("acceptedIngressOffers").intValue() == 8192
                  && cluster.path("componentErrors").intValue() == 0,
              id);
      case "OFFER_IS_NOT_SUCCESS" ->
          require(
              cluster.path("allBusinessOutcomesFromCorrelatedEgress").booleanValue()
                  && cluster.path("acceptedIngressOffers").intValue()
                      == cluster.path("correlatedResponses").intValue(),
              id);
      case "CORRELATION_ROUND_TRIP" ->
          require(cluster.path("correlationRoundTrips").intValue() == 8192, id);
      case "SESSION_NOT_BUSINESS_IDENTITY" ->
          require(
              snapshot.path("duplicateOriginalResultsSurvived").intValue() == 1024
                  && snapshot.path("restartCount").intValue() == 1
                  && snapshot.path("distinctClientSessionIds").intValue() >= 2
                  && snapshot.path("preSnapshotSessionId").longValue()
                      != snapshot.path("postRestartSessionId").longValue()
                  && snapshot.path("identityReplayedAcrossSessions").booleanValue(),
              id);
      case "NEW_RESPONSE_AFTER_APPLY" ->
          require(
              cluster.path("serviceObservations").intValue()
                      == cluster.path("correlatedResponses").intValue()
                  && generated.directResults().stream()
                      .filter(
                          result ->
                              result.response().status()
                                  == io.github.lchareln.cex.matching.cluster.M11ResponseStatus
                                      .NEW_APPLIED)
                      .allMatch(result -> result.fullResult().isPresent()),
              id);
      case "DUPLICATE_REPLAYS_ORIGINAL" ->
          require(
              generated.directResults().stream()
                          .filter(
                              result ->
                                  result.response().status()
                                      == io.github.lchareln.cex.matching.cluster.M11ResponseStatus
                                          .DUPLICATE_REPLAYED)
                          .count()
                      == 1024
                  && generated.directResults().stream()
                      .filter(
                          result ->
                              result.response().status()
                                  == io.github.lchareln.cex.matching.cluster.M11ResponseStatus
                                      .DUPLICATE_REPLAYED)
                      .allMatch(result -> result.fullResult().isPresent()),
              id);
      case "COMMAND_ID_CONFLICT_NO_MUTATION" ->
          require(
              generated.generatedReport().path("commandIdConflicts").intValue() == 512
                  && generated.finalState().nextApplicationSequence() == 2049,
              id);
      case "SLOT_CONFLICT_NO_MUTATION" ->
          require(
              generated.generatedReport().path("slotConflicts").intValue() == 512
                  && generated.finalState().identityBindings().size() == 2048,
              id);
      case "DIRECT_CLUSTER_EVENTS_EQUAL" ->
          require(
              generated.directResults().equals(generated.uninterruptedResults())
                  && generated.directResults().equals(generated.restartedResults()),
              id);
      case "DIRECT_CLUSTER_DIGEST_EQUAL" ->
          require(
              generated.generatedReport().path("threePathFullBusinessEquivalent").booleanValue()
                  && generated.finalState().nextApplicationSequence() == 2049,
              id);
      case "RUNTIME_METADATA_EXCLUDED" ->
          require(
              !architecture.path("runtimeMetadataInBusinessDigest").booleanValue()
                  && generated.uninterruptedResults().equals(generated.restartedResults()),
              id);
      case "NO_STANDALONE_WAL_WRITE" ->
          require(
              architecture.path("standaloneWalWrites").intValue() == 0
                  && architecture.path("clusterServiceLocalWalViolations").intValue() == 0,
              id);
      case "SNAPSHOT_ACCEPTANCE_AND_COMPLETION_DISTINCT" ->
          require(
              snapshot.path("adminRequestAccepted").booleanValue()
                  && snapshot.path("completionBounded").booleanValue()
                  && snapshot.path("completionCountAfter").longValue()
                      > snapshot.path("completionCountBefore").longValue()
                  && snapshot.path("acceptanceDistinctFromCompletion").booleanValue()
                  && snapshot.path("controlToggleResetToNeutral").booleanValue()
                  && snapshot.path("recordingLogNewSnapshotEntry").booleanValue()
                  && snapshot.path("recordingIdsChanged").booleanValue()
                  && snapshot.path("sameTermAndLogPosition").booleanValue(),
              id);
      case "SNAPSHOT_STATE_EXACT_AFTER_RESTART" ->
          require(
              snapshot.path("loadedSnapshot").booleanValue()
                  && snapshot.path("directoriesPreserved").booleanValue()
                  && snapshot.path("identityDigestExact").booleanValue()
                  && snapshot.path("semanticDigestExact").booleanValue(),
              id);
      case "SNAPSHOT_IDENTITY_RESULT_SURVIVES" ->
          require(
              snapshot.path("duplicateOriginalResultsSurvived").intValue() == 1024
                  && codec.path("nMinusOneIdempotencyPreserved").booleanValue(),
              id);
      case "SNAPSHOT_SEQUENCE_CONTINUES" ->
          require(
              snapshot.path("nextApplicationSequenceExact").booleanValue()
                  && snapshot.path("restoredNextApplicationSequence").longValue() == 2049,
              id);
      case "CURRENT_READS_PREVIOUS_SNAPSHOT" ->
          require(
              codec.path("snapshotS1ReadableAndRestorable").booleanValue()
                  && codec.path("nMinusOneIdempotencyPreserved").booleanValue(),
              id);
      case "CURRENT_DOWN_ENCODES_PREVIOUS_RESPONSE" ->
          require(
              codec.path("responseV1DownEncoded").booleanValue()
                  && codec.path("responseV1OutcomesCovered").intValue() == 4,
              id);
      default -> throw new IllegalArgumentException("unknown M11 scenario " + id);
    }
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
      case "CODEC_V1_GOLDENS",
          "CODEC_V2_GOLDENS",
          "MALFORMED_FAILS_CLOSED",
          "UNSUPPORTED_VERSION_FAILS_CLOSED",
          "CURRENT_READS_PREVIOUS_SNAPSHOT",
          "CURRENT_DOWN_ENCODES_PREVIOUS_RESPONSE" ->
          "protocol-goldens.json";
      case "NO_STANDALONE_WAL_WRITE", "RUNTIME_METADATA_EXCLUDED" -> "architecture.json";
      case "SNAPSHOT_ACCEPTANCE_AND_COMPLETION_DISTINCT",
          "SNAPSHOT_STATE_EXACT_AFTER_RESTART",
          "SNAPSHOT_IDENTITY_RESULT_SURVIVES",
          "SNAPSHOT_SEQUENCE_CONTINUES" ->
          "cluster-runtime.json";
      default -> "generated-differential.json+cluster-runtime.json";
    };
  }

  private static String detail(String id) {
    return switch (id) {
      case "SNAPSHOT_ACCEPTANCE_AND_COMPLETION_DISTINCT" ->
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
      throw new M11SemanticFailure("M11 fixed scenario failed: " + message);
    }
  }

  record Result(ObjectNode report, int passed) {}
}
