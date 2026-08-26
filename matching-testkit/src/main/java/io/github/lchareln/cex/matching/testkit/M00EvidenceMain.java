package io.github.lchareln.cex.matching.testkit;

import java.nio.file.Path;

/** Command-line entrypoint used by the Gradle {@code m00Evidence} task. */
public final class M00EvidenceMain {
  private M00EvidenceMain() {}

  public static void main(String[] arguments) {
    if (arguments.length != 4) {
      throw new IllegalArgumentException(
          "usage: M00EvidenceMain <repository-root> <check-directory> <evidence-directory> <unit-tag>");
    }
    M00EvidenceWriter.Result result =
        new M00EvidenceWriter()
            .write(
                Path.of(arguments[0]), Path.of(arguments[1]), Path.of(arguments[2]), arguments[3]);
    System.out.println(
        "M00 evidence: "
            + result.manifestPath()
            + " sha256="
            + result.manifestSha256()
            + " source="
            + result.sourceCommit());
  }
}
