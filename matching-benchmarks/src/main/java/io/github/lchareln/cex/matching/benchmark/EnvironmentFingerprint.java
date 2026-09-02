package io.github.lchareln.cex.matching.benchmark;

import com.sun.management.OperatingSystemMXBean;
import java.io.IOException;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.net.URI;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

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
    long maximumHeapBytes,
    List<String> garbageCollectorNames,
    String cpuModel,
    String storageDevice,
    String filesystem,
    String powerPolicy,
    String walRoot,
    String walRootUri,
    String walFileStoreName,
    String walFileStoreType,
    long walFileStoreTotalSpaceBytes,
    long walFileStoreUsableSpaceBytes,
    long walFileStoreUnallocatedSpaceBytes,
    Instant runStartedAt,
    Instant runFinishedAt) {
  private static final Pattern ENCODED_DOT = Pattern.compile("(?i)%2e");

  public EnvironmentFingerprint {
    requireText(javaRuntime, "javaRuntime");
    requireText(javaVersion, "javaVersion");
    requireText(javaVendor, "javaVendor");
    requireText(vmName, "vmName");
    jvmArguments = List.copyOf(jvmArguments);
    jvmArguments.forEach(value -> requireText(value, "jvmArguments entry"));
    requireText(osName, "osName");
    requireText(osVersion, "osVersion");
    requireText(osArchitecture, "osArchitecture");
    garbageCollectorNames = List.copyOf(garbageCollectorNames);
    if (garbageCollectorNames.isEmpty()) {
      throw new IllegalArgumentException("garbageCollectorNames must not be empty");
    }
    garbageCollectorNames.forEach(value -> requireText(value, "garbageCollectorNames entry"));
    if (new LinkedHashSet<>(garbageCollectorNames).size() != garbageCollectorNames.size()) {
      throw new IllegalArgumentException("garbageCollectorNames must not contain duplicates");
    }
    if (!garbageCollectorNames.stream().sorted().toList().equals(garbageCollectorNames)) {
      throw new IllegalArgumentException("garbageCollectorNames must use natural sort order");
    }
    requireText(cpuModel, "cpuModel");
    requireText(storageDevice, "storageDevice");
    requireText(filesystem, "filesystem");
    requireText(powerPolicy, "powerPolicy");
    requireText(walRoot, "walRoot");
    Path normalizedWalRoot = Path.of(walRoot).normalize();
    if (!normalizedWalRoot.isAbsolute() || !normalizedWalRoot.toString().equals(walRoot)) {
      throw new IllegalArgumentException("walRoot must be an absolute normalized path");
    }
    URI normalizedWalRootUri = validateWalRootUri(walRootUri);
    if (!Path.of(normalizedWalRootUri).normalize().equals(normalizedWalRoot)) {
      throw new IllegalArgumentException("walRootUri must resolve to walRoot");
    }
    requireText(walFileStoreName, "walFileStoreName");
    requireText(walFileStoreType, "walFileStoreType");
    Objects.requireNonNull(runStartedAt, "runStartedAt");
    Objects.requireNonNull(runFinishedAt, "runFinishedAt");
    if (availableProcessors <= 0 || physicalMemoryBytes <= 0 || maximumHeapBytes <= 0) {
      throw new IllegalArgumentException(
          "processor, physical memory, and maximum heap dimensions must be present");
    }
    if (walFileStoreTotalSpaceBytes <= 0
        || walFileStoreUsableSpaceBytes < 0
        || walFileStoreUnallocatedSpaceBytes < 0
        || walFileStoreUsableSpaceBytes > walFileStoreTotalSpaceBytes
        || walFileStoreUnallocatedSpaceBytes > walFileStoreTotalSpaceBytes) {
      throw new IllegalArgumentException("WAL FileStore space dimensions are inconsistent");
    }
    if (runFinishedAt.isBefore(runStartedAt)) {
      throw new IllegalArgumentException("runFinishedAt precedes runStartedAt");
    }
  }

  public static EnvironmentFingerprint capture(
      Path walRoot,
      String cpuModel,
      String storageDevice,
      String filesystem,
      String powerPolicy,
      Instant runStartedAt,
      Instant runFinishedAt)
      throws IOException {
    java.lang.management.RuntimeMXBean runtime = ManagementFactory.getRuntimeMXBean();
    OperatingSystemMXBean os = operatingSystemBean();
    Path requestedWalRoot = Objects.requireNonNull(walRoot, "walRoot").toAbsolutePath().normalize();
    if (!Files.isDirectory(requestedWalRoot)) {
      throw new IOException("WAL root is not an existing directory: " + requestedWalRoot);
    }
    Path realWalRoot = requestedWalRoot.toRealPath();
    FileStore walFileStore = Files.getFileStore(realWalRoot);
    List<String> garbageCollectors =
        ManagementFactory.getGarbageCollectorMXBeans().stream()
            .map(GarbageCollectorMXBean::getName)
            .distinct()
            .sorted()
            .toList();
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
        Runtime.getRuntime().maxMemory(),
        garbageCollectors,
        cpuModel,
        storageDevice,
        filesystem,
        powerPolicy,
        realWalRoot.toString(),
        realWalRoot.toUri().toASCIIString(),
        walFileStore.name(),
        walFileStore.type(),
        walFileStore.getTotalSpace(),
        walFileStore.getUsableSpace(),
        walFileStore.getUnallocatedSpace(),
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

  static URI validateWalRootUri(String value) {
    requireText(value, "walRootUri");
    final URI uri;
    try {
      uri = URI.create(value);
    } catch (IllegalArgumentException invalidUri) {
      throw new IllegalArgumentException("walRootUri must be a valid URI", invalidUri);
    }
    if (!uri.isAbsolute()
        || !"file".equalsIgnoreCase(uri.getScheme())
        || uri.isOpaque()
        || uri.getRawAuthority() != null
        || uri.getRawPath() == null
        || !uri.getRawPath().startsWith("/")
        || !uri.normalize().equals(uri)
        || hasUnsafeRawPath(uri.getRawPath())
        || uri.getRawQuery() != null
        || uri.getRawFragment() != null) {
      throw new IllegalArgumentException("walRootUri must identify an absolute file URI path");
    }
    return uri;
  }

  private static boolean hasUnsafeRawPath(String rawPath) {
    String lowercase = rawPath.toLowerCase(Locale.ROOT);
    if (lowercase.contains("%2f") || lowercase.contains("%5c") || lowercase.contains("%00")) {
      return true;
    }
    for (String segment : rawPath.split("/", -1)) {
      String decodedDots = ENCODED_DOT.matcher(segment).replaceAll(".");
      if (".".equals(decodedDots) || "..".equals(decodedDots)) return true;
    }
    return false;
  }
}
