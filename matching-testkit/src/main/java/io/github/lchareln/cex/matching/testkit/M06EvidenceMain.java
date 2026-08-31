package io.github.lchareln.cex.matching.testkit;

import java.nio.file.Path;

/** Command-line entrypoint for tag-bound M06 evidence generation. */
public final class M06EvidenceMain {
  private M06EvidenceMain() {}

  public static void main(String[] arguments) {
    if (arguments.length != 4) {
      throw new IllegalArgumentException(
          "usage: M06EvidenceMain <repository-root> <check-directory> <evidence-directory> <unit-tag>");
    }
    M06EvidenceWriter.Result result =
        new M06EvidenceWriter()
            .write(
                Path.of(arguments[0]), Path.of(arguments[1]), Path.of(arguments[2]), arguments[3]);
    System.out.println(
        "M06 evidence: " + result.manifestPath() + " sha256=" + result.manifestSha256());
  }
}
