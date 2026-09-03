package io.github.lchareln.cex.matching.cluster;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class M11ArchitectureBoundaryTest {
  @Test
  void aeronIsIsolatedAndClusterRuntimeHasNoSecondApplicationWal() throws Exception {
    Path root = Path.of(System.getProperty("matching.repositoryRoot"));
    String core = allJava(root.resolve("matching-core/src/main/java"));
    String local = allJava(root.resolve("matching-local-runtime/src/main/java"));
    String cluster = allJava(root.resolve("matching-cluster-runtime/src/main/java"));
    assertFalse(core.contains("io.aeron"));
    assertFalse(local.contains("io.aeron"));
    for (String forbidden :
        List.of(
            "new LocalMatchingRuntime",
            "LocalMatchingRuntime.open",
            "new LocalMatchingService",
            "new SegmentedWal",
            "new SnapshotStore",
            "new WalConfig")) {
      assertFalse(cluster.contains(forbidden), forbidden);
    }
    assertTrue(cluster.contains("implements ClusteredService"));

    String dependencies =
        Files.readString(root.resolve("matching-cluster-runtime/build.gradle.kts"));
    assertFalse(dependencies.contains("matching-testkit"));
    assertFalse(dependencies.contains("matching-reference"));
    assertFalse(dependencies.contains("matching-benchmarks"));

    assertAeronIndependentWitness(M11SnapshotAdminAcceptance.class);
    assertAeronIndependentWitness(M11SnapshotCompletion.class);
  }

  private static void assertAeronIndependentWitness(Class<? extends Record> witness) {
    assertFalse(
        Arrays.stream(witness.getRecordComponents())
            .map(component -> component.getType().getName())
            .anyMatch(name -> name.startsWith("io.aeron") || name.startsWith("org.agrona")),
        witness.getName());
  }

  private static String allJava(Path directory) throws Exception {
    StringBuilder combined = new StringBuilder();
    try (var files = Files.walk(directory)) {
      for (Path file : files.filter(path -> path.toString().endsWith(".java")).sorted().toList()) {
        combined.append(Files.readString(file));
      }
    }
    return combined.toString();
  }
}
