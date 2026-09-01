package io.github.lchareln.cex.matching.testkit;

import java.nio.file.Path;

/** Command-line entrypoint for annotated-tag-bound M09 evidence generation. */
public final class M09EvidenceMain {
  private M09EvidenceMain() {}

  public static void main(String[] arguments) {
    if (arguments.length != 4) {
      throw new IllegalArgumentException(
          "usage: M09EvidenceMain <repository-root> <check-directory> <evidence-directory> <unit-tag>");
    }
    M09EvidenceWriter.Result result =
        new M09EvidenceWriter()
            .write(
                Path.of(arguments[0]), Path.of(arguments[1]), Path.of(arguments[2]), arguments[3]);
    System.out.println(
        "M09 evidence: " + result.manifestPath() + " sha256=" + result.manifestSha256());
  }
}
