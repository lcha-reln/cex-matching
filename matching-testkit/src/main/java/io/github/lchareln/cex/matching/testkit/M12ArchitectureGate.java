package io.github.lchareln.cex.matching.testkit;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Preserves the M11 business and wire boundaries while admitting only the M12 HA adapter axis. */
final class M12ArchitectureGate {
  private static final String M11_COMMIT = "6997e05cea81cb93b883e882c8d75887d0622a22";
  private static final String CLUSTERED_SERVICE_SOURCE =
      "matching-cluster-runtime/src/main/java/io/github/lchareln/cex/matching/cluster/M11ClusteredMatchingService.java";
  private static final String EGRESS_BEHAVIOR_TEST_SOURCE =
      "matching-cluster-runtime/src/test/java/io/github/lchareln/cex/matching/cluster/M11BoundedProgressTest.java";
  private static final List<String> EGRESS_BEHAVIOR_TESTS =
      List.of(
          "persistentEgressBackpressureBecomesUndeliveredWitnessAfterBusinessResultBinding",
          "healthyTransientBackpressureStillRetriesUntilEgressSucceeds",
          "terminalPublicationAndClosingSessionDoNotCrashCommittedService",
          "closedMaxPositionAndComponentFailureAreDiagnosticsNotServiceFailures",
          "retryAfterUndeliveredEgressReplaysTheBoundResultWithoutSecondEffect");
  private static final List<String> FROZEN_PROTOCOL_SOURCES =
      List.of(
          "matching-cluster-runtime/src/main/java/io/github/lchareln/cex/matching/cluster/M11CommandRequest.java",
          "matching-cluster-runtime/src/main/java/io/github/lchareln/cex/matching/cluster/M11CommandResponse.java",
          "matching-cluster-runtime/src/main/java/io/github/lchareln/cex/matching/cluster/M11RequestCodec.java",
          "matching-cluster-runtime/src/main/java/io/github/lchareln/cex/matching/cluster/M11ResponseCodec.java",
          "matching-cluster-runtime/src/main/java/io/github/lchareln/cex/matching/cluster/M11Snapshot.java",
          "matching-cluster-runtime/src/main/java/io/github/lchareln/cex/matching/cluster/M11SnapshotCodec.java");

