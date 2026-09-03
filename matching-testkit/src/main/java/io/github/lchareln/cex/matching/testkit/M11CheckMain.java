package io.github.lchareln.cex.matching.testkit;

import java.nio.file.Path;

/** Command-line entrypoint for the intentional M11 start-contract RED. */
public final class M11CheckMain {
  private M11CheckMain() {}

  public static void main(String[] arguments) {
    if (arguments.length != 2) {
      throw new IllegalArgumentException(
          "usage: M11CheckMain <repository-root> <report-directory>");
    }
    M11StartCheckRunner.Result result =
        new M11StartCheckRunner().run(Path.of(arguments[0]), Path.of(arguments[1]));
    System.out.println("M11 check status: " + result.status() + " (" + result.reportPath() + ")");
    System.exit(1);
  }
}
