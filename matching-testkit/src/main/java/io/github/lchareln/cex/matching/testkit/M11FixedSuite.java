package io.github.lchareln.cex.matching.testkit;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
    List<Fact> facts = new ArrayList<>();
    for (JsonNode scenario : workload.path("fixedScenarios")) {
      String id = scenario.path("id").stringValue();
      assertScenario(id, protocol, generated, architecture);
      List<String> declared = strings(scenario.path("proofObligations"));
      require(declared.equals(declaredObligations(id)), id + " obligation declaration changed");
      List<Fact> asserted = factsAfterAssertion(id, protocol, generated, architecture);
      require(
          asserted.stream()
              .map(Fact::obligation)
              .distinct()
              .toList()
              .equals(assertedObligations(id)),
          id + " assertion fact mapping changed");
      facts.addAll(asserted);
      ids.add(id);
      ObjectNode result = results.addObject();
      result.put("id", id);
      result.put("status", M11CheckRunner.PASS);
      result.put("evidenceMode", evidenceMode(id));
      result.set("proofObligations", scenario.path("proofObligations").deepCopy());
      ArrayNode assertedFacts = result.putArray("assertedFacts");
      asserted.forEach(fact -> fact.write(assertedFacts.addObject()));
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
    report.put("assertionFacts", facts.size());
    report.put("factSource", "EXECUTED_ASSERTION_LEDGER");
    report.set("results", results);
    ArrayNode ledger = report.putArray("factLedger");
    facts.forEach(fact -> fact.write(ledger.addObject()));
    return new Result(report, results.size(), facts);
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
                  && codec.path("snapshotS2Current").booleanValue()
                  && codec.path("fixtures").size() == 6
                  && java.util.stream.StreamSupport.stream(
                          codec.path("fixtures").spliterator(), false)
                      .allMatch(
                          fixture ->
                              fixture.path("decoded").booleanValue()
                                  && fixture.path("reencodedByteExact").booleanValue()),
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
                  && cluster.path("componentErrors").intValue() == 0
                  && "1.52.2".equals(architecture.path("configuredAeronVersion").stringValue())
                  && architecture.path("versionConfigurationExact").booleanValue(),
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
              snapshot.path("duplicateOriginalResultsSurvived").intValue() == 512
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
                  && architecture.path("businessApplyCalls").longValue() > 0
                  && architecture.path("logCallbackBusinessApplyCalls").longValue()
                      == architecture.path("businessApplyCalls").longValue()
                  && architecture.path("nonLogCallbackBusinessApplyCalls").longValue() == 0
                  && architecture.path("egressStateInputViolations").intValue() == 0
                  && generated.directResults().stream()
                      .filter(
                          result ->
                              result.response().status()
                                  == io.github.lchareln.cex.matching.cluster.M11ResponseStatus
                                      .NEW_APPLIED)
                      .allMatch(result -> result.fullResult().isPresent()),
              id);
      case "DUPLICATE_REPLAYS_ORIGINAL" ->
          require(duplicatesReplayOriginal(generated.directResults()) == 1024, id);
      case "COMMAND_ID_CONFLICT_NO_MUTATION" ->
          require(
              generated.generatedReport().path("commandIdConflicts").intValue() == 512
                  && identityRejections(generated.directResults(), "COMMAND_ID_SLOT_CONFLICT")
                      == 512
                  && generated.finalState().nextApplicationSequence() == 2049
                  && snapshot.path("postRestartConflictStateInvariantChecks").intValue() == 1024
                  && snapshot.path("postRestartConflictSequenceInvariantChecks").intValue() == 1024,
              id);
      case "SLOT_CONFLICT_NO_MUTATION" ->
          require(
              generated.generatedReport().path("slotConflicts").intValue() == 512
                  && identityRejections(generated.directResults(), "SLOT_IDENTITY_CONFLICT") == 512
                  && generated.finalState().identityBindings().size() == 2048
                  && snapshot.path("postRestartConflictIdentityInvariantChecks").intValue() == 1024
                  && snapshot.path("postRestartConflictCursorInvariantChecks").intValue() == 1024,
              id);
      case "DIRECT_CLUSTER_EVENTS_EQUAL" ->
          require(
              architecture.path("matchingCoreByteIdentical").booleanValue()
                  && architecture.path("coreInfrastructureFree").booleanValue()
                  && generated.directResults().equals(generated.uninterruptedResults())
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
                  && architecture.path("runtimeMetadataSpyExecuted").booleanValue()
                  && architecture.path("runtimeMetadataVariants").intValue() >= 2
                  && architecture.path("runtimeMetadataDigestStable").booleanValue()
                  && generated.uninterruptedResults().equals(generated.restartedResults()),
              id);
      case "NO_STANDALONE_WAL_WRITE" ->
          require(
              architecture.path("callbackReachableStandaloneWalReferences").intValue() == 0
                  && "CALLBACK_REACHABLE_SOURCE_REFERENCE_COUNT_COMPATIBILITY"
                      .equals(architecture.path("standaloneWalWritesEvidenceMode").stringValue())
                  && architecture.path("standaloneWalWrites").intValue() == 0
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
              snapshot.path("duplicateOriginalResultsSurvived").intValue() == 512
                  && snapshot.path("postRestartCrossSnapshotDuplicates").intValue() == 512
                  && snapshot.path("postRestartDuplicateFullResultExact").booleanValue()
                  && snapshot.path("postRestartDuplicateStateInvariantChecks").intValue() == 512
                  && snapshot.path("postRestartDuplicateSequenceInvariantChecks").intValue() == 512
                  && snapshot.path("postRestartDuplicateIdentityInvariantChecks").intValue() == 512
                  && snapshot.path("postRestartDuplicateCursorInvariantChecks").intValue() == 512
                  && codec.path("nMinusOneIdempotencyPreserved").booleanValue(),
              id);
      case "SNAPSHOT_SEQUENCE_CONTINUES" ->
          require(
              snapshot.path("nextApplicationSequenceExact").booleanValue()
                  && snapshot.path("snapshotNextApplicationSequence").longValue() == 1537
                  && snapshot.path("restoredNextApplicationSequence").longValue() == 1537
                  && "PREVIOUS_NEW".equals(snapshot.path("firstPostRestartLane").stringValue())
                  && "NEW_APPLIED".equals(snapshot.path("firstPostRestartStatus").stringValue())
                  && snapshot.path("firstPostRestartApplicationSequence").longValue() == 1537
                  && snapshot.path("firstPostRestartProducerSequence").longValue() == 1537,
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

  private static long duplicatesReplayOriginal(
      List<io.github.lchareln.cex.matching.cluster.M11ApplicationResult> results) {
    Map<Long, io.github.lchareln.cex.matching.local.CanonicalResult> originals =
        new LinkedHashMap<>();
    long duplicates = 0;
    for (var result : results) {
      switch (result.response().status()) {
        case NEW_APPLIED -> {
          var full = result.fullResult().orElseThrow();
          long sequence = result.response().applicationSequence().orElseThrow();
          require(full.applicationSequence() == sequence, "NEW result sequence changed");
          require(originals.put(sequence, full) == null, "duplicate NEW application sequence");
        }
        case DUPLICATE_REPLAYED -> {
          var full = result.fullResult().orElseThrow();
          long sequence = result.response().applicationSequence().orElseThrow();
          require(
              full.equals(originals.get(sequence)),
              "duplicate did not replay the exact original CanonicalResult");
          require(
              result.response().resultDigest().orElseThrow().equals(full.resultDigest()),
              "duplicate response commitment differs from original result");
          duplicates++;
        }
        case REJECTED -> require(result.fullResult().isEmpty(), "rejection fabricated a result");
      }
    }
    return duplicates;
  }

  private static long identityRejections(
      List<io.github.lchareln.cex.matching.cluster.M11ApplicationResult> results, String code) {
    return results.stream()
        .filter(
            result ->
                result.response().status()
                    == io.github.lchareln.cex.matching.cluster.M11ResponseStatus.REJECTED)
        .peek(result -> require(result.fullResult().isEmpty(), "rejection fabricated a result"))
        .filter(result -> result.response().rejectionCode().orElse("").equals(code))
        .count();
  }

  private static List<Fact> factsAfterAssertion(
      String id,
      M11ProtocolSuite.Result protocol,
      M11GeneratedSuite.Result generated,
      ObjectNode architecture) {
    JsonNode codec = protocol.report();
    JsonNode cluster = generated.clusterReport();
    JsonNode snapshot = generated.snapshotReport();
    return switch (id) {
      case "CODEC_V1_GOLDENS" ->
          List.of(
              fact(
                  id,
                  "N_MINUS_ONE_READABLE",
                  "request-v1, response-v1, and snapshot-v1 were decoded by current production codecs; snapshot identity remained replayable",
                  "requestV1=true,responseV1=true,snapshotS1=true"));
      case "CODEC_V2_GOLDENS" ->
          List.of(
              fact(
                  id,
                  "CURRENT_GOLDEN_WRITE_EXACT",
                  "current request, response, and snapshot fixtures re-encoded byte-exact",
                  "goldens=" + codec.path("goldens").intValue()));
      case "MALFORMED_FAILS_CLOSED" ->
          List.of(
              fact(
                  id,
                  "MALFORMED_FAILS_CLOSED",
                  "malformed and forged payload-hash inputs were rejected before apply",
                  "stateMutations=" + codec.path("forgedPayloadHashStateMutations").intValue()));
      case "UNSUPPORTED_VERSION_FAILS_CLOSED" ->
          List.of(
              fact(
                  id,
                  "UNSUPPORTED_VERSION_FAILS_CLOSED",
                  "unsupported request/response negotiation was rejected before apply without a fabricated business result",
                  "stateMutations="
                      + codec.path("invalidRequestedResponseStateMutations").intValue()
                      + ",fabricatedResults="
                      + codec.path("fabricatedBusinessResults").intValue()));
      case "REAL_SINGLE_MEMBER_LEADER" ->
          List.of(
              fact(
                  id,
                  "AERON_VERSION_PINNED",
                  "resolved production Aeron dependency matched the frozen version catalog",
                  architecture.path("configuredAeronVersion").stringValue()),
              fact(
                  id,
                  "REAL_SINGLE_MEMBER_CLUSTER",
                  "two fresh one-member Aeron Cluster runs completed all ingress with no component errors",
                  "runs="
                      + cluster.path("clusterRuns").intValue()
                      + ",ingress="
                      + cluster.path("acceptedIngressOffers").intValue()),
              fact(
                  id,
                  "AERON_DEPENDENCY_CONFINED",
                  "Aeron imports and dependencies were confined to matching-cluster-runtime main and test scopes",
                  "importViolations="
                      + architecture.path("aeronJavaImportViolations").intValue()
                      + ",dependencyViolations="
                      + architecture.path("aeronDependencyViolations").intValue()));
      case "OFFER_IS_NOT_SUCCESS" ->
          List.of(
              fact(
                  id,
                  "INGRESS_OFFER_NOT_ACK",
                  "every accepted ingress obtained its business outcome from correlated egress",
                  "ingress="
                      + cluster.path("acceptedIngressOffers").intValue()
                      + ",egress="
                      + cluster.path("correlatedResponses").intValue()));
      case "CORRELATION_ROUND_TRIP" ->
          List.of(
              fact(
                  id,
                  "CORRELATION_ROUND_TRIP",
                  "every actual Cluster response preserved the submitted correlation ID",
                  "roundTrips=" + cluster.path("correlationRoundTrips").intValue()));
      case "SESSION_NOT_BUSINESS_IDENTITY" ->
          List.of(
              fact(
                  id,
                  "COMMAND_ID_STABLE",
                  "the same durable command identity replayed its original result across a client-session change",
                  "sessions=" + snapshot.path("distinctClientSessionIds").intValue()),
              fact(
                  id,
                  "SESSION_NOT_BUSINESS_IDENTITY",
                  "the restart path observed different client session IDs without changing durable identity",
                  snapshot.path("preSnapshotSessionId").longValue()
                      + "->"
                      + snapshot.path("postRestartSessionId").longValue()));
      case "NEW_RESPONSE_AFTER_APPLY" ->
          List.of(
              fact(
                  id,
                  "LOG_CALLBACK_ONLY_APPLY",
                  "all production business applies were reachable from the committed-log callback and none from another callback",
                  "businessApplyCalls="
                      + architecture.path("businessApplyCalls").longValue()
                      + ",logCallback="
                      + architecture.path("logCallbackBusinessApplyCalls").longValue()
                      + ",nonLog="
                      + architecture.path("nonLogCallbackBusinessApplyCalls").longValue()),
              fact(
                  id,
                  "RESPONSE_AFTER_RESULT_BIND",
                  "every NEW Cluster observation carried the full result before its correlated response",
                  "observations=" + cluster.path("serviceObservations").intValue()),
              fact(
                  id,
                  "EGRESS_OFFER_NOT_STATE_INPUT",
                  "source reachability found no business-state transition driven by egress offer outcome",
                  "violations=" + architecture.path("egressStateInputViolations").intValue()));
      case "DUPLICATE_REPLAYS_ORIGINAL" ->
          List.of(
              fact(
                  id,
                  "DUPLICATE_ORIGINAL_RESULT",
                  "each duplicate full result and result digest equaled the earlier NEW result at the same application sequence",
                  "exactDuplicates=" + duplicatesReplayOriginal(generated.directResults())));
      case "COMMAND_ID_CONFLICT_NO_MUTATION" ->
          List.of(
              fact(
                  id,
                  "ID_CONFLICT_NO_MUTATION",
                  "all command-ID conflicts were rejected with per-action state and sequence invariants",
                  "conflicts="
                      + generated.generatedReport().path("commandIdConflicts").intValue()
                      + ",stateChecks="
                      + snapshot.path("postRestartConflictStateInvariantChecks").intValue()
                      + ",sequenceChecks="
                      + snapshot.path("postRestartConflictSequenceInvariantChecks").intValue()));
      case "SLOT_CONFLICT_NO_MUTATION" ->
          List.of(
              fact(
                  id,
                  "SLOT_CONFLICT_NO_MUTATION",
                  "all slot conflicts were rejected with per-action identity and producer-cursor invariants",
                  "conflicts="
                      + generated.generatedReport().path("slotConflicts").intValue()
                      + ",identityChecks="
                      + snapshot.path("postRestartConflictIdentityInvariantChecks").intValue()
                      + ",cursorChecks="
                      + snapshot.path("postRestartConflictCursorInvariantChecks").intValue()));
      case "DIRECT_CLUSTER_EVENTS_EQUAL" ->
          List.of(
              fact(
                  id,
                  "CORE_INFRASTRUCTURE_FREE",
                  "the M10 matching-core tree remained byte-identical and infrastructure-free",
                  architecture.path("headCoreTree").stringValue()),
              fact(
                  id,
                  "DIRECT_CLUSTER_EVENT_EQUIVALENCE",
                  "direct, uninterrupted Cluster, and restart Cluster emitted equal full application-result streams",
                  "comparisons="
                      + generated.generatedReport().path("directClusterComparisons").intValue()));
      case "DIRECT_CLUSTER_DIGEST_EQUAL" ->
          List.of(
              fact(
                  id,
                  "DIRECT_CLUSTER_RESULT_EQUIVALENCE",
                  "all three paths produced identical bounded responses and full canonical results",
                  "clusterComparisons="
                      + generated.generatedReport().path("clusterClusterComparisons").intValue()),
              fact(
                  id,
                  "DIRECT_CLUSTER_STATE_EQUIVALENCE",
                  "all three paths ended at the exact same complete business-state image",
                  "nextSequence=" + generated.finalState().nextApplicationSequence()));
      case "RUNTIME_METADATA_EXCLUDED" ->
          List.of(
              fact(
                  id,
                  "RUNTIME_METADATA_EXCLUDED",
                  "runtime metadata variants were exercised while the business semantic digest remained stable",
                  "spy="
                      + architecture.path("runtimeMetadataSpyExecuted").booleanValue()
                      + ",variants="
                      + architecture.path("runtimeMetadataVariants").intValue()));
      case "NO_STANDALONE_WAL_WRITE" ->
          List.of(
              fact(
                  id,
                  "NO_STANDALONE_WAL_WRITE",
                  "the ClusteredService callback-reachable production source graph contains no standalone-WAL reference",
                  "callbackReachableReferences="
                      + architecture.path("callbackReachableStandaloneWalReferences").intValue()
                      + ",evidenceMode="
                      + architecture.path("standaloneWalWritesEvidenceMode").stringValue()));
      case "SNAPSHOT_ACCEPTANCE_AND_COMPLETION_DISTINCT" ->
          List.of(
              fact(
                  id,
                  "REAL_SINGLE_MEMBER_CLUSTER",
                  "the real single-member harness performed an accepted and completed admin snapshot",
                  "completed=" + cluster.path("snapshotsCompleted").intValue()),
              fact(
                  id,
                  "SNAPSHOT_COMPLETION_RECORDED",
                  "admin acceptance preceded a bounded completed recording-log snapshot with changed recording IDs, same term/position, and NEUTRAL toggle",
                  snapshot.path("completionCountBefore").longValue()
                      + "->"
                      + snapshot.path("completionCountAfter").longValue()));
      case "SNAPSHOT_STATE_EXACT_AFTER_RESTART" ->
          List.of(
              fact(
                  id,
                  "SNAPSHOT_RESTART_EXACT",
                  "restart loaded the completed snapshot from preserved directories with exact identity and semantic digests",
                  snapshot.path("snapshotStateSha256").stringValue()));
      case "SNAPSHOT_IDENTITY_RESULT_SURVIVES" ->
          List.of(
              fact(
                  id,
                  "SNAPSHOT_IDENTITY_SURVIVES",
                  "every cross-snapshot duplicate replayed the original result with per-action state, sequence, identity, and cursor invariants",
                  "duplicates="
                      + snapshot.path("postRestartCrossSnapshotDuplicates").intValue()
                      + ",fullResultExact="
                      + snapshot.path("postRestartDuplicateFullResultExact").booleanValue()));
      case "SNAPSHOT_SEQUENCE_CONTINUES" ->
          List.of(
              fact(
                  id,
                  "SNAPSHOT_SEQUENCE_CONTINUES",
                  "snapshot restored next sequence 1537 and the first post-restart PREVIOUS_NEW applied at that exact sequence",
                  "restoredNext="
                      + snapshot.path("restoredNextApplicationSequence").longValue()
                      + ",firstApplied="
                      + snapshot.path("firstPostRestartApplicationSequence").longValue()));
      case "CURRENT_READS_PREVIOUS_SNAPSHOT" ->
          List.of(
              fact(
                  id,
                  "N_MINUS_ONE_READABLE",
                  "current snapshot codec decoded/restored S1 with ordered durable identity results",
                  "bindings=" + codec.path("snapshotIdentityBindingsMinimum").intValue()),
              fact(
                  id,
                  "SNAPSHOT_IDENTITY_SURVIVES",
                  "an S1-restored duplicate replayed its original canonical result",
                  "preserved=" + codec.path("nMinusOneIdempotencyPreserved").booleanValue()));
      case "CURRENT_DOWN_ENCODES_PREVIOUS_RESPONSE" ->
          List.of(
              fact(
                  id,
                  "N_MINUS_ONE_READABLE",
                  "current runtime down-encoded all four promised response-v1 outcomes and current decoder read them",
                  "outcomes=" + codec.path("responseV1OutcomesCovered").intValue()));
      default -> throw new IllegalArgumentException("unknown M11 scenario " + id);
    };
  }

  private static Fact fact(
      String scenarioId, String obligation, String assertion, String observedValue) {
    return new Fact(obligation, scenarioId, source(scenarioId), assertion, observedValue);
  }

  static List<String> assertedObligations(String id) {
    return switch (id) {
      case "CODEC_V1_GOLDENS" -> List.of("N_MINUS_ONE_READABLE");
      case "CODEC_V2_GOLDENS" -> List.of("CURRENT_GOLDEN_WRITE_EXACT");
      case "MALFORMED_FAILS_CLOSED" -> List.of("MALFORMED_FAILS_CLOSED");
      case "UNSUPPORTED_VERSION_FAILS_CLOSED" -> List.of("UNSUPPORTED_VERSION_FAILS_CLOSED");
      case "REAL_SINGLE_MEMBER_LEADER" ->
          List.of(
              "AERON_VERSION_PINNED", "REAL_SINGLE_MEMBER_CLUSTER", "AERON_DEPENDENCY_CONFINED");
      case "OFFER_IS_NOT_SUCCESS" -> List.of("INGRESS_OFFER_NOT_ACK");
      case "CORRELATION_ROUND_TRIP" -> List.of("CORRELATION_ROUND_TRIP");
      case "SESSION_NOT_BUSINESS_IDENTITY" ->
          List.of("COMMAND_ID_STABLE", "SESSION_NOT_BUSINESS_IDENTITY");
      case "NEW_RESPONSE_AFTER_APPLY" ->
          List.of(
              "LOG_CALLBACK_ONLY_APPLY",
              "RESPONSE_AFTER_RESULT_BIND",
              "EGRESS_OFFER_NOT_STATE_INPUT");
      case "DUPLICATE_REPLAYS_ORIGINAL" -> List.of("DUPLICATE_ORIGINAL_RESULT");
      case "COMMAND_ID_CONFLICT_NO_MUTATION" -> List.of("ID_CONFLICT_NO_MUTATION");
      case "SLOT_CONFLICT_NO_MUTATION" -> List.of("SLOT_CONFLICT_NO_MUTATION");
      case "DIRECT_CLUSTER_EVENTS_EQUAL" ->
          List.of("CORE_INFRASTRUCTURE_FREE", "DIRECT_CLUSTER_EVENT_EQUIVALENCE");
      case "DIRECT_CLUSTER_DIGEST_EQUAL" ->
          List.of("DIRECT_CLUSTER_RESULT_EQUIVALENCE", "DIRECT_CLUSTER_STATE_EQUIVALENCE");
      case "RUNTIME_METADATA_EXCLUDED" -> List.of("RUNTIME_METADATA_EXCLUDED");
      case "NO_STANDALONE_WAL_WRITE" -> List.of("NO_STANDALONE_WAL_WRITE");
      case "SNAPSHOT_ACCEPTANCE_AND_COMPLETION_DISTINCT" ->
          List.of("REAL_SINGLE_MEMBER_CLUSTER", "SNAPSHOT_COMPLETION_RECORDED");
      case "SNAPSHOT_STATE_EXACT_AFTER_RESTART" -> List.of("SNAPSHOT_RESTART_EXACT");
      case "SNAPSHOT_IDENTITY_RESULT_SURVIVES" -> List.of("SNAPSHOT_IDENTITY_SURVIVES");
      case "SNAPSHOT_SEQUENCE_CONTINUES" -> List.of("SNAPSHOT_SEQUENCE_CONTINUES");
      case "CURRENT_READS_PREVIOUS_SNAPSHOT" ->
          List.of("N_MINUS_ONE_READABLE", "SNAPSHOT_IDENTITY_SURVIVES");
      case "CURRENT_DOWN_ENCODES_PREVIOUS_RESPONSE" -> List.of("N_MINUS_ONE_READABLE");
      default -> throw new IllegalArgumentException("unknown M11 scenario " + id);
    };
  }

  static List<String> declaredObligations(String id) {
    if ("MALFORMED_FAILS_CLOSED".equals(id)) {
      return List.of("MALFORMED_FAILS_CLOSED", "SYSTEM_ERROR_NEVER_PASS");
    }
    return assertedObligations(id);
  }

  private static List<String> strings(JsonNode values) {
    List<String> result = new ArrayList<>();
    values.forEach(value -> result.add(value.stringValue()));
    return List.copyOf(result);
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
      case "REAL_SINGLE_MEMBER_LEADER" -> "architecture.json+cluster-runtime.json";
      case "NEW_RESPONSE_AFTER_APPLY" -> "architecture.json+cluster-runtime.json";
      case "DIRECT_CLUSTER_EVENTS_EQUAL" -> "architecture.json+generated-differential.json";
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

  record Fact(
      String obligation,
      String scenarioId,
      String sourceArtifact,
      String assertion,
      String observedValue) {
    Fact {
      requireNonBlank(obligation, "obligation");
      requireNonBlank(scenarioId, "scenarioId");
      requireNonBlank(sourceArtifact, "sourceArtifact");
      requireNonBlank(assertion, "assertion");
      requireNonBlank(observedValue, "observedValue");
    }

    void write(ObjectNode target) {
      target.put("obligation", obligation);
      target.put("scenarioId", scenarioId);
      target.put("sourceArtifact", sourceArtifact);
      target.put("assertion", assertion);
      target.put("observedValue", observedValue);
    }

    private static void requireNonBlank(String value, String field) {
      if (value == null || value.isBlank()) {
        throw new IllegalArgumentException(field + " must not be blank");
      }
    }
  }

  record Result(ObjectNode report, int passed, List<Fact> facts) {
    Result {
      report = report.deepCopy();
      facts = List.copyOf(facts);
    }

    @Override
    public ObjectNode report() {
      return report.deepCopy();
    }
  }
}
