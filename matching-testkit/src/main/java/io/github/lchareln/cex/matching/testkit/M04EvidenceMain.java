package io.github.lchareln.cex.matching.testkit;

import java.nio.file.Path;

/** CLI for clean-tree M04 evidence generation. */
public final class M04EvidenceMain {
  private M04EvidenceMain() {}

  public static void main(String[] arguments) {
    if (arguments.length != 4) {
      throw new IllegalArgumentException(
          "usage: M04EvidenceMain <repository-root> <check-directory> <evidence-directory> <unit-tag>");
    }
    M04EvidenceWriter.Result result =
        new M04EvidenceWriter()
            .write(
                Path.of(arguments[0]), Path.of(arguments[1]), Path.of(arguments[2]), arguments[3]);
    System.out.println(
        "M04 evidence: " + result.manifestPath() + " (sha256=" + result.manifestSha256() + ")");
  }
}
