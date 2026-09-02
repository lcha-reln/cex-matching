package io.github.lchareln.cex.matching.testkit;

import java.nio.file.Path;

/** Command-line entrypoint for M10 correctness plus full qualification evidence publication. */
public final class M10EvidenceMain {
  private M10EvidenceMain() {}

  public static void main(String[] arguments) {
    if (arguments.length != 6) {
      throw new IllegalArgumentException(
          "usage: M10EvidenceMain <repository-root> <check-directory> <release-directory>"
              + " <evidence-directory> <unit-tag> <product-release>");
    }
    M10EvidenceWriter.Result result =
        new M10EvidenceWriter()
            .write(
                Path.of(arguments[0]),
                Path.of(arguments[1]),
                Path.of(arguments[2]),
                Path.of(arguments[3]),
                arguments[4],
                arguments[5]);
    System.out.println(
        "M10 evidence: "
            + result.manifestPath()
            + " sha256="
            + result.manifestSha256()
            + " decompressedRawRecords="
            + result.rawRecords());
  }
}
