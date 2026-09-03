package io.github.lchareln.cex.matching.testkit;

/** The single M11 boundary between deterministic contract failures and infrastructure failures. */
final class M11FailureClassifier {
  private M11FailureClassifier() {}

  static String classify(Throwable failure) {
    return failure instanceof M11SemanticFailure
        ? M11CheckRunner.STUDENT_FAILURE
        : M11CheckRunner.SYSTEM_ERROR;
  }
}