  ObjectNode run(Path repositoryRoot) {
    Path root = repositoryRoot.toAbsolutePath().normalize();
    List<String> violations = new ArrayList<>();
    ObjectNode inherited = new M11ArchitectureGate().run(root);

    String baselineCore = git(root, "rev-parse", M11_COMMIT + ":matching-core").strip();
    String currentCore = git(root, "rev-parse", "HEAD:matching-core").strip();
    if (!baselineCore.equals(currentCore)) {
      violations.add("matching-core differs from the M11 completion baseline");
    }
    String baselineGoldens =
        git(root, "rev-parse", M11_COMMIT + ":matching-testkit/src/test/resources/m11/goldens")
            .strip();
    String currentGoldens =
        git(root, "rev-parse", "HEAD:matching-testkit/src/test/resources/m11/goldens").strip();
    if (!baselineGoldens.equals(currentGoldens)) {
      violations.add("M11 application goldens differ from the completion baseline");
    }

    Map<String, String> protocolHashes = new LinkedHashMap<>();
    for (String source : FROZEN_PROTOCOL_SOURCES) {
      byte[] expected = gitBytes(root, "show", M11_COMMIT + ":" + source);
      byte[] actual = readBytes(root.resolve(source));
      String expectedDigest = Hashing.sha256Hex(expected);
      String actualDigest = Hashing.sha256Hex(actual);
      protocolHashes.put(source, actualDigest);
      if (!expectedDigest.equals(actualDigest)) {
        violations.add("M11 wire source changed: " + source);
      }
    }

    byte[] baselineAdapter = gitBytes(root, "show", M11_COMMIT + ":" + CLUSTERED_SERVICE_SOURCE);
    byte[] currentAdapter = readBytes(root.resolve(CLUSTERED_SERVICE_SOURCE));
    String baselineAdapterSha256 = Hashing.sha256Hex(baselineAdapter);
    String currentAdapterSha256 = Hashing.sha256Hex(currentAdapter);
    boolean transportAdapterCorrected = !baselineAdapterSha256.equals(currentAdapterSha256);
    if (!transportAdapterCorrected) {
      violations.add("M12 committed-result egress correction is missing from the M11 adapter");
    }
    String egressBehaviorTests = readString(root.resolve(EGRESS_BEHAVIOR_TEST_SOURCE));
    long presentEgressBehaviorTests =
        EGRESS_BEHAVIOR_TESTS.stream()
            .filter(name -> occurrences(egressBehaviorTests, "void " + name + "(") == 1)
            .count();
    if (presentEgressBehaviorTests != EGRESS_BEHAVIOR_TESTS.size()) {
      violations.add("M12 egress correction behavior-test set changed");
    }

    int childMainCount = sourceCount(root, "public final class M12ClusterMemberMain");
    int externalControllerCount =
        occurrences(
            readString(
                root.resolve(
                    "matching-testkit/src/main/java/io/github/lchareln/cex/matching/testkit/M12ThreeMemberProcessHarness.java")),
            ".destroyForcibly()");
    int serviceFaultControllerCount =
        occurrences(
            readString(
                root.resolve(
                    "matching-cluster-runtime/src/main/java/io/github/lchareln/cex/matching/cluster/M11ClusteredMatchingService.java")),
            "destroyForcibly");
    if (childMainCount != 1) {
      violations.add("M12 must expose exactly one member child-process main");
    }
    if (serviceFaultControllerCount != 0) {
      violations.add("the ClusteredService must not own the M12 fault controller");
    }
    if (externalControllerCount < 1) {
      violations.add("no external child-process fail-stop controller is present");
    }
    require(violations.isEmpty(), "M12 architecture violations: " + violations);

    ObjectNode report = JsonSupport.MAPPER.createObjectNode();
    report.put("schemaVersion", "matching.m12.architecture.v1");
    report.put("status", M12CheckRunner.PASS);
    report.put("m11BaselineCommit", M11_COMMIT);
    report.put("m11CoreTree", baselineCore);
    report.put("headCoreTree", currentCore);
    report.put("matchingCoreByteIdentical", baselineCore.equals(currentCore));
    report.put("m11GoldensTree", baselineGoldens);
    report.put("headGoldensTree", currentGoldens);
    report.put("m11GoldensByteIdentical", baselineGoldens.equals(currentGoldens));
    report.put("m11WireSourcesByteIdentical", true);
    ObjectNode hashes = report.putObject("m11WireSourceSha256");
    protocolHashes.forEach(hashes::put);
    report.put("m11ClusteredServiceAdapterByteIdentical", !transportAdapterCorrected);
    report.put("m11ClusteredServiceAdapterBaselineSha256", baselineAdapterSha256);
    report.put("m12ClusteredServiceAdapterSha256", currentAdapterSha256);
    report.put("transportAdapterCorrection", "COMMITTED_RESULT_EGRESS_FAILURE_RECORDED_NOT_THROWN");
    report.put("transportAdapterCorrectionPresent", transportAdapterCorrected);
    report.put("transportAdapterCorrectionBehaviorTests", presentEgressBehaviorTests);
    report.put("aeronProductionModule", "matching-cluster-runtime");
    report.put("coreInfrastructureFree", inherited.path("coreInfrastructureFree").booleanValue());
    report.put(
        "callbackReachableStandaloneWalReferences",
        inherited.path("callbackReachableStandaloneWalReferences").intValue());
    report.put(
        "callbackReachableExternalIoReferences",
        inherited.path("callbackReachableExternalIoReferences").intValue());
    report.put(
        "logCallbackBusinessApplyCalls",
        inherited.path("logCallbackBusinessApplyCalls").intValue());
    report.put(
        "nonLogCallbackBusinessApplyCalls",
        inherited.path("nonLogCallbackBusinessApplyCalls").intValue());
    report.put(
        "runtimeMetadataDigestStable",
        inherited.path("runtimeMetadataDigestStable").booleanValue());
    report.put("memberChildMainCount", childMainCount);
    report.put("externalFaultControllerSites", externalControllerCount);
    report.put("clusteredServiceFaultControllerSites", serviceFaultControllerCount);
    report.put("externalServices", false);
    report.put("standaloneApplicationWal", false);
    ArrayNode details = report.putArray("violations");
    violations.forEach(details::add);
    return report;
  }

  private static int sourceCount(Path root, String needle) {
    int count = 0;
    try (var paths = Files.walk(root)) {
      for (Path path : paths.filter(Files::isRegularFile).toList()) {
        String portable =
            root.relativize(path).toString().replace(path.getFileSystem().getSeparator(), "/");
        if (!portable.endsWith("M12ClusterMemberMain.java")
            || portable.contains("/build/")
            || portable.startsWith("build/")) {
          continue;
        }
        count += occurrences(Files.readString(path), needle);
      }
      return count;
    } catch (IOException failure) {
      throw new IllegalStateException("cannot scan M12 source tree", failure);
    }
  }

  private static int occurrences(String text, String needle) {
    int count = 0;
    int offset = 0;
    while ((offset = text.indexOf(needle, offset)) >= 0) {
      count++;
      offset += needle.length();
    }
    return count;
  }

  private static String git(Path root, String... arguments) {
    return new String(gitBytes(root, arguments), StandardCharsets.UTF_8);
  }

  private static byte[] gitBytes(Path root, String... arguments) {
    List<String> command = new ArrayList<>();
    command.add("git");
    command.addAll(List.of(arguments));
    try {
      Process process = new ProcessBuilder(command).directory(root.toFile()).start();
      byte[] output = process.getInputStream().readAllBytes();
      String error = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
      int exit = process.waitFor();
      require(exit == 0, "git command failed: " + error.strip());
      return output;
    } catch (IOException failure) {
      throw new IllegalStateException("cannot execute git", failure);
    } catch (InterruptedException failure) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("git interrupted", failure);
    }
  }

  private static byte[] readBytes(Path path) {
    try {
      return Files.readAllBytes(path);
    } catch (IOException failure) {
      throw new IllegalStateException("cannot read " + path, failure);
    }
  }

  private static String readString(Path path) {
    return new String(readBytes(path), StandardCharsets.UTF_8);
  }

  private static void require(boolean condition, String message) {
    if (!condition) {
      throw new M12SemanticFailure(message);
    }
  }
}
