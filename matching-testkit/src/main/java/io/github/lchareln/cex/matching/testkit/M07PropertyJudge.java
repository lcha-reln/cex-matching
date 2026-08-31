package io.github.lchareln.cex.matching.testkit;

import io.github.lchareln.cex.matching.reference.M07ReferenceCommand;
import io.github.lchareln.cex.matching.reference.M07SemanticOutcome;
import java.util.List;
import java.util.Objects;

/** Three-way M07 classifier: production semantics, independent model, and event-derived ledger. */
final class M07PropertyJudge {
  static final String PASS = "PASS";
  static final String STUDENT_FAILURE = "STUDENT_FAILURE";
  static final String SYSTEM_ERROR = "SYSTEM_ERROR";

  Observation judge(List<M07ReferenceCommand> commands, M07Candidate.Factory candidateFactory) {
    Objects.requireNonNull(commands, "commands");
    Objects.requireNonNull(candidateFactory, "candidateFactory");
    M07Candidate candidate;
    try {
      candidate = candidateFactory.create();
    } catch (RuntimeException failure) {
      return system(-1, "CANDIDATE_FACTORY", failure);
    }
    M07ReferenceCandidate reference = new M07ReferenceCandidate();
    M07EventLedger candidateLedger = new M07EventLedger();
    M07EventLedger referenceLedger = new M07EventLedger();
    int comparisons = 0;
    int ledgerChecks = 0;
    for (int index = 0; index < commands.size(); index++) {
      M07ReferenceCommand command = commands.get(index);
      M07SemanticOutcome expected;
      try {
        expected = reference.apply(command);
        referenceLedger.apply(command, expected);
      } catch (RuntimeException failure) {
        return system(index, "REFERENCE_OR_LEDGER", failure);
      }
      M07SemanticOutcome actual;
      try {
        actual = candidate.apply(command);
      } catch (RuntimeException failure) {
        return system(index, "CANDIDATE_EXCEPTION", failure);
      }
      comparisons++;
      if (!expected.events().equals(actual.events())) {
        return student(
            index,
            "EVENT_DIFFERENTIAL",
            "candidate events differ from the independent model",
            comparisons,
            ledgerChecks);
      }
      if (!expected.stateAfter().equals(actual.stateAfter())) {
        return student(
            index,
            "STATE_DIFFERENTIAL",
            "candidate state differs from the independent model",
            comparisons,
            ledgerChecks);
      }
      if (!actual.stateAfter().equals(candidate.snapshot())) {
        return student(
            index,
            "DETACHED_SNAPSHOT",
            "candidate result and detached snapshot disagree",
            comparisons,
            ledgerChecks);
      }
      try {
        candidateLedger.apply(command, actual);
      } catch (M07EventLedger.LedgerFailure failure) {
        return student(index, "EVENT_LEDGER", failure.getMessage(), comparisons, ledgerChecks);
      } catch (RuntimeException failure) {
        return system(index, "EVENT_LEDGER_EXCEPTION", failure);
      }
      ledgerChecks++;
    }
    return new Observation(
        PASS, "PASS", -1, "M07 finite history passed", commands.size(), comparisons, ledgerChecks);
  }

  private static Observation student(
      int index, String property, String message, int comparisons, int ledgers) {
    return new Observation(STUDENT_FAILURE, property, index, message, index, comparisons, ledgers);
  }

  private static Observation system(int index, String property, RuntimeException failure) {
    String message = failure.getMessage();
    return new Observation(
        SYSTEM_ERROR,
        property,
        index,
        failure.getClass().getSimpleName()
            + (message == null || message.isBlank() ? "" : ": " + message),
        Math.max(index, 0),
        Math.max(index, 0),
        Math.max(index, 0));
  }

  record Observation(
      String classification,
      String fingerprint,
      int commandIndex,
      String message,
      int completedCommands,
      int differentialComparisons,
      int ledgerChecks) {}
}
