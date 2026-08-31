package io.github.lchareln.cex.matching.testkit;

import java.nio.file.Path;

/** Command-line entrypoint for the structured M05 RED boundary. */
public final class M05CheckMain {
  private M05CheckMain() {}

  public static void main(String[] arguments) {
    if (arguments.length != 2) {
      throw new IllegalArgumentException(
          "usage: M05CheckMain <repository-root> <report-directory>");
    }
    M05StartCheckRunner.Result result =
        new M05StartCheckRunner().run(Path.of(arguments[0]), Path.of(arguments[1]));
    System.out.println("M05 check status: " + result.status() + " (" + result.reportPath() + ")");
    if (!"PASS".equals(result.status())) {
      System.exit(1);
    }
  }
}
