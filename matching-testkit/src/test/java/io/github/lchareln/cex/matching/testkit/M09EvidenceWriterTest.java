package io.github.lchareln.cex.matching.testkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;

final class M09EvidenceWriterTest {
  @Test
  void cleanAnnotatedUnitTagPublishesEveryBoundArtifactAndHonestLimits(@TempDir Path temporary)
      throws IOException {
    Lab lab = createLab(temporary, true);
    AtomicInteger checks = new AtomicInteger();
    M09EvidenceWriter writer =
        new M09EvidenceWriter(
            (root, reports) -> {
              checks.incrementAndGet();
              return new M09CheckRunner.Result(M09CheckRunner.PASS, reports.resolve("check.json"));
            });

    M09EvidenceWriter.Result result =
        writer.write(lab.root(), lab.reports(), lab.evidence(), M09EvidenceWriter.UNIT_TAG);

    assertEquals(1, checks.get());
    JsonNode manifest = JsonSupport.parse(Files.readAllBytes(result.manifestPath()));
    assertEquals("M09", manifest.path("unit").stringValue());
    assertTrue(manifest.path("productRelease").isNull());
    assertEquals(
        M09EvidenceWriter.REQUIRED_CLAIMS,
        manifest.path("claims").valueStream().map(node -> node.path("id").stringValue()).toList());
    assertEquals(
        M09EvidenceWriter.LIMITATIONS,
        manifest.path("limitations").valueStream().map(JsonNode::stringValue).toList());
    assertTrue(
        M09EvidenceWriter.LIMITATIONS.stream()
            .anyMatch(value -> value.contains("externally deleted final active segment")));
    List<String> paths = new ArrayList<>();
    for (JsonNode claim : manifest.path("claims")) {
      for (JsonNode artifact : claim.path("artifacts")) {
        String relative = artifact.path("path").stringValue();
        paths.add(relative);
        assertEquals(
            artifact.path("sha256").stringValue(),
            Hashing.sha256Hex(Files.readAllBytes(lab.evidence().resolve(relative))));
      }
    }
    assertEquals(paths.size(), new LinkedHashSet<>(paths).size());
    assertEquals(M09EvidenceWriter.EXPECTED_ARTIFACT_PATHS, new LinkedHashSet<>(paths));
    assertEquals(
        result.manifestSha256(), Hashing.sha256Hex(Files.readAllBytes(result.manifestPath())));
  }

  @Test
  void lightweightCompleteTagIsRejected(@TempDir Path temporary) {
    Lab lab = createLab(temporary, false);
    IllegalStateException failure =
        assertThrows(
            IllegalStateException.class,
            () ->
                writer()
                    .write(lab.root(), lab.reports(), lab.evidence(), M09EvidenceWriter.UNIT_TAG));
    assertTrue(failure.getMessage().contains("not annotated"));
    assertFalse(Files.exists(lab.evidence().resolve("manifest.json")));
  }

  @Test
  void dirtyTreeAndProductReleaseTagAreFailClosed(@TempDir Path temporary) {
    Lab dirty = createLab(temporary.resolve("dirty"), true);
    write(dirty.root().resolve("dirty.txt"), "dirty\n");
    assertThrows(
        IllegalStateException.class,
        () ->
            writer()
                .write(
                    dirty.root(), dirty.reports(), dirty.evidence(), M09EvidenceWriter.UNIT_TAG));

    Lab product = createLab(temporary.resolve("product"), true);
    git(product.root(), "tag", "-a", "matching-9.9.9", "-m", "forbidden release");
    IllegalStateException failure =
        assertThrows(
            IllegalStateException.class,
            () ->
                writer()
                    .write(
                        product.root(),
                        product.reports(),
                        product.evidence(),
                        M09EvidenceWriter.UNIT_TAG));
    assertTrue(failure.getMessage().contains("matching-*"));
  }

