package io.github.lchareln.cex.matching.testkit;

import io.github.lchareln.cex.matching.local.FaultInjector;
import io.github.lchareln.cex.matching.local.FaultPoint;
import io.github.lchareln.cex.matching.local.LocalMatchingRuntime;
import io.github.lchareln.cex.matching.local.M08Command;
import io.github.lchareln.cex.matching.local.M08EnvelopeCodec;
import io.github.lchareln.cex.matching.local.WalConfig;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.UUID;

/** Child-only process that halts at one deterministic runtime hook without closing the WAL. */
public final class M08CrashChildMain {
  static final int HALT_EXIT = 86;

  private M08CrashChildMain() {}

  public static void main(String[] arguments) throws Exception {
    if (arguments.length != 3) {
      throw new IllegalArgumentException(
          "usage: M08CrashChildMain <directory> <shard> <fault-point>");
    }
    Path directory = Path.of(arguments[0]).toAbsolutePath().normalize();
    long shard = Long.parseLong(arguments[1]);
    FaultPoint point = FaultPoint.valueOf(arguments[2]);
    if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)
        || Files.isSymbolicLink(directory)) {
      throw new IllegalArgumentException(
          "child requires a deployment-preprovisioned real WAL directory");
    }
    byte[] envelope = envelope(shard, point);
    try (LocalMatchingRuntime runtime =
        LocalMatchingRuntime.open(
            WalConfig.defaults(directory, shard), new HaltFault(directory, point))) {
      runtime.submit(envelope);
    }
    throw new IllegalStateException("child did not halt at " + point);
  }

  static byte[] envelope(long shard, FaultPoint point) {
    return new M08EnvelopeCodec()
        .encode(
            "halt-smoke-" + point,
            1,
            shard,
            1,
            new UUID(0x0860000000000000L, point.ordinal() + 1L),
            new M08Command.Cancel("BTC-USDT", BigInteger.valueOf(point.ordinal() + 1L)));
  }

  static Path marker(Path directory, FaultPoint point) {
    return directory.resolve("halt-" + point + ".marker");
  }

  private static final class HaltFault implements FaultInjector {
    private final Path directory;
    private final FaultPoint target;

    private HaltFault(Path directory, FaultPoint target) {
      this.directory = directory;
      this.target = target;
    }

    @Override
    public void hit(FaultPoint point) throws IOException {
      if (point != target) {
        return;
      }
      Path marker = marker(directory, point);
      Files.writeString(
          marker, point.name(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
      try (FileChannel channel = FileChannel.open(marker, StandardOpenOption.WRITE)) {
        channel.force(true);
      }
      Runtime.getRuntime().halt(HALT_EXIT);
    }
  }
}
