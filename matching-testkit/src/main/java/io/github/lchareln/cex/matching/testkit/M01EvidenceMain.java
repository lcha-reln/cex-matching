package io.github.lchareln.cex.matching.testkit;

import java.nio.file.Path;

/** Command-line entrypoint used by the Gradle {@code m01Evidence} task. */
public final class M01EvidenceMain {
  private M01EvidenceMain() {}

  public static void main(String[] arguments) {
    if (arguments.length != 4) {
      throw new IllegalArgumentException(
          "usage: M01EvidenceMain <repository-root> <check-directory> <evidence-directory> <unit-tag>");
    }
    M01EvidenceWriter.Result result =
        new M01EvidenceWriter()
            .write(
                Path.of(arguments[0]), Path.of(arguments[1]), Path.of(arguments[2]), arguments[3]);
    System.out.println(
        "M01 evidence: "
            + result.manifestPath()
            + " sha256="
            + result.manifestSha256()
            + " source="
            + result.sourceCommit());
  }
}
