package io.github.lchareln.cex.matching.cluster;

import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;

/** Child-JVM entry point for exactly one M12 voting member. */
public final class M12ClusterMemberMain {
  private M12ClusterMemberMain() {}

  public static void main(String[] arguments) throws Exception {
    LaunchOptions options = parseArguments(arguments);
    M12ClusterMember member =
        M12ClusterMember.launch(options.config(), options.memberId(), options.freshStart());
    AtomicBoolean stopping = new AtomicBoolean();
    Thread owner = Thread.currentThread();
    Thread shutdownHook =
        Thread.ofPlatform()
            .name("m12-member-" + options.memberId() + "-shutdown")
            .unstarted(
                () -> {
                  stopping.set(true);
                  try {
                    member.publishStatus();
                  } catch (Exception ignored) {
                    // Process stderr and the non-zero child exit remain authoritative if publishing
                    // fails.
                  } finally {
                    member.close();
                    LockSupport.unpark(owner);
                  }
                });
    Runtime.getRuntime().addShutdownHook(shutdownHook);
    try {
      while (!stopping.get()) {
        member.publishStatus();
        member.throwIfFailed();
        LockSupport.parkNanos(options.config().statusPublishInterval().toNanos());
        if (Thread.interrupted()) {
          Thread.currentThread().interrupt();
          stopping.set(true);
        }
      }
    } finally {
      try {
        Runtime.getRuntime().removeShutdownHook(shutdownHook);
      } catch (IllegalStateException ignored) {
        // The registered hook owns cleanup once JVM shutdown has begun.
      }
      member.close();
    }
  }

  static LaunchOptions parseArguments(String[] arguments) {
    if (arguments.length == 0 || arguments.length % 2 != 0) {
      throw new IllegalArgumentException("M12 member arguments must be --name value pairs");
    }
    Map<String, String> values = new HashMap<>();
    for (int index = 0; index < arguments.length; index += 2) {
      String name = arguments[index];
      if (!name.startsWith("--") || name.length() == 2) {
        throw new IllegalArgumentException("invalid M12 member argument name: " + name);
      }
      if (values.putIfAbsent(name, arguments[index + 1]) != null) {
        throw new IllegalArgumentException("duplicate M12 member argument: " + name);
      }
    }
    requireOnlyKnown(values);
    boolean freshStart = strictBoolean(required(values, "--fresh-start"), "--fresh-start");
    M12ThreeMemberConfig config =
        new M12ThreeMemberConfig(
            Path.of(required(values, "--root")),
            integer(values, "--cluster-id"),
            longInteger(values, "--shard-id"),
            integer(values, "--port-base"),
            integer(values, "--app-version"),
            milliseconds(values, "--client-message-timeout-ms"),
            milliseconds(values, "--heartbeat-interval-ms"),
            milliseconds(values, "--heartbeat-timeout-ms"),
            milliseconds(values, "--election-timeout-ms"),
            milliseconds(values, "--startup-canvass-timeout-ms"),
            milliseconds(values, "--status-interval-ms"));
    int memberId = integer(values, "--member-id");
    config.requireMemberId(memberId);
    return new LaunchOptions(config, memberId, freshStart);
  }

  private static void requireOnlyKnown(Map<String, String> values) {
    for (String name : values.keySet()) {
      if (!switch (name) {
        case "--root",
            "--member-id",
            "--cluster-id",
            "--shard-id",
            "--port-base",
            "--app-version",
            "--client-message-timeout-ms",
            "--heartbeat-interval-ms",
            "--heartbeat-timeout-ms",
            "--election-timeout-ms",
            "--startup-canvass-timeout-ms",
            "--status-interval-ms",
            "--fresh-start" ->
            true;
        default -> false;
      }) {
        throw new IllegalArgumentException("unknown M12 member argument: " + name);
      }
    }
  }

  private static String required(Map<String, String> values, String name) {
    String value = values.get(name);
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("missing M12 member argument: " + name);
    }
    return value;
  }

  private static int integer(Map<String, String> values, String name) {
    return Integer.parseInt(required(values, name));
  }

  private static long longInteger(Map<String, String> values, String name) {
    return Long.parseLong(required(values, name));
  }

  private static Duration milliseconds(Map<String, String> values, String name) {
    return Duration.ofMillis(longInteger(values, name));
  }

  private static boolean strictBoolean(String value, String name) {
    if (!"true".equals(value) && !"false".equals(value)) {
      throw new IllegalArgumentException(name + " must be true or false");
    }
    return Boolean.parseBoolean(value);
  }

  record LaunchOptions(M12ThreeMemberConfig config, int memberId, boolean freshStart) {}
}
