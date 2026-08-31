package io.github.lchareln.cex.matching.testkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.lchareln.cex.matching.local.M08RuntimeJudgeProbe;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class M08CompletionSuitesTest {
  @TempDir Path temporaryDirectory;

  @Test
  void generatedSuiteUsesARealIndependentDurabilityLedger() {
    M08GeneratedSuite.Result result =
        new M08GeneratedSuite().run(temporaryDirectory.resolve("generated"));
    assertEquals(96, result.histories());
    assertEquals(4_608, result.operations());
    assertEquals(4_608, result.ledgerChecks());
    assertEquals(result.ledgerAppends(), result.ledgerRecordForces());
    assertEquals(result.ledgerRecordForces(), result.ledgerApplies());
    assertTrue(result.restartLedgerChecks() > 0);
    assertTrue(result.ledgerDirectoryForces() >= 96);
    assertEquals(192, result.invalidEnvelopes());
    assertEquals(576, result.businessRejections());
    assertEquals(
        java.util.Set.of("SUBMIT", "DUPLICATE", "CONFLICT", "RESTART", "ROLLOVER", "FAULT"),
        result.selectedOperations().keySet());
    assertTrue(result.selectedOperations().values().stream().allMatch(count -> count > 0));
    assertEquals(
        1_152, result.selectedOperations().values().stream().mapToInt(Integer::intValue).sum());
  }

  @Test
  void semanticMutantsExecuteFreshRuntimeAndFileHistories() {
    Path repositoryRoot = Path.of(System.getProperty("matching.repositoryRoot"));
    M08MutantSuite.Result result = new M08MutantSuite().run(repositoryRoot);
    assertEquals(10, result.killed());
    assertEquals("SYSTEM_ERROR", result.throwingControl());
    assertTrue(result.minimalOperations() > 0);
    assertTrue(result.actualMutationActions() >= 10);
    result
        .counterexamples()
        .path("counterexamples")
        .forEach(
            counterexample -> {
              assertEquals(
                  "EXECUTABLE_MUTATED_RUNTIME",
                  counterexample.path("candidateDriver").stringValue());
              assertTrue(counterexample.path("fullRestartGrammar").booleanValue());
              assertTrue(counterexample.path("actualMutationActions").intValue() > 0);
            });
  }

  @Test
  void beforeOperationFailuresAreExecutedAndTypedHonestly() {
    M08OperationFailureSuite.Result result =
        new M08OperationFailureSuite().run(temporaryDirectory.resolve("operation-failures"));
    assertEquals(7, result.operationFailures());
    assertTrue(
        StreamSupport.stream(result.failures().spliterator(), false)
            .anyMatch(
                failure ->
                    "INJECTED_ENOSPC".equals(failure.path("injectedFaultKind").stringValue())));
    assertTrue(
        StreamSupport.stream(result.failures().spliterator(), false)
            .anyMatch(
                failure ->
                    "INJECTED_READ_ONLY".equals(failure.path("injectedFaultKind").stringValue())));
    result
        .failures()
        .forEach(
            failure -> {
              assertTrue(!failure.path("operationExecuted").booleanValue());
              assertTrue(!failure.path("actualFilesystem").booleanValue());
              assertTrue(!failure.path("ackReturned").booleanValue());
            });
  }

  @Test
  void threeWayClassifierDoesNotTurnInfrastructureErrorsIntoStudentFailures() {
    assertEquals(
        M08CheckRunner.STUDENT_FAILURE,
        M08CheckRunner.classifyFailure(new M08SemanticFailure("candidate mismatch")));
    assertEquals(
        M08CheckRunner.SYSTEM_ERROR,
        M08CheckRunner.classifyFailure(
            new IllegalStateException("file-control failure", new IOException("injected"))));
  }

  @Test
  void candidateSemanticSeamsUseTheStudentFailureMarker() throws Exception {
    for (Class<?> seam :
        List.of(
            M08OperationFailureSuite.class,
            M08FixedSuite.class,
            M08GeneratedSuite.class,
            M08RuntimeJudgeProbe.class)) {
      Method require = seam.getDeclaredMethod("require", boolean.class, String.class);
      require.setAccessible(true);
      InvocationTargetException invocation =
          assertThrows(
              InvocationTargetException.class,
              () -> require.invoke(null, false, "candidate semantic mismatch"),
              seam.getSimpleName());
      RuntimeException failure =
          assertInstanceOf(RuntimeException.class, invocation.getCause(), seam.getSimpleName());
      assertInstanceOf(M08SemanticFailure.class, failure, seam.getSimpleName());
      assertEquals(
          M08CheckRunner.STUDENT_FAILURE,
          M08CheckRunner.classifyFailure(failure),
          seam.getSimpleName());
    }
  }

  @Test
  void childRuntimeHaltSmokeReopensPreprovisionedWalAndChecksDigests() {
    M08CrashSmoke.Result result = new M08CrashSmoke().run(temporaryDirectory.resolve("child-halt"));
    assertEquals(3, result.processCrashes());
    result
        .windows()
        .forEach(
            window -> {
              assertTrue(window.path("semanticDigestChecked").booleanValue());
              assertTrue(window.path("preprovisionedWalDirectory").booleanValue());
              assertTrue(window.path("ancestorDirectoryDurabilityExternal").booleanValue());
              assertTrue(!window.path("fileDigestBeforeReopen").stringValue().isBlank());
              assertTrue(!window.path("fileDigestAfterRetry").stringValue().isBlank());
              assertTrue(!window.path("powerLossProof").booleanValue());
            });
  }
}
