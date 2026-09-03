package io.github.lchareln.cex.matching.testkit;

import io.github.lchareln.cex.matching.benchmark.EnvironmentFingerprint;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import tools.jackson.databind.node.ObjectNode;

/** Captures the machine boundary for one finite localhost three-member correctness run. */
final class M12Environment {
  ObjectNode capture(Path clusterRoot, Instant started, Instant finished) {
    try {
      EnvironmentFingerprint value =
          EnvironmentFingerprint.capture(
              clusterRoot,
              "M12_CORRECTNESS_CPU_NOT_PROFILED",
              "M12_LOCAL_CLUSTER_STORAGE_NOT_QUALIFIED",
              "M12_LOCAL_FILESYSTEM_NOT_QUALIFIED",
              "M12_CORRECTNESS_POWER_POLICY_NOT_PROFILED",
              started,
              finished);
      ObjectNode report = JsonSupport.MAPPER.createObjectNode();
      report.put("schemaVersion", "matching.m12.environment.v1");
      report.put("status", M12CheckRunner.PASS);
      report.put("javaRuntime", value.javaRuntime());
      report.put("javaVersion", value.javaVersion());
      report.put("javaVendor", value.javaVendor());
      report.put("vmName", value.vmName());
      value.jvmArguments().forEach(report.putArray("jvmArguments")::add);
      report.put("osName", value.osName());
      report.put("osVersion", value.osVersion());
      report.put("osArchitecture", value.osArchitecture());
      report.put("availableProcessors", value.availableProcessors());
      report.put("physicalMemoryBytes", value.physicalMemoryBytes());
      report.put("maximumHeapBytes", value.maximumHeapBytes());
      value.garbageCollectorNames().forEach(report.putArray("garbageCollectorNames")::add);
      report.put("cpuModel", value.cpuModel());
      report.put("storageDevice", value.storageDevice());
      report.put("filesystem", value.filesystem());
      report.put("powerPolicy", value.powerPolicy());
      report.put("walRoot", value.walRoot());
      report.put("walRootUri", value.walRootUri());
      report.put("walFileStoreName", value.walFileStoreName());
      report.put("walFileStoreType", value.walFileStoreType());
      report.put("walFileStoreTotalSpaceBytes", value.walFileStoreTotalSpaceBytes());
      report.put("walFileStoreUsableSpaceBytes", value.walFileStoreUsableSpaceBytes());
      report.put("walFileStoreUnallocatedSpaceBytes", value.walFileStoreUnallocatedSpaceBytes());
      report.put("runStartedAt", value.runStartedAt().toString());
      report.put("runFinishedAt", value.runFinishedAt().toString());
      report.put("correctnessOnly", true);
      report.put("performanceQualified", false);
      report.put("singleHost", true);
      return report;
    } catch (IOException failure) {
      throw new IllegalStateException("cannot capture M12 environment", failure);
    }
  }
}
