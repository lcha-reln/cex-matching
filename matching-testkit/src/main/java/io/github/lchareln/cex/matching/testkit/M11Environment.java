package io.github.lchareln.cex.matching.testkit;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import tools.jackson.databind.node.ObjectNode;

/** Captures the finite local environment in which the real single-member harness ran. */
final class M11Environment {
  ObjectNode capture(Path clusterRoot, Instant started, Instant finished) {
    try {
      Path realRoot = clusterRoot.toAbsolutePath().normalize().toRealPath();
      var store = Files.getFileStore(realRoot);
      ObjectNode report = JsonSupport.MAPPER.createObjectNode();
      report.put("schemaVersion", "matching.m11.environment.v1");
      report.put("status", M11CheckRunner.PASS);
      report.put("javaRuntime", System.getProperty("java.runtime.name"));
      report.put("javaVersion", System.getProperty("java.runtime.version"));
      report.put("javaVendor", System.getProperty("java.vendor"));
      report.put("vmName", System.getProperty("java.vm.name"));
      ManagementFactory.getRuntimeMXBean()
          .getInputArguments()
          .forEach(report.putArray("jvmArguments")::add);
      report.put("osName", System.getProperty("os.name"));
      report.put("osVersion", System.getProperty("os.version"));
      report.put("osArchitecture", System.getProperty("os.arch"));
      report.put("availableProcessors", Runtime.getRuntime().availableProcessors());
      report.put("maximumHeapBytes", Runtime.getRuntime().maxMemory());
      report.put("clusterRoot", realRoot.toString());
      report.put(
          "fileStoreName", store.name().isBlank() ? realRoot.getRoot().toString() : store.name());
      report.put("fileStoreType", store.type().isBlank() ? "local-filesystem" : store.type());
      report.put("runStartedAt", started.toString());
      report.put("runFinishedAt", finished.toString());
      return report;
    } catch (IOException failure) {
      throw new IllegalStateException("cannot capture M11 environment", failure);
    }
  }
}
