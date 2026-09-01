package io.github.lchareln.cex.matching.testkit;

import io.github.lchareln.cex.matching.local.FaultInjector;
import io.github.lchareln.cex.matching.local.FaultPoint;
import io.github.lchareln.cex.matching.local.LocalMatchingRuntime;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;

/** Child JVM that reaches exactly one M09 publication/retirement hook then halts with code 86. */
public final class M09CrashChildMain {
  static final int HALT_CODE = 86;
  private static final Map<String, FaultPoint> POINTS =
      Map.of(
          "BEFORE_SNAPSHOT_TEMP_WRITE", FaultPoint.BEFORE_SNAPSHOT_TEMP_WRITE,
          "AFTER_PARTIAL_SNAPSHOT_TEMP_WRITE", FaultPoint.AFTER_PARTIAL_SNAPSHOT_TEMP_WRITE,
          "AFTER_SNAPSHOT_FILE_FORCE_BEFORE_RENAME", FaultPoint.BEFORE_SNAPSHOT_READ,
          "AFTER_SNAPSHOT_RENAME_BEFORE_DIRECTORY_FORCE",
              FaultPoint.BEFORE_SNAPSHOT_DIRECTORY_FORCE,
          "AFTER_SNAPSHOT_DIRECTORY_FORCE_BEFORE_RETIREMENT",
              FaultPoint.AFTER_SNAPSHOT_DIRECTORY_FORCE_BEFORE_RETENTION,
          "AFTER_FIRST_SEGMENT_DELETE_BEFORE_DIRECTORY_FORCE",
              FaultPoint.AFTER_FIRST_RETENTION_SEGMENT_DELETE,
          "AFTER_RETIREMENT_DIRECTORY_FORCE_BEFORE_RETURN",
              FaultPoint.AFTER_RETENTION_DIRECTORY_FORCE_BEFORE_RETURN);

  private M09CrashChildMain() {}

  static FaultPoint expectedFaultPoint(String window) {
    FaultPoint point = POINTS.get(window);
    if (point == null) {
      throw new IllegalArgumentException("unknown M09 child halt window " + window);
    }
    return point;
  }

  public static void main(String[] arguments) throws Exception {
    if (arguments.length != 3) {
      throw new IllegalArgumentException(
          "usage: M09CrashChildMain <runtime-directory> <marker> <window>");
    }
    Path directory = Path.of(arguments[0]).toAbsolutePath().normalize();
    Path marker = Path.of(arguments[1]).toAbsolutePath().normalize();
    String window = arguments[2];
    FaultPoint point = expectedFaultPoint(window);
    Files.createDirectories(directory);
    M09ScenarioSupport support = new M09ScenarioSupport();
    M09ScenarioSupport.CommandStream stream = support.stream("child-" + window);
    HaltingInjector injector = new HaltingInjector(point, marker, window);
    try (LocalMatchingRuntime runtime =
        LocalMatchingRuntime.open(support.config(directory), injector)) {
      M09ScenarioSupport.requireNew(
          runtime.submit(stream.next(M09ScenarioSupport.cancel(1))), "child first submit");
      if (window.startsWith("AFTER_FIRST_SEGMENT_DELETE")
          || window.startsWith("AFTER_RETIREMENT_DIRECTORY_FORCE")) {
        runtime.checkpoint();
        M09ScenarioSupport.requireNew(
            runtime.submit(stream.next(M09ScenarioSupport.cancel(2))), "child second submit");
      }
      injector.arm(runtime.nextWalSequence(), runtime.semanticStateDigest());
      runtime.checkpoint();
    }
    throw new IllegalStateException("M09 child did not reach halt hook " + window);
  }

  private static final class HaltingInjector implements FaultInjector {
    private final FaultPoint target;
    private final Path marker;
    private final String window;
    private boolean armed;
    private long nextWalSequence;
    private String semanticDigest = "";

    private HaltingInjector(FaultPoint target, Path marker, String window) {
      this.target = target;
      this.marker = marker;
      this.window = window;
    }

    private void arm(long nextWalSequence, String semanticDigest) {
      this.nextWalSequence = nextWalSequence;
      this.semanticDigest = semanticDigest;
      armed = true;
    }

    @Override
    public void hit(FaultPoint point) throws IOException {
      if (!armed || point != target) {
        return;
      }
      String value =
          "window="
              + window
              + "\nfaultPoint="
              + target
              + "\noccurrence=1"
              + "\nnextWalSequence="
              + nextWalSequence
              + "\nsemanticDigest="
              + semanticDigest
              + '\n';
      Files.writeString(marker, value, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
      try (FileChannel channel = FileChannel.open(marker, StandardOpenOption.WRITE)) {
        channel.force(true);
      }
      Runtime.getRuntime().halt(HALT_CODE);
    }
  }
}
