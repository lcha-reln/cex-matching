package io.github.lchareln.cex.matching.benchmark;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EnvironmentFingerprintTest {
  @Test
  void captureBindsTheJvmCollectorsHeapAndActualWalFileStore(@TempDir Path walRoot)
      throws Exception {
    Instant started = Instant.parse("2026-09-01T00:00:00Z");
    EnvironmentFingerprint fingerprint =
        EnvironmentFingerprint.capture(
            walRoot,
            "test-cpu",
            "operator-device-label",
            "operator-filesystem-label",
            "test-power-policy",
            started,
            started.plusSeconds(1));

    FileStore actualStore = Files.getFileStore(walRoot);
    assertEquals(walRoot.toRealPath().toString(), fingerprint.walRoot());
    assertEquals(walRoot.toRealPath().toUri().toASCIIString(), fingerprint.walRootUri());
    assertEquals(actualStore.name(), fingerprint.walFileStoreName());
    assertEquals(actualStore.type(), fingerprint.walFileStoreType());
    assertEquals(Runtime.getRuntime().maxMemory(), fingerprint.maximumHeapBytes());
    assertEquals(
        ManagementFactory.getGarbageCollectorMXBeans().stream()
            .map(GarbageCollectorMXBean::getName)
            .distinct()
            .sorted()
            .toList(),
        fingerprint.garbageCollectorNames());
    assertTrue(fingerprint.walFileStoreTotalSpaceBytes() > 0);
    assertTrue(fingerprint.walFileStoreUsableSpaceBytes() >= 0);
    assertTrue(fingerprint.walFileStoreUnallocatedSpaceBytes() >= 0);
    assertTrue(
        fingerprint.walFileStoreUsableSpaceBytes() <= fingerprint.walFileStoreTotalSpaceBytes());
    assertTrue(
        fingerprint.walFileStoreUnallocatedSpaceBytes()
            <= fingerprint.walFileStoreTotalSpaceBytes());
    assertEquals("operator-device-label", fingerprint.storageDevice());
    assertEquals("operator-filesystem-label", fingerprint.filesystem());
  }

  @Test
  void constructorRejectsDuplicateCollectorsRelativePathsAndImpossibleSpace(@TempDir Path walRoot)
      throws Exception {
    EnvironmentFingerprint captured =
        EnvironmentFingerprint.capture(
            walRoot,
            "test-cpu",
            "operator-device-label",
            "operator-filesystem-label",
            "test-power-policy",
            Instant.parse("2026-09-01T00:00:00Z"),
            Instant.parse("2026-09-01T00:00:01Z"));
    String collector = captured.garbageCollectorNames().getFirst();

    assertThrows(
        IllegalArgumentException.class,
        () ->
            copy(
                captured,
                List.of(collector, collector),
                captured.walRoot(),
                captured.walRootUri(),
                100,
                50,
                50));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            copy(
                captured,
                List.of("Z Collector", "A Collector"),
                captured.walRoot(),
                captured.walRootUri(),
                100,
                50,
                50));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            copy(
                captured,
                List.of(" "),
                List.of(collector),
                captured.walRoot(),
                captured.walRootUri(),
                100,
                50,
                50));
    for (String unsafeUri :
        List.of(
            "file:///var/m10/%2E%2E/wal",
            "file:///var/m10/%2Fescape", "file:///var/m10/%5Cescape", "file:///var/m10/%00/wal")) {
      assertThrows(
          IllegalArgumentException.class,
          () -> EnvironmentFingerprint.validateWalRootUri(unsafeUri));
    }
    assertThrows(
        IllegalArgumentException.class,
        () ->
            copy(captured, List.of(collector), "relative/wal", captured.walRootUri(), 100, 50, 50));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            copy(
                captured,
                List.of(collector),
                captured.walRoot(),
                "file://remote-host/m10/wal",
                100,
                50,
                50));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            copy(
                captured,
                List.of(collector),
                captured.walRoot(),
                walRoot.resolve("different").toUri().toASCIIString(),
                100,
                50,
                50));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            copy(
                captured,
                List.of(collector),
                captured.walRoot(),
                captured.walRootUri(),
                100,
                101,
                50));
    assertDoesNotThrow(
        () ->
            copy(
                captured,
                List.of(collector),
                captured.walRoot(),
                captured.walRootUri(),
                100,
                0,
                0));
  }

  private static EnvironmentFingerprint copy(
      EnvironmentFingerprint source,
      List<String> garbageCollectors,
      String walRoot,
      String walRootUri,
      long totalSpace,
      long usableSpace,
      long unallocatedSpace) {
    return copy(
        source,
        source.jvmArguments(),
        garbageCollectors,
        walRoot,
        walRootUri,
        totalSpace,
        usableSpace,
        unallocatedSpace);
  }

  private static EnvironmentFingerprint copy(
      EnvironmentFingerprint source,
      List<String> jvmArguments,
      List<String> garbageCollectors,
      String walRoot,
      String walRootUri,
      long totalSpace,
      long usableSpace,
      long unallocatedSpace) {
    return new EnvironmentFingerprint(
        source.javaRuntime(),
        source.javaVersion(),
        source.javaVendor(),
        source.vmName(),
        jvmArguments,
        source.osName(),
        source.osVersion(),
        source.osArchitecture(),
        source.availableProcessors(),
        source.physicalMemoryBytes(),
        source.maximumHeapBytes(),
        garbageCollectors,
        source.cpuModel(),
        source.storageDevice(),
        source.filesystem(),
        source.powerPolicy(),
        walRoot,
        walRootUri,
        source.walFileStoreName(),
        source.walFileStoreType(),
        totalSpace,
        usableSpace,
        unallocatedSpace,
        source.runStartedAt(),
        source.runFinishedAt());
  }
}
