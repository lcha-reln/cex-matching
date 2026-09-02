package io.github.lchareln.cex.matching.testkit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

final class M10ReleaseEnvironmentTest {
  @Test
  void acceptsPortableActualFileStoreEvidenceWithoutEquatingOperatorLabels() {
    ObjectNode environment = environment();
    environment.put("storageDevice", "operator-device-label");
    environment.put("filesystem", "operator-filesystem-label");
    environment.put("walFileStoreName", "actual-store-name");
    environment.put("walFileStoreType", "actual-store-type");
    environment.put("walFileStoreUsableSpaceBytes", 0);
    environment.put("walFileStoreUnallocatedSpaceBytes", 0);

    assertDoesNotThrow(() -> M10ReleaseBundleVerifier.verifyEnvironment(environment));
  }

  @Test
  void requiresHeapCollectorsAndANormalizedAbsoluteFileUriWithoutParsingForeignWalRoot() {
    ObjectNode missingHeap = environment();
    missingHeap.remove("maximumHeapBytes");
    assertTrue(
        assertThrows(
                IllegalStateException.class,
                () -> M10ReleaseBundleVerifier.verifyEnvironment(missingHeap))
            .getMessage()
            .contains("maximumHeapBytes"));

    ObjectNode duplicateCollector = environment();
    ArrayNode collectors = (ArrayNode) duplicateCollector.path("garbageCollectorNames");
    collectors.add(collectors.path(0).stringValue());
    assertTrue(
        assertThrows(
                IllegalStateException.class,
                () -> M10ReleaseBundleVerifier.verifyEnvironment(duplicateCollector))
            .getMessage()
            .contains("duplicate"));

    ObjectNode unsortedCollectors = environment();
    ((ArrayNode) unsortedCollectors.path("garbageCollectorNames"))
        .removeAll()
        .add("Test Young GC")
        .add("Test Old GC");
    assertTrue(
        assertThrows(
                IllegalStateException.class,
                () -> M10ReleaseBundleVerifier.verifyEnvironment(unsortedCollectors))
            .getMessage()
            .contains("sort order"));

    ObjectNode blankJvmArgument = environment();
    ((ArrayNode) blankJvmArgument.path("jvmArguments")).add(" ");
    assertTrue(
        assertThrows(
                IllegalStateException.class,
                () -> M10ReleaseBundleVerifier.verifyEnvironment(blankJvmArgument))
            .getMessage()
            .contains("blank"));

    ObjectNode blankHumanWalRoot = environment();
    blankHumanWalRoot.put("walRoot", " ");
    assertTrue(
        assertThrows(
                IllegalStateException.class,
                () -> M10ReleaseBundleVerifier.verifyEnvironment(blankHumanWalRoot))
            .getMessage()
            .contains("walRoot"));

    ObjectNode nonFileUri = environment();
    nonFileUri.put("walRootUri", "https://example.invalid/wal");
    assertTrue(
        assertThrows(
                IllegalStateException.class,
                () -> M10ReleaseBundleVerifier.verifyEnvironment(nonFileUri))
            .getMessage()
            .contains("absolute file URI"));

    ObjectNode nonNormalizedUri = environment();
    nonNormalizedUri.put("walRootUri", "file:///var/m10/../wal");
    assertThrows(
        IllegalStateException.class,
        () -> M10ReleaseBundleVerifier.verifyEnvironment(nonNormalizedUri));

    ObjectNode relativeUri = environment();
    relativeUri.put("walRootUri", "file:relative/wal");
    assertThrows(
        IllegalStateException.class, () -> M10ReleaseBundleVerifier.verifyEnvironment(relativeUri));

    ObjectNode authorityUri = environment();
    authorityUri.put("walRootUri", "file://remote-host/m10/wal");
    assertThrows(
        IllegalStateException.class,
        () -> M10ReleaseBundleVerifier.verifyEnvironment(authorityUri));

    for (String unsafeUri :
        java.util.List.of(
            "file:///var/m10/%2E%2E/wal",
            "file:///var/m10/%2Fescape", "file:///var/m10/%5Cescape", "file:///var/m10/%00/wal")) {
      ObjectNode unsafeEnvironment = environment();
      unsafeEnvironment.put("walRootUri", unsafeUri);
      assertThrows(
          IllegalStateException.class,
          () -> M10ReleaseBundleVerifier.verifyEnvironment(unsafeEnvironment));
    }
  }

  @Test
  void rejectsBlankFileStoreIdentityAndSpaceBeyondTotal() {
    ObjectNode blankType = environment();
    blankType.put("walFileStoreType", " ");
    assertThrows(
        IllegalStateException.class, () -> M10ReleaseBundleVerifier.verifyEnvironment(blankType));

    ObjectNode excessiveUsable = environment();
    excessiveUsable.put("walFileStoreUsableSpaceBytes", 1_000_001L);
    assertTrue(
        assertThrows(
                IllegalStateException.class,
                () -> M10ReleaseBundleVerifier.verifyEnvironment(excessiveUsable))
            .getMessage()
            .contains("usable space exceeds"));

    ObjectNode excessiveUnallocated = environment();
    excessiveUnallocated.put("walFileStoreUnallocatedSpaceBytes", 1_000_001L);
    assertTrue(
        assertThrows(
                IllegalStateException.class,
                () -> M10ReleaseBundleVerifier.verifyEnvironment(excessiveUnallocated))
            .getMessage()
            .contains("unallocated space exceeds"));
  }

  private static ObjectNode environment() {
    ObjectNode environment = JsonSupport.MAPPER.createObjectNode();
    environment.put("javaRuntime", "test-runtime");
    environment.put("javaVersion", "25-test");
    environment.put("javaVendor", "test-vendor");
    environment.put("vmName", "test-vm");
    environment.putArray("jvmArguments");
    environment.put("osName", "test-os");
    environment.put("osVersion", "1");
    environment.put("osArchitecture", "test-arch");
    environment.put("availableProcessors", 8);
    environment.put("physicalMemoryBytes", 8_589_934_592L);
    environment.put("maximumHeapBytes", 2_147_483_648L);
    environment.putArray("garbageCollectorNames").add("Test Old GC").add("Test Young GC");
    environment.put("cpuModel", "test-cpu");
    environment.put("storageDevice", "test-device");
    environment.put("filesystem", "test-filesystem");
    environment.put("powerPolicy", "test-power-policy");
    environment.put("walRoot", "C:\\m10-release-wal");
    environment.put("walRootUri", "file:///C:/m10-release-wal");
    environment.put("walFileStoreName", "test-store");
    environment.put("walFileStoreType", "test-type");
    environment.put("walFileStoreTotalSpaceBytes", 1_000_000L);
    environment.put("walFileStoreUsableSpaceBytes", 700_000L);
    environment.put("walFileStoreUnallocatedSpaceBytes", 800_000L);
    environment.put("runStartedAt", "2026-09-01T00:00:00Z");
    environment.put("runFinishedAt", "2026-09-01T01:00:00Z");
    return environment;
  }
}
