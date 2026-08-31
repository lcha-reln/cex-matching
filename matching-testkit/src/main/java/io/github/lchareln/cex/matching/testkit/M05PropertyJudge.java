package io.github.lchareln.cex.matching.testkit;

import io.github.lchareln.cex.matching.reference.M05SemanticOutcome;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Fresh-state exact differential plus independent invariant judge for M05 histories. */
final class M05PropertyJudge {
  static final String PASS = "PASS";
  static final String STUDENT_FAILURE = "STUDENT_FAILURE";
  static final String SYSTEM_ERROR = "SYSTEM_ERROR";

  Observation judge(M05GeneratedHistory history, M05Candidate.Factory factory) {
    return judge(
        "history-" + history.historyIndex(), history.seedHex(), history.commands(), factory);
  }

  Observation judge(
      String historyId, String seed, List<M05Command> commands, M05Candidate.Factory factory) {
    List<M05Command> immutable = List.copyOf(commands);
    List<Step> trace = new ArrayList<>();
    int comparisons = 0;
    int ledgerChecks = 0;
    int bookChecks = 0;
    int marketControlChecks = 0;
    M05Candidate expected;
    M05Candidate actual;
    M05EventLedger ledger;
    try {
      expected = new M05ReferenceCandidate();
      actual = Objects.requireNonNull(factory.create(), "candidate");
      ledger = new M05EventLedger();
    } catch (RuntimeException failure) {
      return system(trace, comparisons, ledgerChecks, bookChecks, marketControlChecks, failure);
    }
    for (int index = 0; index < immutable.size(); index++) {
      M05Command command = immutable.get(index);
      M05SemanticOutcome expectedOutcome;
      M05SemanticOutcome actualOutcome;
      try {
        expectedOutcome = expected.apply(command);
        actualOutcome = actual.apply(command);
      } catch (RuntimeException failure) {
        return system(trace, comparisons, ledgerChecks, bookChecks, marketControlChecks, failure);
      }
      trace.add(new Step(command, expectedOutcome, actualOutcome));
      try {
        ledger.verifyAndApply(command, actualOutcome);
        ledgerChecks++;
        bookChecks++;
        marketControlChecks++;
        if (!expectedOutcome.equals(actualOutcome)) {
          throw new PropertyFailure(
              "EXACT_COMMAND_DIFFERENTIAL",
              divergence(expectedOutcome, actualOutcome),
              "candidate outcome differs from the independent reference model");
        }
        comparisons++;
      } catch (M05EventLedger.LedgerFailure failure) {
        return student(
            historyId,
            seed,
            trace,
            comparisons,
            ledgerChecks,
            bookChecks,
            marketControlChecks,
            new PropertyFailure(
                failure.propertyId(), failure.divergenceKind(), failure.getMessage()));
      } catch (PropertyFailure failure) {
        return student(
            historyId,
            seed,
            trace,
            comparisons,
            ledgerChecks,
            bookChecks,
            marketControlChecks,
            failure);
      } catch (RuntimeException failure) {
        return system(trace, comparisons, ledgerChecks, bookChecks, marketControlChecks, failure);
      }
    }
    return new Observation(
        PASS,
        immutable.size(),
        comparisons,
        ledgerChecks,
        bookChecks,
        marketControlChecks,
        null,
        trace,
        "all M05 properties passed");
  }

  private static String divergence(M05SemanticOutcome expected, M05SemanticOutcome actual) {
    if (!expected.applicationSequence().equals(actual.applicationSequence())) {
      return "APPLICATION_SEQUENCE";
    }
    if (!expected.events().equals(actual.events())) {
      return "EVENT_BATCH";
    }
    if (!expected.stateAfter().activeRuleSet().equals(actual.stateAfter().activeRuleSet())
        || !expected.stateAfter().preparedRuleSet().equals(actual.stateAfter().preparedRuleSet())
        || !expected
            .stateAfter()
            .lastActivationFence()
            .equals(actual.stateAfter().lastActivationFence())) {
      return "MARKET_CONTROL_STATE";
    }
    return "BOOK_OR_SEQUENCE_STATE";
  }

  private static Observation student(
      String historyId,
      String seed,
      List<Step> trace,
      int comparisons,
      int ledgerChecks,
      int bookChecks,
      int marketControlChecks,
      PropertyFailure failure) {
    int commandIndex = Math.max(0, trace.size() - 1);
    Step step = trace.isEmpty() ? null : trace.getLast();
    Failure detail =
        new Failure(
            historyId,
            seed,
            commandIndex,
            failure.propertyId(),
            failure.divergenceKind(),
            step == null ? null : step.command(),
            step == null ? null : step.expected(),
            step == null ? null : step.actual(),
            failure.getMessage());
    return new Observation(
        STUDENT_FAILURE,
        commandIndex,
        comparisons,
        ledgerChecks,
        bookChecks,
        marketControlChecks,
        detail,
        trace,
        failure.getMessage());
  }

  private static Observation system(
      List<Step> trace,
      int comparisons,
      int ledgerChecks,
      int bookChecks,
      int marketControlChecks,
      RuntimeException failure) {
    String message = failure.getMessage();
    String stable =
        failure.getClass().getSimpleName()
            + (message == null || message.isBlank() ? "" : ": " + message);
    return new Observation(
        SYSTEM_ERROR,
        Math.max(0, trace.size() - 1),
        comparisons,
        ledgerChecks,
        bookChecks,
        marketControlChecks,
        null,
        trace,
        stable);
  }

  record Observation(
      String classification,
      int completedCommands,
      int differentialComparisons,
      int ledgerChecks,
      int bookChecks,
      int marketControlChecks,
      Failure failure,
      List<Step> trace,
      String message) {
    Observation {
      trace = List.copyOf(trace);
    }
  }

  record Failure(
      String historyId,
      String seed,
      int commandIndex,
      String propertyId,
      String divergenceKind,
      M05Command command,
      M05SemanticOutcome expected,
      M05SemanticOutcome actual,
      String message) {
    String fingerprint() {
      return propertyId + "/" + divergenceKind;
    }
  }

  record Step(M05Command command, M05SemanticOutcome expected, M05SemanticOutcome actual) {}

  private static final class PropertyFailure extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final String propertyId;
    private final String divergenceKind;

    PropertyFailure(String propertyId, String divergenceKind, String message) {
      super(message);
      this.propertyId = propertyId;
      this.divergenceKind = divergenceKind;
    }

    String propertyId() {
      return propertyId;
    }

    String divergenceKind() {
      return divergenceKind;
    }
  }
}
