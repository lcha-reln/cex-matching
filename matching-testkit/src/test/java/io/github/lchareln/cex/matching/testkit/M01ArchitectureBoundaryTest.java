package io.github.lchareln.cex.matching.testkit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class M01ArchitectureBoundaryTest {
  @Test
  void coreRemainsDeterministicSingleProcessAndDependencyFree() {
    M01ArchitectureGate.Report report = new M01ArchitectureGate().verify(M01TestPaths.root());

    assertTrue(report.passed(), report.violations().toString());
  }

  @Test
  void fullyQualifiedRuntimeAndNondeterministicApisCannotBypassTheGate(@TempDir Path root)
      throws IOException {
    Path source = root.resolve("matching-core/src/main/java/example/Candidate.java");
    Files.createDirectories(source.getParent());
    Files.createDirectories(root.resolve("matching-core"));
    Files.writeString(root.resolve("matching-core/build.gradle.kts"), "plugins { java }\n");
    Files.writeString(
        root.resolve("settings.gradle.kts"),
        "rootProject.name = \"candidate\"\ninclude(\"matching-core\", \"matching-testkit\")\n");

    List<String> forbiddenBodies =
        List.of(
            "new java.io.File(\"state\")",
            "java.nio.file.Path.of(\"state\")",
            "new java.net.URI(\"https://example.invalid\")",
            "java.time.Instant.now()",
            "new java.util.Random()",
            "new java.util.SplittableRandom()",
            "new java.security.SecureRandom()",
            "java.util.concurrent.ThreadLocalRandom.current()",
            "Math.random()",
            "Thread.ofPlatform()",
            "System.out",
            "System.getenv(\"M01\")",
            "new ProcessBuilder()",
            "Runtime.getRuntime()",
            "ProcessHandle.current()",
            "java.nio.channels.SocketChannel.open()");

    for (String body : forbiddenBodies) {
      Files.writeString(
          source,
          "package example; final class Candidate { Object value() { return " + body + "; } }\n");
      M01ArchitectureGate.Report report = new M01ArchitectureGate().verify(root);
      assertFalse(report.passed(), () -> "gate accepted forbidden source: " + body);
    }

    for (String staticImport : List.of("java.lang.Math.random", "java.lang.System.nanoTime")) {
      Files.writeString(
          source,
          "package example; import static "
              + staticImport
              + "; final class Candidate { Object value() { return "
              + staticImport.substring(staticImport.lastIndexOf('.') + 1)
              + "(); } }\n");
      M01ArchitectureGate.Report report = new M01ArchitectureGate().verify(root);
      assertFalse(report.passed(), () -> "gate accepted forbidden static import: " + staticImport);
    }

    for (String sourceText :
        List.of(
            "package example; final class Candidate { synchronized Object value() { return null; } }\n",
            "package example; final class Candidate { Object value() throws Exception { wait(); return null; } }\n",
            "package example; final class Candidate { Object value() { notify(); return null; } }\n",
            "package example; final class Candidate { Object value() { notifyAll(); return null; } }\n")) {
      Files.writeString(source, sourceText);
      M01ArchitectureGate.Report report = new M01ArchitectureGate().verify(root);
      assertFalse(report.passed(), () -> "gate accepted lock or monitor source: " + sourceText);
    }
  }
}