  @Test
  void nonPassAndUnexpectedCheckPathPublishNothing(@TempDir Path temporary) {
    Lab failed = createLab(temporary.resolve("failed"), true);
    IllegalStateException nonPass =
        assertThrows(
            IllegalStateException.class,
            () ->
                new M09EvidenceWriter(
                        (root, reports) ->
                            new M09CheckRunner.Result(
                                M09CheckRunner.STUDENT_FAILURE, reports.resolve("check.json")))
                    .write(
                        failed.root(),
                        failed.reports(),
                        failed.evidence(),
                        M09EvidenceWriter.UNIT_TAG));
    assertTrue(nonPass.getMessage().contains("not PASS"));
    assertFalse(Files.exists(failed.evidence().resolve("manifest.json")));

    Lab wrongPath = createLab(temporary.resolve("wrong-path"), true);
    IllegalStateException unexpected =
        assertThrows(
            IllegalStateException.class,
            () ->
                new M09EvidenceWriter(
                        (root, reports) ->
                            new M09CheckRunner.Result(
                                M09CheckRunner.PASS, reports.resolve("unexpected.json")))
                    .write(
                        wrongPath.root(),
                        wrongPath.reports(),
                        wrongPath.evidence(),
                        M09EvidenceWriter.UNIT_TAG));
    assertTrue(unexpected.getMessage().contains("unexpected report path"));
    assertFalse(Files.exists(wrongPath.evidence().resolve("manifest.json")));
  }

  @Test
  void productTagAddedDuringCheckOrAfterPublishFailsClosed(@TempDir Path temporary) {
    Lab duringCheck = createLab(temporary.resolve("during-check"), true);
    M09EvidenceWriter checkRace =
        new M09EvidenceWriter(
            (root, reports) -> {
              git(root, "tag", "-a", "matching-9.9.8", "-m", "raced during check");
              return new M09CheckRunner.Result(M09CheckRunner.PASS, reports.resolve("check.json"));
            });
    IllegalStateException beforePublish =
        assertThrows(
            IllegalStateException.class,
            () ->
                checkRace.write(
                    duringCheck.root(),
                    duringCheck.reports(),
                    duringCheck.evidence(),
                    M09EvidenceWriter.UNIT_TAG));
    assertTrue(beforePublish.getMessage().contains("matching-*"));
    assertFalse(Files.exists(duringCheck.evidence().resolve("manifest.json")));

    Lab afterPublish = createLab(temporary.resolve("after-publish"), true);
    M09EvidenceWriter postPublishRace =
        new M09EvidenceWriter(
            (root, reports) ->
                new M09CheckRunner.Result(M09CheckRunner.PASS, reports.resolve("check.json")),
            new M09EvidenceWriter.BoundaryHook() {
              @Override
              public void beforeFinalVerification(Path root) {}

              @Override
              public void beforePostPublishVerification(Path root) {
                git(root, "tag", "-a", "matching-9.9.7", "-m", "raced after publish");
              }
            });
    IllegalStateException afterPublishFailure =
        assertThrows(
            IllegalStateException.class,
            () ->
                postPublishRace.write(
                    afterPublish.root(),
                    afterPublish.reports(),
                    afterPublish.evidence(),
                    M09EvidenceWriter.UNIT_TAG));
    assertTrue(afterPublishFailure.getMessage().contains("matching-*"));
    assertFalse(Files.exists(afterPublish.evidence().resolve("manifest.json")));
  }

  @Test
  void completeTagMustBeExactHeadAndUnitTagMustBeExact(@TempDir Path temporary) {
    Lab movedHead = createLab(temporary.resolve("moved-head"), true);
    write(movedHead.root().resolve("later.txt"), "later\n");
    git(movedHead.root(), "add", ".");
    git(movedHead.root(), "commit", "-q", "-m", "test: move HEAD after unit tag");
    IllegalStateException mismatch =
        assertThrows(
            IllegalStateException.class,
            () ->
                writer()
                    .write(
                        movedHead.root(),
                        movedHead.reports(),
                        movedHead.evidence(),
                        M09EvidenceWriter.UNIT_TAG));
    assertTrue(mismatch.getMessage().contains("does not peel to HEAD"));

    Lab wrongTag = createLab(temporary.resolve("wrong-tag"), true);
    IllegalStateException wrong =
        assertThrows(
            IllegalStateException.class,
            () ->
                writer()
                    .write(
                        wrongTag.root(),
                        wrongTag.reports(),
                        wrongTag.evidence(),
                        "course/m09-start"));
    assertTrue(wrong.getMessage().contains("invalid M09 complete tag"));
  }

