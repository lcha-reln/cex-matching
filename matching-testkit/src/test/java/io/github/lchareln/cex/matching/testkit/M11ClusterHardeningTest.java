package io.github.lchareln.cex.matching.testkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

final class M11ClusterHardeningTest {
  private final Path root = Path.of(System.getProperty("matching.repositoryRoot"));

  @Test
  void generatedCorpusUsesTheExactFrozenScheduleAndContinuousNewOrdinals() {
    M11GeneratedSuite.Corpus corpus = M11GeneratedSuite.generate();
    JsonNode workload = JsonSupport.parse(read(root.resolve(M11StartCheckRunner.WORKLOAD_PATH)));
    List<String> frozenSchedule =
        java.util.stream.StreamSupport.stream(
                workload.path("generatedDifferential").path("segmentSchedule").spliterator(), false)
            .map(JsonNode::stringValue)
            .toList();

    assertEquals(frozenSchedule, corpus.segmentSchedule());
    assertEquals(4096, corpus.actions().size());
    assertEquals(
        1536,
        corpus.actions().subList(0, 2048).stream()
            .filter(action -> action.expected() == M11GeneratedSuite.Expected.NEW)
            .count());
    assertEquals(
        512,
        corpus.actions().subList(0, 2048).stream()
            .filter(action -> action.expected() == M11GeneratedSuite.Expected.DUPLICATE)
            .count());
    assertEquals(
        LongStream.rangeClosed(1, 2048).boxed().toList(),
        corpus.actions().stream()
            .filter(action -> action.expected() == M11GeneratedSuite.Expected.NEW)
            .map(M11GeneratedSuite.Action::newOrdinal)
            .toList());
    M11GeneratedSuite.Action firstAfterCut = corpus.actions().get(2048);
    assertEquals(M11GeneratedSuite.Lane.PREVIOUS_NEW, firstAfterCut.lane());
    assertEquals(M11GeneratedSuite.Expected.NEW, firstAfterCut.expected());
    assertEquals(1537, firstAfterCut.newOrdinal());
    assertEquals(1537, firstAfterCut.request().slot().producerSequence());
    assertEquals(
        512,
        corpus.actions().stream().filter(M11GeneratedSuite.Action::crossSnapshotDuplicate).count());
    assertTrue(
        corpus.actions().stream()
            .filter(M11GeneratedSuite.Action::crossSnapshotDuplicate)
            .allMatch(action -> action.sourceActionIndex() < 2048));

    M11GeneratedSuite.DirectRun direct = M11GeneratedSuite.runDirect(corpus);
    assertTrue(direct.newOrdinalsContinuous());
    assertEquals(1024, direct.duplicateInvariantChecks());
    assertEquals(1024, direct.conflictInvariantChecks());
    assertEquals(2049, direct.finalState().nextApplicationSequence());
  }

  @Test
  void protocolHashProbesCoverEveryOuterIdentityFieldAndPayloadMutations() {
    JsonNode report = new M11ProtocolSuite().run(root).report();
    assertEquals(8, report.path("payloadHashOuterFieldProbes").intValue());
    assertEquals(10, report.path("payloadHashPayloadMutationProbes").intValue());
    assertTrue(report.path("payloadHashOuterInvariant").booleanValue());
    assertTrue(report.path("payloadHashPayloadSensitive").booleanValue());
  }

  @Test
  void productionCallbackGraphAndPinnedDependenciesAreExecutedFacts() {
    JsonNode report = new M11ArchitectureGate().run(root);
    assertEquals("1.52.2", report.path("configuredAeronVersion").stringValue());
    assertEquals("2.5.0", report.path("configuredAgronaVersion").stringValue());
    assertTrue(report.path("versionConfigurationExact").booleanValue());
    assertEquals(
        report.path("abstractProductionCallbacks").intValue(),
        report.path("implementedProductionCallbacks").intValue());
    assertEquals(1, report.path("businessApplyCalls").intValue());
    assertEquals(1, report.path("logCallbackBusinessApplyCalls").intValue());
    assertEquals(0, report.path("nonLogCallbackBusinessApplyCalls").intValue());
    assertEquals(0, report.path("egressStateInputViolations").intValue());
    assertEquals(0, report.path("standaloneWalWrites").intValue());
    assertTrue(report.path("runtimeMetadataSpyExecuted").booleanValue());
    assertTrue(report.path("runtimeMetadataDigestStable").booleanValue());
  }

  private static byte[] read(Path path) {
    try {
      return java.nio.file.Files.readAllBytes(path);
    } catch (java.io.IOException failure) {
      throw new IllegalStateException("cannot read " + path, failure);
    }
  }
}
