package io.github.lchareln.cex.matching.testkit;

import java.nio.file.Path;

/** Command-line entrypoint used by the Gradle {@code m02Evidence} task. */
public final class M02EvidenceMain {
  private M02EvidenceMain() {}

  public static void main(String[] arguments) {
    if (arguments.length != 4) {
      throw new IllegalArgumentException(
          "usage: M02EvidenceMain <repository-root> <check-directory> <evidence-directory> <unit-tag>");
    }
    M02EvidenceWriter.Result result =
        new M02EvidenceWriter()
            .write(
                Path.of(arguments[0]), Path.of(arguments[1]), Path.of(arguments[2]), arguments[3]);
    System.out.println(
        "M02 evidence: "
            + result.manifestPath()
            + " sha256="
            + result.manifestSha256()
            + " source="
            + result.sourceCommit());
  }
}