  @Test
  void inheritedAndStartTagsMustBeAnnotatedAncestors(@TempDir Path temporary) {
    Lab missing = createLab(temporary.resolve("missing-inherited"), true);
    git(missing.root(), "tag", "-d", "course/m08-complete");
    assertThrows(
        IllegalStateException.class,
        () ->
            writer()
                .write(
                    missing.root(),
                    missing.reports(),
                    missing.evidence(),
                    M09EvidenceWriter.UNIT_TAG));

    Lab lightweight = createLab(temporary.resolve("lightweight-inherited"), true);
    git(lightweight.root(), "tag", "-d", "course/m08-complete");
    git(lightweight.root(), "tag", "course/m08-complete");
    IllegalStateException lightweightFailure =
        assertThrows(
            IllegalStateException.class,
            () ->
                writer()
                    .write(
                        lightweight.root(),
                        lightweight.reports(),
                        lightweight.evidence(),
                        M09EvidenceWriter.UNIT_TAG));
    assertTrue(lightweightFailure.getMessage().contains("not annotated"));

    Lab unrelated = createLab(temporary.resolve("unrelated-inherited"), true);
    String tree = git(unrelated.root(), "rev-parse", "HEAD^{tree}").strip();
    String unrelatedCommit =
        git(unrelated.root(), "commit-tree", tree, "-m", "unrelated inherited boundary").strip();
    git(unrelated.root(), "tag", "-d", "course/m08-complete");
    git(
        unrelated.root(),
        "tag",
        "-a",
        "course/m08-complete",
        unrelatedCommit,
        "-m",
        "unrelated inherited boundary");
    IllegalStateException unrelatedFailure =
        assertThrows(
            IllegalStateException.class,
            () ->
                writer()
                    .write(
                        unrelated.root(),
                        unrelated.reports(),
                        unrelated.evidence(),
                        M09EvidenceWriter.UNIT_TAG));
    assertTrue(unrelatedFailure.getMessage().contains("not an ancestor"));

    Lab lightweightStart = createLab(temporary.resolve("lightweight-start"), true);
    git(lightweightStart.root(), "tag", "-d", "course/m09-start");
    git(lightweightStart.root(), "tag", "course/m09-start");
    assertThrows(
        IllegalStateException.class,
        () ->
            writer()
                .write(
                    lightweightStart.root(),
                    lightweightStart.reports(),
                    lightweightStart.evidence(),
                    M09EvidenceWriter.UNIT_TAG));
  }

  @Test
  void missingExpectedReportLeavesNoManifestOrStaging(@TempDir Path temporary) throws IOException {
    Lab lab = createLab(temporary, true);
    Files.delete(lab.reports().resolve("storage-inventory.json"));
    assertThrows(
        IllegalStateException.class,
        () ->
            writer().write(lab.root(), lab.reports(), lab.evidence(), M09EvidenceWriter.UNIT_TAG));
    assertFalse(Files.exists(lab.evidence().resolve("manifest.json")));
    try (var paths = Files.list(lab.evidence().getParent())) {
      assertTrue(
          paths.noneMatch(path -> path.getFileName().toString().startsWith(".M09-staging-")));
    }
  }

  @Test
  void symlinkedEvidenceParentCannotEscapeTheRepository(@TempDir Path temporary)
      throws IOException {
    Lab lab = createLab(temporary.resolve("symlink"), true);
    Path outside = temporary.resolve("outside");
    Files.createDirectories(outside);
    Path evidenceParent = lab.evidence().getParent();
    Files.createDirectories(evidenceParent.getParent());
    Files.createSymbolicLink(evidenceParent, outside);

    IllegalStateException failure =
        assertThrows(
            IllegalStateException.class,
            () ->
                writer()
                    .write(lab.root(), lab.reports(), lab.evidence(), M09EvidenceWriter.UNIT_TAG));

    assertTrue(failure.getMessage().contains("symlink path component"));
    try (var files = Files.list(outside)) {
      assertTrue(files.findAny().isEmpty());
    }
  }

