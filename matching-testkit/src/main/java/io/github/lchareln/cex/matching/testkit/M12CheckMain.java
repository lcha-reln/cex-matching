package io.github.lchareln.cex.matching.testkit;

import java.nio.file.Path;

/** Command-line entrypoint for the intentional M12 start-contract RED. */
public final class M12CheckMain {
  private M12CheckMain() {}

  public static void main(String[] arguments) {
    if (arguments.length != 2) {
      throw new IllegalArgumentException(
          "usage: M12CheckMain <repository-root> <report-directory>");
    }
    M12StartCheckRunner.Result result =
        new M12StartCheckRunner().run(Path.of(arguments[0]), Path.of(arguments[1]));
    System.out.println("M12 check status: " + result.status() + " (" + result.reportPath() + ")");
    System.exit(1);
  }
}
