package io.github.lchareln.cex.matching.benchmark;

import com.sun.management.OperatingSystemMXBean;
import java.lang.management.ManagementFactory;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Environment dimensions that scope an M10 capacity envelope to one measured machine. */
public record EnvironmentFingerprint(
    String javaRuntime,
    String javaVersion,
    String javaVendor,
    String vmName,
    List<String> jvmArguments,
    String osName,
    String osVersion,
    String osArchitecture,
    int availableProcessors,
    long physicalMemoryBytes,
    String cpuModel,
    String storageDevice,
    String filesystem,
    String powerPolicy,
    Instant runStartedAt,
    Instant runFinishedAt) {
  public EnvironmentFingerprint {
    requireText(javaRuntime, "javaRuntime");
    requireText(javaVersion, "javaVersion");
    requireText(javaVendor, "javaVendor");
    requireText(vmName, "vmName");
    jvmArguments = List.copyOf(jvmArguments);
    requireText(osName, "osName");
    requireText(osVersion, "osVersion");
    requireText(osArchitecture, "osArchitecture");
    requireText(cpuModel, "cpuModel");
    requireText(storageDevice, "storageDevice");
    requireText(filesystem, "filesystem");
    requireText(powerPolicy, "powerPolicy");
    Objects.requireNonNull(runStartedAt, "runStartedAt");
    Objects.requireNonNull(runFinishedAt, "runFinishedAt");
    if (availableProcessors <= 0 || physicalMemoryBytes <= 0) {
      throw new IllegalArgumentException(
          "processor and physical memory dimensions must be present");
    }
    if (runFinishedAt.isBefore(runStartedAt)) {
      throw new IllegalArgumentException("runFinishedAt precedes runStartedAt");
    }
  }

  public static EnvironmentFingerprint capture(
      String cpuModel,
      String storageDevice,
      String filesystem,
      String powerPolicy,
      Instant runStartedAt,
      Instant runFinishedAt) {
    java.lang.management.RuntimeMXBean runtime = ManagementFactory.getRuntimeMXBean();
    OperatingSystemMXBean os = operatingSystemBean();
    return new EnvironmentFingerprint(
        System.getProperty("java.runtime.name"),
        System.getProperty("java.runtime.version"),
        System.getProperty("java.vendor"),
        System.getProperty("java.vm.name"),
        runtime.getInputArguments(),
        System.getProperty("os.name"),
        System.getProperty("os.version"),
        System.getProperty("os.arch"),
        Runtime.getRuntime().availableProcessors(),
        os.getTotalMemorySize(),
        cpuModel,
        storageDevice,
        filesystem,
        powerPolicy,
        runStartedAt,
        runFinishedAt);
  }

  private static OperatingSystemMXBean operatingSystemBean() {
    if (ManagementFactory.getOperatingSystemMXBean() instanceof OperatingSystemMXBean os) {
      return os;
    }
    throw new IllegalStateException("JDK operating-system resource collector is unavailable");
  }

  private static void requireText(String value, String name) {
    Objects.requireNonNull(value, name);
    if (value.isBlank()
        || value.equalsIgnoreCase("unknown")
        || value.equalsIgnoreCase("unavailable")) {
      throw new IllegalArgumentException(name + " must be explicitly recorded");
    }
  }
}