  private static M09EvidenceWriter writer() {
    return new M09EvidenceWriter(
        (root, reports) ->
            new M09CheckRunner.Result(M09CheckRunner.PASS, reports.resolve("check.json")));
  }

  private static Lab createLab(Path temporary, boolean annotatedTag) {
    Path root = temporary.resolve("repo");
    Path source = Path.of(System.getProperty("matching.repositoryRoot"));
    copy(
        source.resolve("schemas/cex.lab-evidence.v1.schema.json"),
        root.resolve("schemas/cex.lab-evidence.v1.schema.json"));
    copy(
        source.resolve(M09CheckRunner.CHECK_SCHEMA_PATH),
        root.resolve(M09CheckRunner.CHECK_SCHEMA_PATH));
    copy(
        source.resolve(M09StartCheckRunner.FIXED_CORPUS_PATH),
        root.resolve(M09StartCheckRunner.FIXED_CORPUS_PATH));
    copy(
        source.resolve(M09StartCheckRunner.GENERATOR_PATH),
        root.resolve(M09StartCheckRunner.GENERATOR_PATH));
    write(root.resolve(".gitignore"), "/build/\n");
    write(root.resolve("course.properties"), courseProperties());
    git(root, "init", "-q");
    git(root, "config", "user.name", "M09 Evidence Test");
    git(root, "config", "user.email", "m09-evidence@example.invalid");
    git(root, "config", "commit.gpgsign", "false");
    git(root, "add", ".");
    git(root, "commit", "-q", "-m", "test: freeze M09 evidence inputs");
    git(root, "tag", "-a", "course/m08-complete", "-m", "test: inherited M08 complete");
    git(root, "tag", "-a", "course/m09-start", "-m", "test: M09 start");
    if (annotatedTag) {
      git(root, "tag", "-a", M09EvidenceWriter.UNIT_TAG, "-m", "test: M09 complete");
    } else {
      git(root, "tag", M09EvidenceWriter.UNIT_TAG);
    }
    Path reports = root.resolve("build/reports/m09");
    for (String name : M09EvidenceWriter.REPORT_ARTIFACTS) {
      write(
          reports.resolve(name),
          "check.json".equals(name)
              ? check()
              : name.endsWith(".json")
                  ? "{\"schemaVersion\":\"m09-test-artifact\",\"status\":\"PASS\"}\n"
                  : "M09 test artifact " + name + "\n");
    }
    return new Lab(root, reports, root.resolve("build/lab-evidence/M09"));
  }

  private static String courseProperties() {
    return """
        case=high-availability-cex
        profile=SPOT-CEX-1.0
        planVersion=0.11
        project=matching
        unit=M09
        lifecycle=COMPLETE
        designDepth=IMPLEMENTED
        startRef=course/m09-start
        completeRef=course/m09-complete
        m09Check.expectedStatus=PASS
        evidencePath=build/lab-evidence/M09/manifest.json
        """;
  }

