package io.github.lchareln.cex.matching.testkit;

import java.nio.file.Path;

/** Command-line entrypoint for M11 clean-tree correctness evidence publication. */
public final class M11EvidenceMain {
  private M11EvidenceMain() {}

  public static void main(String[] arguments) {
    if (arguments.length != 4) {
      throw new IllegalArgumentException(
          "usage: M11EvidenceMain <repository-root> <check-directory> <evidence-directory>"
              + " <unit-tag>");
    }
    M11EvidenceWriter.Result result =
        new M11EvidenceWriter()
            .write(
                Path.of(arguments[0]), Path.of(arguments[1]), Path.of(arguments[2]), arguments[3]);
    System.out.println(
        "M11 evidence: "
            + result.manifestPath()
            + " sha256="
            + result.manifestSha256()
            + " artifacts="
            + result.artifactCount());
  }
}
