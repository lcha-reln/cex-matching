package io.github.lchareln.cex.matching.benchmark;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Schema-like semantic reconciliation applied before derived performance data is publishable. */
public final class RunReconciler {
  public static final Set<String> SUBMISSION_RESULT_VARIANTS =
      Set.of(
          "NEW_DURABLY_APPLIED",
          "DUPLICATE_REPLAYED",
          "STRUCTURAL_REJECTED",
          "PREFLIGHT_REJECTED",
          "CHECKPOINT_REQUIRED",
          "DURABILITY_UNKNOWN",
          "FAILED_CLOSED");

  private RunReconciler() {}

  public static List<String> violations(
      RunAccounting accounting,
      boolean terminalQuiesced,
      int latencySampleCount,
      int queueSampleCount,
      int resourceSampleCount) {
    List<String> violations = new ArrayList<>();
    if (accounting.offers()
        != accounting.admitted() + accounting.overloaded() + accounting.closedOrInvalid()) {
      violations.add("offers != admitted + overloaded + closed-or-invalid");
    }
    if (accounting.admitted()
        != accounting.submissionResultCompletions()
            + accounting.explicitServiceFailures()
            + accounting.pendingAtObservationCut()) {
      violations.add("admitted != submission-result-completions + explicit-failures + pending");
    }
    if (terminalQuiesced && accounting.pendingAtObservationCut() != 0) {
      violations.add("terminal quiesced cut has pending admitted work");
    }
    for (Map.Entry<String, Long> entry : accounting.submissionResultVariants().entrySet()) {
      if (!SUBMISSION_RESULT_VARIANTS.contains(entry.getKey())) {
        violations.add("unknown SubmissionResult variant: " + entry.getKey());
      }
    }
    if (latencySampleCount != accounting.terminalCompletions()) {
      violations.add("latency sample count does not equal terminal admitted completions");
    }
    if (queueSampleCount != accounting.offers()) {
      violations.add("queue sample count does not equal offers");
    }
    if (resourceSampleCount <= 0) {
      violations.add("resource series is missing");
    }
    return List.copyOf(violations);
  }

  public static void requireValid(
      RunAccounting accounting,
      boolean terminalQuiesced,
      int latencySampleCount,
      int queueSampleCount,
      int resourceSampleCount) {
    List<String> violations =
        violations(
            accounting,
            terminalQuiesced,
            latencySampleCount,
            queueSampleCount,
            resourceSampleCount);
    if (!violations.isEmpty()) {
      throw new IllegalArgumentException(String.join("; ", violations));
    }
  }
}
