package io.github.lchareln.cex.matching.testkit;

import io.github.lchareln.cex.matching.reference.LinearReferenceModel;
import io.github.lchareln.cex.matching.reference.ReferenceCommand;
import io.github.lchareln.cex.matching.reference.ReferenceMatcher;
import io.github.lchareln.cex.matching.reference.SemanticOutcome;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Fresh-state production/reference/ledger judge for M04 command histories. */
final class M04PropertyJudge {
  static final String PASS = "PASS";
  static final String STUDENT_FAILURE = "STUDENT_FAILURE";
  static final String SYSTEM_ERROR = "SYSTEM_ERROR";

  Observation judge(M04GeneratedHistory history, M04Candidate.Factory factory) {
    return judge(
        "history-" + history.historyIndex(), history.seedHex(), history.commands(), factory);
  }

  Observation judge(
      String historyId,
      String seed,
      List<ReferenceCommand> commands,
      M04Candidate.Factory factory) {
    Objects.requireNonNull(historyId, "historyId");
    Objects.requireNonNull(seed, "seed");
    List<ReferenceCommand> immutableCommands = List.copyOf(commands);
    Objects.requireNonNull(factory, "factory");
    List<Step> trace = new ArrayList<>();
    int comparisons = 0;
    int ledgerChecks = 0;
    int bookChecks = 0;
    M04Candidate candidate;
    ReferenceMatcher reference;
    M04EventLedger ledger;
    try {
      candidate = Objects.requireNonNull(factory.create(), "candidate");
      reference = new LinearReferenceModel();
      ledger = new M04EventLedger();
    } catch (RuntimeException failure) {
      return system(trace, comparisons, ledgerChecks, bookChecks, failure);
    }

    for (int index = 0; index < immutableCommands.size(); index++) {
      ReferenceCommand command = immutableCommands.get(index);
      SemanticOutcome expected;
      SemanticOutcome actual;
      try {
        expected = Objects.requireNonNull(reference.apply(command), "reference outcome");
        actual = Objects.requireNonNull(candidate.apply(command), "candidate outcome");
      } catch (RuntimeException failure) {
        return system(trace, comparisons, ledgerChecks, bookChecks, failure);
      }
      trace.add(new Step(command, expected, actual));
      try {
        ledger.verifyAndApply(command, actual);
        ledgerChecks++;
        bookChecks++;
        if (!expected.events().equals(actual.events())) {
          throw new PropertyFailure(
              "EXACT_BATCH_DIFFERENTIAL", "EVENT_BATCH", "events differ from reference");
        }
        if (!expected.bookAfter().equals(actual.bookAfter())) {
          throw new PropertyFailure(
              "EXACT_BATCH_DIFFERENTIAL", "BOOK_AFTER", "book differs from reference");
        }
        comparisons++;
      } catch (PropertyFailure failure) {
        return student(historyId, seed, trace, comparisons, ledgerChecks, bookChecks, failure);
      } catch (RuntimeException failure) {
        return system(trace, comparisons, ledgerChecks, bookChecks, failure);
      }
    }
    return new Observation(
        PASS,
        immutableCommands.size(),
        comparisons,
        ledgerChecks,
        bookChecks,
        null,
        trace,
        "all M04 properties passed");
  }

  private static Observation student(
      String historyId,
      String seed,
      List<Step> trace,
      int comparisons,
      int ledgerChecks,
      int bookChecks,
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
        detail,
        trace,
        failure.getMessage());
  }

  private static Observation system(
      List<Step> trace,
      int comparisons,
      int ledgerChecks,
      int bookChecks,
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
      Failure failure,
      List<Step> trace,
      String message) {
    Observation {
      Objects.requireNonNull(classification, "classification");
      trace = List.copyOf(trace);
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
      Objects.requireNonNull(propertyId, "propertyId");
      Objects.requireNonNull(divergenceKind, "divergenceKind");
    }

    String fingerprint() {
      return propertyId + "/" + divergenceKind;
    }
  }

  record Step(ReferenceCommand command, SemanticOutcome expected, SemanticOutcome actual) {}

  static final class PropertyFailure extends RuntimeException {
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
