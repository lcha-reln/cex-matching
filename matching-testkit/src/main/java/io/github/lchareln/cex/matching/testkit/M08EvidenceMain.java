package io.github.lchareln.cex.matching.testkit;

import java.nio.file.Path;

/** Command-line entrypoint for annotated-tag-bound M08 evidence generation. */
public final class M08EvidenceMain {
  private M08EvidenceMain() {}

  public static void main(String[] arguments) {
    if (arguments.length != 4) {
      throw new IllegalArgumentException(
          "usage: M08EvidenceMain <repository-root> <check-directory> <evidence-directory> <unit-tag>");
    }
    M08EvidenceWriter.Result result =
        new M08EvidenceWriter()
            .write(
                Path.of(arguments[0]), Path.of(arguments[1]), Path.of(arguments[2]), arguments[3]);
    System.out.println(
        "M08 evidence: " + result.manifestPath() + " sha256=" + result.manifestSha256());
  }
}
