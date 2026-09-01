package io.github.lchareln.cex.matching.testkit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.lchareln.cex.matching.local.CheckpointResult;
import io.github.lchareln.cex.matching.local.M09RuntimeJudgeProbe;
import io.github.lchareln.cex.matching.local.RecoveryException;
import io.github.lchareln.cex.matching.local.RuntimeState;
import io.github.lchareln.cex.matching.local.SnapshotAnchor;
import io.github.lchareln.cex.matching.local.SubmissionResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class M09CompletionSuitesTest {
  @TempDir Path temporary;

  @Test
  void executesTheTwentyTwoFrozenScenariosAndThirtyTwoObligations() {
    M09Corpus corpus = M09Corpus.load(root());
    M09FixedSuite.Result result = new M09FixedSuite().run(corpus, temporary.resolve("fixed"));
    assertEquals(22, result.scenarios().size());
    assertEquals(32, result.coverage().observed());
    result.scenarios().forEach(node -> assertEquals("PASS", node.path("status").stringValue()));
  }

  @Test
  void regeneratesNinetySixByFortyHistoriesByteExactly() {
    M09Corpus corpus = M09Corpus.load(root());
    M09GeneratedSuite.Result first =
        new M09GeneratedSuite().run(corpus, temporary.resolve("generated-a"));
    M09GeneratedSuite.Result second =
        new M09GeneratedSuite().run(corpus, temporary.resolve("generated-b"));
    assertEquals(96, first.historyPlans().size());
    assertEquals(3_840, first.operations());
    assertEquals(3_840, first.comparisons());
    assertEquals(4, first.laneCounts().size());
    assertArrayEquals(first.canonicalBytes(), second.canonicalBytes());
    assertEquals(first.digest(), second.digest());
  }

  @Test
  void executesAllEightDeclaredPreOperationFailureSeamsWithoutOverclaimingIoOrder() {
    M09OperationFailureSuite.Result result =
        new M09OperationFailureSuite()
            .run(M09Corpus.load(root()), temporary.resolve("failure-seams"));
    assertEquals(8, result.observed());
    result
        .failures()
        .forEach(
            node -> {
              assertEquals(
                  true, node.path("faultInjectedAtDeclaredPreOperationHook").booleanValue());
              assertEquals(false, node.path("underlyingOperationExecutionClaim").booleanValue());
              assertEquals(false, node.path("actualFilesystemFailure").booleanValue());
            });
  }

  @Test
  void haltsSevenChildProcessesAtTheFrozenWindows() {
    M09CrashSmoke.Result result =
        new M09CrashSmoke().run(M09Corpus.load(root()), temporary.resolve("child-halt"));
    assertEquals(7, result.observed());
    result
        .windows()
        .forEach(
            node -> {
              assertEquals(86, node.path("exitCode").intValue());
              assertEquals(true, node.path("runtimeHalt").booleanValue());
              assertEquals(false, node.path("powerLossProof").booleanValue());
              assertEquals(
                  true, node.path("haltAtDeclaredHookAndNamespaceObserved").booleanValue());
              assertEquals(false, node.path("underlyingOperationOrderClaim").booleanValue());
              assertEquals(false, node.path("physicalDurabilityClaim").booleanValue());
              assertTrue(node.path("inventoryAtHalt").isArray());
              assertEquals(
                  node.path("expectedInventoryAtHalt").path("canonicalSnapshotFiles").intValue(),
                  node.path("canonicalSnapshotFilesAtHalt").intValue());
            });
  }

  @Test
  void classifiesEveryChildHarnessCounterfactualAsSystemError() {
    assertHarnessSystemError(
        () ->
            M09CrashSmoke.validateHarnessObservation(
                1, true, "WINDOW", "WINDOW", "HOOK", "HOOK", 1, 1));
    assertHarnessSystemError(
        () ->
            M09CrashSmoke.validateHarnessObservation(
                86, false, "WINDOW", "WINDOW", "HOOK", "HOOK", 1, 1));
    assertHarnessSystemError(
        () ->
            M09CrashSmoke.validateHarnessObservation(
                86, true, "WINDOW", "WRONG", "HOOK", "HOOK", 1, 1));
    assertHarnessSystemError(
        () ->
            M09CrashSmoke.validateHarnessObservation(
                86, true, "WINDOW", "WINDOW", "HOOK", "WRONG", 1, 1));
    assertHarnessSystemError(
        () ->
            M09CrashSmoke.validateHarnessObservation(
                86, true, "WINDOW", "WINDOW", "HOOK", "HOOK", 1, 2));
    assertHarnessSystemError(() -> M09CrashSmoke.validateObservedCount(6, 7));
    assertEquals(
        M09CheckRunner.STUDENT_FAILURE,
        M09CheckRunner.classifyFailure(new M09SemanticFailure("post-halt semantic mismatch")));
    M09SemanticFailure inventoryMismatch =
        assertThrows(
            M09SemanticFailure.class,
            () -> M09CrashSmoke.validateInventoryCounts(1, 0, 1, 0, 1, 1, "WINDOW"));
    assertEquals(M09CheckRunner.STUDENT_FAILURE, M09CheckRunner.classifyFailure(inventoryMismatch));
  }

  @Test
  void killsTwelveExecutableMutantsAndExcludesSystemErrors() {
    M09MutantSuite.Result result = new M09MutantSuite().run(M09Corpus.load(root()), root());
    assertEquals(12, result.killed());
    assertEquals(12, result.mutants().size());
    assertEquals("SYSTEM_ERROR", result.throwingControl());
    assertTrue(result.actualMutationActions() >= 12);
    assertEquals(
        M09CheckRunner.SYSTEM_ERROR,
        M09CheckRunner.classifyFailure(
            new IllegalStateException("deterministic mutant throwing control")));
    result
        .mutants()
        .forEach(
            node -> {
              assertEquals("STUDENT_FAILURE", node.path("classification").stringValue());
              assertEquals(false, node.path("systemErrorCountedAsKill").booleanValue());
            });
  }

  @Test
  void deletingAMiddleRealOperationContinuesAsInvalidHistoryWithoutAFalseKill() {
    M09MutantSuite suite = new M09MutantSuite();
    String id = "M09-SNAPSHOT-DROPS-RESTING-ORDER";
    List<String> incomplete = new ArrayList<>(suite.requiredGrammarForAudit(id));
    incomplete.remove(3);
    M09MutantSuite.AuditOutcome outcome = suite.executeForAudit(id, incomplete, true);
    assertEquals("INVALID_HISTORY", outcome.classification());
    assertEquals("", outcome.fingerprint());
    assertEquals(incomplete.size(), outcome.interpretedOperations());
  }

  @Test
  void mutantRecoveryKillsAcceptOnlyTheExactExpectedCause() {
    M09MutantSuite.requireIdentityDropRecovery(
        new RecoveryException(
            "M09S1 state restore failed",
            new IllegalArgumentException(
                "identity bindings are not a contiguous durable history")));
    M09MutantSuite.requireCutReplayRecovery(
        new RecoveryException("durable M08C1 identities are not a new contiguous stream"));

    IllegalStateException identityFailure =
        assertThrows(
            IllegalStateException.class,
            () ->
                M09MutantSuite.requireIdentityDropRecovery(
                    new RecoveryException(
                        "M09S1 state restore failed",
                        new IllegalArgumentException("unrelated restore failure"))));
    IllegalStateException cutFailure =
        assertThrows(
            IllegalStateException.class,
            () ->
                M09MutantSuite.requireCutReplayRecovery(
                    new RecoveryException(
                        "durable M08C1 identities are not a new contiguous stream",
                        new IllegalStateException("unrelated replay failure"))));
    assertEquals(M09CheckRunner.SYSTEM_ERROR, M09CheckRunner.classifyFailure(identityFailure));
    assertEquals(M09CheckRunner.SYSTEM_ERROR, M09CheckRunner.classifyFailure(cutFailure));
  }

  @Test
  void recoveryBudgetWitnessAcceptsOnlyTheExactBudgetFailure() {
    M09FixedSuite.requireBudgetRecoveryFailure(
        new RecoveryException(
            "M09 recovery suffix exceeds the configured records-and-bytes budget before WAL 65"),
        65);
    IllegalStateException wrongSequence =
        assertThrows(
            IllegalStateException.class,
            () ->
                M09FixedSuite.requireBudgetRecoveryFailure(
                    new RecoveryException(
                        "M09 recovery suffix exceeds the configured records-and-bytes budget before WAL 64"),
                    65));
    IllegalStateException wrongCause =
        assertThrows(
            IllegalStateException.class,
            () ->
                M09FixedSuite.requireBudgetRecoveryFailure(
                    new RecoveryException(
                        "M09 recovery suffix exceeds the configured records-and-bytes budget before WAL 65",
                        new IllegalStateException("unrelated parser failure")),
                    65));
    assertEquals(M09CheckRunner.SYSTEM_ERROR, M09CheckRunner.classifyFailure(wrongSequence));
    assertEquals(M09CheckRunner.SYSTEM_ERROR, M09CheckRunner.classifyFailure(wrongCause));
  }

  @Test
  void injectedIoBoundariesRejectAnUnrelatedIOExceptionAsSystemError() {
    IOException injected = new IOException("injected");
    M09ScenarioSupport.requireExactInjectedIOException(
        injected, injected, "counterfactual exact I/O seam");
    IllegalStateException unrelated =
        assertThrows(
            IllegalStateException.class,
            () ->
                M09ScenarioSupport.requireExactInjectedIOException(
                    new IOException("unrelated"), injected, "counterfactual exact I/O seam"));
    assertEquals(M09CheckRunner.SYSTEM_ERROR, M09CheckRunner.classifyFailure(unrelated));
    IllegalStateException missedHook =
        assertThrows(
            IllegalStateException.class,
            () -> M09ScenarioSupport.requireSystemBoundary(false, "expected hook was not reached"));
    assertEquals(M09CheckRunner.SYSTEM_ERROR, M09CheckRunner.classifyFailure(missedHook));
  }

  @Test
  void storageLedgerRejectsUnknownFilesAndWrongSnapshotGenerationCutNames() throws Exception {
    Path runtime = Files.createDirectories(temporary.resolve("inventory-counterfactual"));
    Files.write(runtime.resolve(".m08w1.lock"), new byte[0]);
    Files.write(
        runtime.resolve("segment-00000000000000000001.m08w1"),
        new byte[M09StorageLedger.WAL_HEADER_BYTES]);
    M09StorageLedger ledger = new M09StorageLedger(4 * 1024 * 1024L, 64, 1_048_576);
    M09FileInventory inventory = new M09FileInventory();
    ledger.verifyInventory(inventory.inspect(runtime));

    Files.writeString(runtime.resolve("unexpected.regular"), "not-runtime-state");
    assertThrows(
        M09SemanticFailure.class, () -> ledger.verifyInventory(inventory.inspect(runtime)));
    Files.delete(runtime.resolve("unexpected.regular"));

    Path unexpectedDirectory = Files.createDirectory(runtime.resolve("unexpected-directory"));
    assertThrows(
        M09SemanticFailure.class, () -> ledger.verifyInventory(inventory.inspect(runtime)));
    Files.delete(unexpectedDirectory);
    Path unexpectedLink = runtime.resolve("unexpected-link");
    Files.createSymbolicLink(unexpectedLink, Path.of("segment-00000000000000000001.m08w1"));
    assertThrows(
        M09SemanticFailure.class, () -> ledger.verifyInventory(inventory.inspect(runtime)));
    Files.delete(unexpectedLink);

    ledger.observeCheckpoint(
        new CheckpointResult(new SnapshotAnchor(1, M09ScenarioSupport.SHARD, 0, 0), 0));
    Files.write(
        runtime.resolve("snapshot-00000000000000000001-00000000000000000001.m09s1"),
        new byte[] {1});
    assertThrows(
        M09SemanticFailure.class, () -> ledger.verifyInventory(inventory.inspect(runtime)));
  }

  @Test
  void enforcesTheInheritedArchitectureAndTestkitProbeBoundary() {
    M09ArchitectureGate.Report report = new M09ArchitectureGate().verify(root());
    assertTrue(report.passed(), () -> report.violations().toString());
    assertTrue(report.testkitProbePresent());
    assertTrue(report.storageOperationsProductionWiringVerified());
    assertTrue(report.independentLedgerProductionParserFree());
  }

  @Test
  void architectureGateRejectsRemovedProductionStorageOperationWiring() throws Exception {
    Path local =
        root()
            .resolve("matching-local-runtime/src/main/java/io/github/lchareln/cex/matching/local");
    String snapshot = Files.readString(local.resolve("SnapshotStore.java"));
    String wal = Files.readString(local.resolve("SegmentedWal.java"));
    String runtime = Files.readString(local.resolve("LocalMatchingRuntime.java"));
    String operations =
        Files.readString(local.resolve("StorageOperations.java"))
            + '\n'
            + Files.readString(local.resolve("JdkStorageOperations.java"));
    assertTrue(
        M09ArchitectureGate.storageOperationWiringViolations(snapshot, wal, runtime, operations)
            .isEmpty());
    assertTrue(
        !M09ArchitectureGate.storageOperationWiringViolations(
                snapshot, wal, runtime, operations.replace("channel.force(true);", ""))
            .isEmpty());
  }

  @Test
  void architectureGateRejectsLedgerUseOfProductionParserCodecStateOrIo() throws Exception {
    Path ledger =
        root()
            .resolve(
                "matching-testkit/src/main/java/io/github/lchareln/cex/matching/testkit/M09StorageLedger.java");
    String source = Files.readString(ledger);
    assertTrue(M09ArchitectureGate.ledgerIndependenceViolations(source).isEmpty());
    assertTrue(
        !M09ArchitectureGate.ledgerIndependenceViolations(
                source
                    + "\nimport io.github.lchareln.cex.matching.local.M09SnapshotCodec;\n"
                    + "import io.github.lchareln.cex.matching.MatchingStateImage;\n")
            .isEmpty());
    assertTrue(
        !M09ArchitectureGate.ledgerIndependenceViolations(
                source + "\nimport java.nio.file.Files;\n")
            .isEmpty());
    assertTrue(
        !M09ArchitectureGate.ledgerIndependenceViolations(
                source + "\nimport io.github.lchareln.cex.matching.local.*;\n")
            .isEmpty());
  }

  @Test
  void realStorageOperationTraceRejectsRemovedOrReorderedForceMoveAndDelete() {
    List<M09RuntimeJudgeProbe.StorageOperationObservation> publication =
        List.of(
            storage("FORCE_FILE", "/runtime/snapshot-01-01.m09s1.tmp", ""),
            storage(
                "ATOMIC_MOVE",
                "/runtime/snapshot-01-01.m09s1.tmp",
                "/runtime/snapshot-01-01.m09s1"),
            storage("FORCE_DIRECTORY", "/runtime", ""));
    M09FixedSuite.requirePublicationStorageOperations(publication);
    assertThrows(
        M09SemanticFailure.class,
        () -> M09FixedSuite.requirePublicationStorageOperations(publication.subList(1, 3)));
    assertThrows(
        M09SemanticFailure.class,
        () ->
            M09FixedSuite.requirePublicationStorageOperations(
                List.of(publication.get(1), publication.get(0), publication.get(2))));

    List<M09RuntimeJudgeProbe.StorageOperationObservation> retirement =
        new ArrayList<>(publication);
    retirement.add(storage("DELETE", "/runtime/segment-00000000000000000001.m08w1", ""));
    retirement.add(storage("FORCE_DIRECTORY", "/runtime", ""));
    M09FixedSuite.requireRetirementStorageOperations(retirement);
    retirement.removeLast();
    assertThrows(
        M09SemanticFailure.class,
        () -> M09FixedSuite.requireRetirementStorageOperations(retirement));
  }

  @Test
  void forcedUnknownHarnessRejectsWrongDetailAndRuntimeStateAsSystemError() {
    String detail = "IOException: injected M09 generated boundary BEFORE_LIVE_APPLY";
    SubmissionResult.DurabilityUnknown unknown =
        new SubmissionResult.DurabilityUnknown(Optional.empty(), "APPLY_OR_ACK", detail);
    SubmissionResult.FailedClosed blocked =
        new SubmissionResult.FailedClosed("APPLY_OR_ACK: " + detail);
    M09FileInventory.Inventory inventory = new M09FileInventory.Inventory(List.of());
    M09DualRuntimeHarness.requireForcedUnknownHarness(
        unknown, RuntimeState.FAILED_CLOSED, blocked, detail, 2, 2, inventory, inventory);

    IllegalStateException wrongDetail =
        assertThrows(
            IllegalStateException.class,
            () ->
                M09DualRuntimeHarness.requireForcedUnknownHarness(
                    unknown,
                    RuntimeState.FAILED_CLOSED,
                    blocked,
                    detail + "-wrong",
                    2,
                    2,
                    inventory,
                    inventory));
    IllegalStateException wrongState =
        assertThrows(
            IllegalStateException.class,
            () ->
                M09DualRuntimeHarness.requireForcedUnknownHarness(
                    unknown, RuntimeState.OPEN, blocked, detail, 2, 2, inventory, inventory));
    assertEquals(M09CheckRunner.SYSTEM_ERROR, M09CheckRunner.classifyFailure(wrongDetail));
    assertEquals(M09CheckRunner.SYSTEM_ERROR, M09CheckRunner.classifyFailure(wrongState));
  }

  private static M09RuntimeJudgeProbe.StorageOperationObservation storage(
      String kind, String path, String target) {
    return new M09RuntimeJudgeProbe.StorageOperationObservation(kind, path, target);
  }

  private static void assertHarnessSystemError(org.junit.jupiter.api.function.Executable action) {
    RuntimeException failure = assertThrows(IllegalStateException.class, action);
    assertEquals(M09CheckRunner.SYSTEM_ERROR, M09CheckRunner.classifyFailure(failure));
  }

  private static Path root() {
    return Path.of(System.getProperty("matching.repositoryRoot")).toAbsolutePath().normalize();
  }
}
