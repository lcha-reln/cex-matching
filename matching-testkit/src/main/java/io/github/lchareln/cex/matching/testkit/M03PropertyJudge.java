package io.github.lchareln.cex.matching.testkit;

import io.github.lchareln.cex.matching.reference.LinearReferenceModel;
import io.github.lchareln.cex.matching.reference.ReferenceCommand;
import io.github.lchareln.cex.matching.reference.ReferenceMatcher;
import io.github.lchareln.cex.matching.reference.SemanticOutcome;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Fresh-state differential and independent property judge for one generated M03 history. */
final class M03PropertyJudge {
  static final String PASS = "PASS";
  static final String STUDENT_FAILURE = "STUDENT_FAILURE";
  static final String SYSTEM_ERROR = "SYSTEM_ERROR";

  Observation judge(M03GeneratedHistory history, M03Candidate.Factory candidateFactory) {
    Objects.requireNonNull(history, "history");
    return judge(
        "history-" + history.historyIndex(),
        history.seedHex(),
        history.commands(),
        candidateFactory);
  }

  Observation judge(
      String historyId,
      String seed,
      List<ReferenceCommand> commands,
      M03Candidate.Factory candidateFactory) {
    Objects.requireNonNull(historyId, "historyId");
    Objects.requireNonNull(seed, "seed");
    List<ReferenceCommand> immutableCommands = List.copyOf(commands);
    Objects.requireNonNull(candidateFactory, "candidateFactory");

    List<Step> trace = new ArrayList<>();
    int differentialComparisons = 0;
    int ledgerChecks = 0;
    int bookChecks = 0;

    M03Candidate candidate;
    ReferenceMatcher reference;
    M03EventLedger ledger;
    try {
      candidate = Objects.requireNonNull(candidateFactory.create(), "candidate");
      reference = new LinearReferenceModel();
      ledger = new M03EventLedger();
    } catch (RuntimeException failure) {
      return systemObservation(trace, differentialComparisons, ledgerChecks, bookChecks, failure);
    }

    for (int index = 0; index < immutableCommands.size(); index++) {
      ReferenceCommand command = immutableCommands.get(index);
      SemanticOutcome expected;
      try {
        expected = Objects.requireNonNull(reference.apply(command), "reference outcome");
      } catch (RuntimeException failure) {
        return systemObservation(trace, differentialComparisons, ledgerChecks, bookChecks, failure);
      }
      SemanticOutcome actual;
      try {
        actual = Objects.requireNonNull(candidate.apply(command), "candidate outcome");
      } catch (RuntimeException failure) {
        return systemObservation(trace, differentialComparisons, ledgerChecks, bookChecks, failure);
      }
      trace.add(new Step(command, expected, actual));

      try {
        ledger.verifyAndApply(command, actual);
        ledgerChecks++;
        bookChecks++;
        if (!expected.events().equals(actual.events())) {
          throw new PropertyFailure(
              "EXACT_BATCH_DIFFERENTIAL", "EVENT_BATCH", "candidate events differ from reference");
        }
        if (!expected.bookAfter().equals(actual.bookAfter())) {
          throw new PropertyFailure(
              "EXACT_BATCH_DIFFERENTIAL", "BOOK_AFTER", "candidate book differs from reference");
        }
        differentialComparisons++;
      } catch (PropertyFailure failure) {
        return studentObservation(
            historyId, seed, trace, differentialComparisons, ledgerChecks, bookChecks, failure);
      } catch (RuntimeException failure) {
        return systemObservation(trace, differentialComparisons, ledgerChecks, bookChecks, failure);
      }
    }
    return new Observation(
        PASS,
        immutableCommands.size(),
        differentialComparisons,
        ledgerChecks,
        bookChecks,
        null,
        trace,
        "all generated properties passed");
  }

  private static Observation studentObservation(
      String historyId,
      String seed,
      List<Step> trace,
      int differentialComparisons,
      int ledgerChecks,
      int bookChecks,
      PropertyFailure failure) {
    int commandIndex = Math.max(0, trace.size() - 1);
    Step failedStep = trace.isEmpty() ? null : trace.getLast();
    Failure detail =
        new Failure(
            historyId,
            seed,
            commandIndex,
            failure.propertyId(),
            failure.divergenceKind(),
            failedStep == null ? null : failedStep.command(),
            failedStep == null ? null : failedStep.expected(),
            failedStep == null ? null : failedStep.actual(),
            failure.getMessage());
    return new Observation(
        STUDENT_FAILURE,
        commandIndex,
        differentialComparisons,
        ledgerChecks,
        bookChecks,
        detail,
        trace,
        failure.getMessage());
  }

  private static Observation systemObservation(
      List<Step> trace,
      int differentialComparisons,
      int ledgerChecks,
      int bookChecks,
      RuntimeException failure) {
    return new Observation(
        SYSTEM_ERROR,
        Math.max(0, trace.size() - 1),
        differentialComparisons,
        ledgerChecks,
        bookChecks,
        null,
        trace,
        stableSystemMessage(failure));
  }

  private static String stableSystemMessage(RuntimeException exception) {
    String message = exception.getMessage();
    return exception.getClass().getSimpleName()
        + (message == null || message.isBlank() ? "" : ": " + message);
  }

  record Observation(
      String classification,
      int completedCommands,
      int differentialComparisons,
      int ledgerChecks,
      int bookChecks,
      Failure failure,
      List<Step> trace,
      String message) {
    Observation {
      trace = List.copyOf(trace);
      Objects.requireNonNull(classification, "classification");
      Objects.requireNonNull(message, "message");
    }
  }

  record Failure(
      String historyId,
      String seed,
      int commandIndex,
      String propertyId,
      String divergenceKind,
      ReferenceCommand command,
      SemanticOutcome expected,
      SemanticOutcome actual,
      String message) {
    Failure {
      Objects.requireNonNull(historyId, "historyId");
      Objects.requireNonNull(seed, "seed");
      Objects.requireNonNull(propertyId, "propertyId");
      Objects.requireNonNull(divergenceKind, "divergenceKind");
      Objects.requireNonNull(message, "message");
    }

    String fingerprint() {
      return propertyId + "/" + divergenceKind;
    }
  }

  record Step(ReferenceCommand command, SemanticOutcome expected, SemanticOutcome actual) {
    Step {
      Objects.requireNonNull(command, "command");
      Objects.requireNonNull(expected, "expected");
      Objects.requireNonNull(actual, "actual");
    }
  }

  static final class PropertyFailure extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final String propertyId;
    private final String divergenceKind;

    PropertyFailure(String propertyId, String divergenceKind, String message) {
      super(message);
      this.propertyId = Objects.requireNonNull(propertyId, "propertyId");
      this.divergenceKind = Objects.requireNonNull(divergenceKind, "divergenceKind");
    }

    String propertyId() {
      return propertyId;
    }

    String divergenceKind() {
      return divergenceKind;
    }
  }
}
