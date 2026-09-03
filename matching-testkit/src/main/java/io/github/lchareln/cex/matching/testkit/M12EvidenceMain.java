package io.github.lchareln.cex.matching.testkit;

import java.nio.file.Path;

/** Command-line entrypoint for M12 clean-tree correctness evidence publication. */
public final class M12EvidenceMain {
  private M12EvidenceMain() {}

  public static void main(String[] arguments) {
    if (arguments.length != 5) {
      throw new IllegalArgumentException(
          "usage: M12EvidenceMain <repository-root> <check-directory> <evidence-directory>"
              + " <unit-tag> <product-release>");
    }
    M12EvidenceWriter.Result result =
        new M12EvidenceWriter()
            .write(
                Path.of(arguments[0]),
                Path.of(arguments[1]),
                Path.of(arguments[2]),
                arguments[3],
                arguments[4]);
    System.out.println(
        "M12 evidence: "
            + result.manifestPath()
            + " sha256="
            + result.manifestSha256()
            + " artifacts="
            + result.artifactCount());
  }
}