  private static String check() {
    return """
        {
          "schemaVersion": "matching.m09.check.v2",
          "unit": "M09",
          "status": "PASS",
          "contractPlanVersion": "0.11",
          "inheritedM08": {"unit": "M08", "status": "PASS", "completeRef": "course/m08-complete"},
          "inputs": {
            "fixedSha256": "b9fd2679d3c82c52875e2a756a26f9c17c19072534477139331426d38f5393cd",
            "generatorSha256": "794621a446f7896cd43b741809393025e063b0ffb190570d9057b90ce1dabda8"
          },
          "fixed": {"scenarios": 22, "declaredOperations": 88, "digest": "1636ed177f59347ec11b8e9ffe1fb6d872fd3de5225298381a161a0b7d755f43"},
          "generator": {
            "algorithm": "splitmix64-v1", "baseSeed": "5909", "histories": 96,
            "operationsPerHistory": 40, "operations": 3840,
            "declaredGeneratedOperations": 3840, "setupBudgetOperations": 65, "lanes": 4,
            "historiesPerLane": 24, "comparisons": 3840, "ledgerChecks": 4225,
            "budgetPredictionScope": "FRESH_APPEND_CANDIDATES_PLUS_CHECKPOINT_RETRIES_AND_65_SETUP_OPERATIONS",
            "budgetPredictionChecks": 2703, "budgetPredictedAccepts": 2702,
            "budgetPredictedRejects": 1, "checkpointRequiredWitnesses": 1,
            "digest": "9551ad7a3026964b57b366e39d6307510789cd83c750bf239098f9ba299354e5", "byteExactRegeneration": true
          },
          "snapshotRecovery": {
            "format": "M09S1", "genesisWalOracle": true, "independentStorageLedger": true,
            "independentLedgerUsesProductionWalParser": false, "wholeSegmentExactInventory": true,
            "maxSuffixRecords": 64, "maxSuffixBytes": 1048576,
            "realPowerLossClaim": false, "replication": false, "aeron": false,
            "externalTerminalSegmentDeletionDetection": false,
            "retirementEvidenceScope": "RUNTIME_RETIREMENT_NON_TERMINAL_GAP_AND_ACTIVE_OR_CROSSING_RETENTION"
          },
          "coverage": {"required": 32, "observed": 32},
          "faultEvidence": {
            "childRuntimeHalts": 7, "operationFailureSeams": 8,
            "systemHarnessFailuresAreSystemError": true,
            "fixedStorageOperationProgramOrderObserved": true,
            "childHaltAtDeclaredHookAndNamespaceObserved": true,
            "childUnderlyingOperationOrderClaim": false,
            "operationFailureAtDeclaredPreOperationHook": true,
            "operationFailureUnderlyingOperationExecutionClaim": false,
            "physicalDurabilityClaim": false, "realPowerLossClaim": false
          },
          "mutants": {
            "required": 12, "storageAndStateMutants": 9,
            "invalidLatestAcceptanceCandidates": 3, "executableCandidates": 12,
            "killedAsStudentFailure": 12, "throwingControl": "SYSTEM_ERROR",
            "systemErrorCountedAsKill": false, "actualMutationActions": 12
          },
          "architecture": {
            "violations": 0, "coreInfrastructureFree": true,
            "localRuntimeJdkAndCoreOnly": true, "testkitProbeAbsentFromProduction": true,
            "storageOperationsProductionWiringVerified": true,
            "independentLedgerProductionParserFree": true
          },
          "releaseTarget": {
            "unitTag": "course/m09-complete", "productRelease": null,
            "verification": "M09_EVIDENCE_ONLY"
          }
        }
        """;
  }

  private static void copy(Path source, Path target) {
    try {
      Files.createDirectories(target.getParent());
      Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
    } catch (IOException failure) {
      throw new IllegalStateException("cannot copy M09 evidence test input", failure);
    }
  }

  private static void write(Path path, String value) {
    try {
      Files.createDirectories(path.getParent());
      Files.writeString(path, value);
    } catch (IOException failure) {
      throw new IllegalStateException("cannot write M09 evidence test file", failure);
    }
  }

  private static String git(Path root, String... arguments) {
    try {
      Files.createDirectories(root);
      List<String> command = new ArrayList<>();
      command.add("git");
      command.addAll(List.of(arguments));
      Process process = new ProcessBuilder(command).directory(root.toFile()).start();
      String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
      String error = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
      int exit = process.waitFor();
      if (exit != 0) {
        throw new IllegalStateException("git failed: " + error);
      }
      return output;
    } catch (IOException | InterruptedException failure) {
      if (failure instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
      throw new IllegalStateException("cannot run git in M09 evidence test", failure);
    }
  }

  private record Lab(Path root, Path reports, Path evidence) {}
}
