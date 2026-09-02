package io.github.lchareln.cex.matching.benchmark;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class M10QualificationRunnerTest {
  @Test
  void freezesTheRawAdmissionAndCompletionTimeContract() {
    ObjectNode contract = JsonMapper.builder().build().createObjectNode();

    M10QualificationRunner.writeRawTimeContract(contract);

    assertEquals(4, contract.size());
    assertEquals("admissionDecisionNanos", contract.path("admissionTimestamp").stringValue());
    assertEquals(
        "ADMISSION_GATE_DECISION", contract.path("admissionObservationKind").stringValue());
    assertEquals("ownerCompletedNanos", contract.path("completionTimestamp").stringValue());
    assertEquals("OWNER_COMPLETED_UNDER_GATE", contract.path("completionTimeOrigin").stringValue());
  }

  @Test
  void serializesEveryReleaseEnvironmentDimension(@TempDir Path walRoot) throws Exception {
    Instant started = Instant.parse("2026-09-01T00:00:00Z");
    EnvironmentFingerprint fingerprint =
        EnvironmentFingerprint.capture(
            walRoot,
            "test-cpu",
            "operator-device",
            "operator-filesystem",
            "test-power-policy",
            started,
            started.plusSeconds(1));
    ObjectNode environment = JsonMapper.builder().build().createObjectNode();

    M10QualificationRunner.writeEnvironment(environment, fingerprint);

    assertEquals(25, environment.size());
    assertEquals(fingerprint.javaRuntime(), text(environment, "javaRuntime"));
    assertEquals(fingerprint.javaVersion(), text(environment, "javaVersion"));
    assertEquals(fingerprint.javaVendor(), text(environment, "javaVendor"));
    assertEquals(fingerprint.vmName(), text(environment, "vmName"));
    assertEquals(
        fingerprint.jvmArguments(),
        environment.path("jvmArguments").valueStream().map(JsonNode::stringValue).toList());
    assertEquals(fingerprint.osName(), text(environment, "osName"));
    assertEquals(fingerprint.osVersion(), text(environment, "osVersion"));
    assertEquals(fingerprint.osArchitecture(), text(environment, "osArchitecture"));
    assertEquals(
        fingerprint.availableProcessors(), environment.path("availableProcessors").intValue());
    assertEquals(
        fingerprint.physicalMemoryBytes(), environment.path("physicalMemoryBytes").longValue());
    assertEquals(fingerprint.maximumHeapBytes(), environment.path("maximumHeapBytes").longValue());
    assertEquals(
        fingerprint.garbageCollectorNames(),
        environment
            .path("garbageCollectorNames")
            .valueStream()
            .map(JsonNode::stringValue)
            .toList());
    assertEquals(fingerprint.cpuModel(), text(environment, "cpuModel"));
    assertEquals(fingerprint.storageDevice(), text(environment, "storageDevice"));
    assertEquals(fingerprint.filesystem(), text(environment, "filesystem"));
    assertEquals(fingerprint.powerPolicy(), text(environment, "powerPolicy"));
    assertEquals(fingerprint.walRoot(), text(environment, "walRoot"));
    assertEquals(fingerprint.walRootUri(), text(environment, "walRootUri"));
    assertEquals(fingerprint.walFileStoreName(), text(environment, "walFileStoreName"));
    assertEquals(fingerprint.walFileStoreType(), text(environment, "walFileStoreType"));
    assertEquals(
        fingerprint.walFileStoreTotalSpaceBytes(),
        environment.path("walFileStoreTotalSpaceBytes").longValue());
    assertEquals(
        fingerprint.walFileStoreUsableSpaceBytes(),
        environment.path("walFileStoreUsableSpaceBytes").longValue());
    assertEquals(
        fingerprint.walFileStoreUnallocatedSpaceBytes(),
        environment.path("walFileStoreUnallocatedSpaceBytes").longValue());
    assertEquals(fingerprint.runStartedAt().toString(), text(environment, "runStartedAt"));
    assertEquals(fingerprint.runFinishedAt().toString(), text(environment, "runFinishedAt"));
  }

  private static String text(ObjectNode object, String field) {
    return object.path(field).stringValue();
  }
}
