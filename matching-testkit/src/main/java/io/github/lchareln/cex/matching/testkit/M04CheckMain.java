package io.github.lchareln.cex.matching.testkit;

import java.nio.file.Path;

/** Command-line entrypoint for the structured M04 RED boundary. */
public final class M04CheckMain {
  private M04CheckMain() {}

  public static void main(String[] arguments) {
    if (arguments.length != 2) {
      throw new IllegalArgumentException(
          "usage: M04CheckMain <repository-root> <report-directory>");
    }
    M04StartCheckRunner.Result result =
        new M04StartCheckRunner().run(Path.of(arguments[0]), Path.of(arguments[1]));
    System.out.println("M04 check status: " + result.status() + " (" + result.reportPath() + ")");
    if (!"PASS".equals(result.status())) {
      System.exit(1);
    }
  }
}
