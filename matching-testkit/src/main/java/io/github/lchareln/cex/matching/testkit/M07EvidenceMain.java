package io.github.lchareln.cex.matching.testkit;

import java.nio.file.Path;

/** Command-line entrypoint for tag-bound M07 evidence generation. */
public final class M07EvidenceMain {
  private M07EvidenceMain() {}

  public static void main(String[] arguments) {
    if (arguments.length != 4) {
      throw new IllegalArgumentException(
          "usage: M07EvidenceMain <repository-root> <check-directory> <evidence-directory> <unit-tag>");
    }
    M07EvidenceWriter.Result result =
        new M07EvidenceWriter()
            .write(
                Path.of(arguments[0]), Path.of(arguments[1]), Path.of(arguments[2]), arguments[3]);
    System.out.println(
        "M07 evidence: " + result.manifestPath() + " sha256=" + result.manifestSha256());
  }
}
