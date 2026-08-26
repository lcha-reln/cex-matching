package io.github.lchareln.cex.matching.testkit;

import java.nio.file.Path;

/** Command-line entrypoint used by the Gradle {@code m00Check} task. */
public final class M00CheckMain {
  private M00CheckMain() {}

  public static void main(String[] arguments) {
    if (arguments.length != 2) {
      throw new IllegalArgumentException(
          "usage: M00CheckMain <repository-root> <report-directory>");
    }
    M00CheckRunner.Result result =
        new M00CheckRunner().run(Path.of(arguments[0]), Path.of(arguments[1]));
    System.out.println("M00 check status: " + result.status() + " (" + result.reportPath() + ")");
    if (!result.passed()) {
      System.exit(1);
    }
  }
}
