package io.github.lchareln.cex.matching.testkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.lchareln.cex.matching.cluster.DirectM11MatchingRuntime;
import io.github.lchareln.cex.matching.cluster.M11RuntimeStateCodec;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.BooleanNode;
import tools.jackson.databind.node.LongNode;
import tools.jackson.databind.node.NullNode;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.node.StringNode;

final class M12EvidenceWriterTest {
  @Test
  void publishesAllArtifactsExactlyOnceThroughAnAtomicStagingBoundary(@TempDir Path temporary) {
    Lab lab = createLab(temporary);
    AtomicInteger checks = new AtomicInteger();
    AtomicBoolean staged = new AtomicBoolean();
    AtomicBoolean published = new AtomicBoolean();
    M12EvidenceWriter writer =
        new M12EvidenceWriter(
            executor(checks),
            new M12EvidenceWriter.BoundaryHook() {
              @Override
              public void beforeFinalVerification(Path root) {
                assertFalse(Files.exists(lab.evidence(), LinkOption.NOFOLLOW_LINKS));
                Path staging = staging(root);
                assertEquals(expectedEvidencePaths(), fileInventory(staging));
                staged.set(true);
              }

              @Override
              public void beforePostPublishVerification(Path root) {
                assertTrue(Files.isDirectory(lab.evidence(), LinkOption.NOFOLLOW_LINKS));
                assertEquals(expectedEvidencePaths(), fileInventory(lab.evidence()));
                assertTrue(stagingDirectories(root).isEmpty());
                published.set(true);
              }
            },
            lab.expectations());

    M12EvidenceWriter.Result result =
        writer.write(
            lab.root(),
            lab.checks(),
            lab.evidence(),
            M12EvidenceWriter.UNIT_TAG,
            M12EvidenceWriter.PRODUCT_RELEASE);

    assertEquals(1, checks.get());
    assertTrue(staged.get());
    assertTrue(published.get());
    assertEquals(33, result.artifactCount());
    assertEquals(expectedEvidencePaths(), fileInventory(lab.evidence()));
    assertEquals(result.manifestSha256(), Hashing.sha256Hex(read(result.manifestPath())));

    JsonNode manifest = JsonSupport.parse(read(result.manifestPath()));
    assertEquals("cex.lab-evidence.v2", manifest.path("schemaVersion").stringValue());
    assertEquals("M12", manifest.path("unit").stringValue());
    assertEquals(M12EvidenceWriter.PRODUCT_RELEASE, manifest.path("productRelease").stringValue());
    assertEquals(lab.sourceCommit(), manifest.path("source").path("commit").stringValue());
    assertEquals(
        M12EvidenceWriter.REQUIRED_CLAIMS,
        manifest.path("claims").valueStream().map(node -> node.path("id").stringValue()).toList());
    assertEquals(
        M12EvidenceWriter.LIMITATIONS,
        manifest.path("limitations").valueStream().map(JsonNode::stringValue).toList());
    assertTrue(
        claim(manifest, "semantic-mutants")
            .path("statement")
            .stringValue()
            .contains("semantic-model-only"));
    assertTrue(
        claim(manifest, "leader-failover")
            .path("statement")
            .stringValue()
            .contains("no old-authority ACK is observed"));
    assertFalse(
        claim(manifest, "leader-failover")
            .path("statement")
            .stringValue()
            .contains("stale-authority fencing"));
    assertTrue(
        M12EvidenceWriter.LIMITATIONS.stream()
            .anyMatch(value -> value.contains("no Leader/member or leadership-term provenance")));

    List<String> bound = new ArrayList<>();
    for (JsonNode item : manifest.path("claims")) {
      for (JsonNode artifact : item.path("artifacts")) {
        String relative = artifact.path("path").stringValue();
        bound.add(relative);
        assertEquals(
            artifact.path("sha256").stringValue(),
            Hashing.sha256Hex(read(lab.evidence().resolve(relative))));
      }
    }
    assertEquals(33, bound.size());
    assertEquals(bound.size(), new LinkedHashSet<>(bound).size());
    Set<String> sources = expectedEvidencePaths();
    sources.remove("manifest.json");
    assertEquals(sources, new LinkedHashSet<>(bound));
  }

  @ParameterizedTest(name = "{0}")
  @EnumSource(ReleaseMutation.class)
  void rejectsIncompleteOrMismatchedReleaseTagsBeforeRunningTheCheck(
      ReleaseMutation mutation, @TempDir Path temporary) {
    Lab lab = createLab(temporary);
    mutation.apply(lab);
    AtomicInteger checks = new AtomicInteger();

    IllegalStateException failure =
        assertThrows(
            IllegalStateException.class,
            () ->
                writer(lab, checks)
                    .write(
                        lab.root(),
                        lab.checks(),
                        lab.evidence(),
                        M12EvidenceWriter.UNIT_TAG,
                        M12EvidenceWriter.PRODUCT_RELEASE));

    assertTrue(
        failure.getMessage().contains("not annotated")
            || failure.getMessage().contains("does not peel to HEAD"));
    assertEquals(0, checks.get());
    assertFalse(Files.exists(lab.evidence(), LinkOption.NOFOLLOW_LINKS));
  }

  @Test
  void startTagMustPeelToTheConfiguredFrozenCommit(@TempDir Path temporary) {
    Lab lab = createLab(temporary);
    AtomicInteger checks = new AtomicInteger();
    M12EvidenceWriter writer =
        new M12EvidenceWriter(
            executor(checks),
            M12EvidenceWriter.BoundaryHook.NOOP,
            new M12EvidenceWriter.ReleaseExpectations(
                lab.inheritedCommit(), lab.inheritedCommit()));

    IllegalStateException failure =
        assertThrows(
            IllegalStateException.class,
            () ->
                writer.write(
                    lab.root(),
                    lab.checks(),
                    lab.evidence(),
                    M12EvidenceWriter.UNIT_TAG,
                    M12EvidenceWriter.PRODUCT_RELEASE));

    assertTrue(failure.getMessage().contains("frozen M12 start commit"));
    assertEquals(0, checks.get());
  }

  @Test
  void inheritedM11CommitMustPrecedeTheM12StartBoundary(@TempDir Path temporary) {
    Lab lab = createLab(temporary);
    git(lab.root(), "tag", "-d", M12EvidenceWriter.INHERITED_TAG);
    annotatedTag(lab.root(), M12EvidenceWriter.INHERITED_TAG);
    AtomicInteger checks = new AtomicInteger();
    M12EvidenceWriter writer =
        new M12EvidenceWriter(
            executor(checks),
            M12EvidenceWriter.BoundaryHook.NOOP,
            new M12EvidenceWriter.ReleaseExpectations(lab.startCommit(), lab.sourceCommit()));

    IllegalStateException failure =
        assertThrows(
            IllegalStateException.class,
            () ->
                writer.write(
                    lab.root(),
                    lab.checks(),
                    lab.evidence(),
                    M12EvidenceWriter.UNIT_TAG,
                    M12EvidenceWriter.PRODUCT_RELEASE));

    assertTrue(failure.getMessage().contains("is not an ancestor of course/m12-start"));
    assertEquals(0, checks.get());
  }

  @Test
  void dirtyHeadIsRejectedBeforeRunningTheCheck(@TempDir Path temporary) {
    Lab lab = createLab(temporary);
    write(
        lab.root().resolve("course.properties"),
        (course() + "dirty=true\n").getBytes(StandardCharsets.UTF_8));
    AtomicInteger checks = new AtomicInteger();

    IllegalStateException failure =
        assertThrows(
            IllegalStateException.class,
            () ->
                writer(lab, checks)
                    .write(
                        lab.root(),
                        lab.checks(),
                        lab.evidence(),
                        M12EvidenceWriter.UNIT_TAG,
                        M12EvidenceWriter.PRODUCT_RELEASE));

    assertTrue(failure.getMessage().contains("repository must be clean"));
    assertEquals(0, checks.get());
  }

  @Test
  void contractCorrectionMustExactlyMatchTheTopologyObservation(@TempDir Path temporary) {
    Lab lab = createLab(temporary);
    Path checkPath = lab.checks().resolve("check.json");
    ObjectNode check = (ObjectNode) JsonSupport.parse(read(checkPath));
    ((ObjectNode) check.path("contractCorrection")).put("initialLeaderId", 2);
    write(checkPath, JsonSupport.prettyBytes(check));

    IllegalStateException failure =
        assertThrows(
            IllegalStateException.class,
            () ->
                writer(lab, new AtomicInteger())
                    .write(
                        lab.root(),
                        lab.checks(),
                        lab.evidence(),
                        M12EvidenceWriter.UNIT_TAG,
                        M12EvidenceWriter.PRODUCT_RELEASE));

    assertTrue(failure.getMessage().contains("differs from the topology observation"));
    assertFalse(Files.exists(lab.evidence(), LinkOption.NOFOLLOW_LINKS));
  }

  @Test
  void everyMemberIdentityDigestMustMatchTheDirectOracle(@TempDir Path temporary) {
    Lab lab = createLab(temporary);
    Path checkPath = lab.checks().resolve("check.json");
    ObjectNode check = (ObjectNode) JsonSupport.parse(read(checkPath));
    ((ObjectNode) check.path("stateEquivalence").path("members").path(1))
        .put("identityResultDigest", "d".repeat(64));
    write(checkPath, JsonSupport.prettyBytes(check));

    IllegalStateException failure =
        assertThrows(
            IllegalStateException.class,
            () ->
                writer(lab, new AtomicInteger())
                    .write(
                        lab.root(),
                        lab.checks(),
                        lab.evidence(),
                        M12EvidenceWriter.UNIT_TAG,
                        M12EvidenceWriter.PRODUCT_RELEASE));

    assertTrue(failure.getMessage().contains("identity table differs from the direct oracle"));
    assertFalse(Files.exists(lab.evidence(), LinkOption.NOFOLLOW_LINKS));
  }

  @ParameterizedTest(name = "{0}")
  @EnumSource(StrictChildReport.class)
  void strictChildReportSchemasRejectUnknownFieldsEvenWhenProjectionAndHashAreRebound(
      StrictChildReport child, @TempDir Path temporary) {
    Lab lab = createLab(temporary);
    tamperAndRebind(lab, child.reportName, child.checkField);

    assertThrows(
        FixtureSchemaException.class,
        () ->
            writer(lab, new AtomicInteger())
                .write(
                    lab.root(),
                    lab.checks(),
                    lab.evidence(),
                    M12EvidenceWriter.UNIT_TAG,
                    M12EvidenceWriter.PRODUCT_RELEASE));

    assertFalse(Files.exists(lab.evidence(), LinkOption.NOFOLLOW_LINKS));
  }

  @Test
  void strictTopologyReaderRecomputesStatusSequenceAdvancement(@TempDir Path temporary) {
    Lab lab = createLab(temporary);
    ObjectNode topology =
        (ObjectNode) JsonSupport.parse(read(lab.checks().resolve("topology.json")));
    JsonNode witness = topology.path("stableSnapshotWitnesses").path(0);
    ((ObjectNode) witness.path("secondSnapshot").path(0))
        .put(
            "statusSequence",
            witness.path("firstSnapshot").path(0).path("statusSequence").longValue());
    rebindReport(lab, "topology.json", "clusterTopology", topology);

    IllegalStateException failure =
        assertThrows(
            IllegalStateException.class,
            () ->
                writer(lab, new AtomicInteger())
                    .write(
                        lab.root(),
                        lab.checks(),
                        lab.evidence(),
                        M12EvidenceWriter.UNIT_TAG,
                        M12EvidenceWriter.PRODUCT_RELEASE));

    assertTrue(failure.getMessage().contains("status sequence did not advance"));
    assertFalse(Files.exists(lab.evidence(), LinkOption.NOFOLLOW_LINKS));
  }

  @Test
  void strictTopologyReaderRecomputesPortBlockOwnership(@TempDir Path temporary) {
    Lab lab = createLab(temporary);
    ObjectNode topology =
        (ObjectNode) JsonSupport.parse(read(lab.checks().resolve("topology.json")));
    ((ArrayNode) topology.path("processStarts").path(0).path("udpPorts"))
        .set(0, LongNode.valueOf(52106));
    rebindReport(lab, "topology.json", "clusterTopology", topology);

    IllegalStateException failure =
        assertThrows(
            IllegalStateException.class,
            () ->
                writer(lab, new AtomicInteger())
                    .write(
                        lab.root(),
                        lab.checks(),
                        lab.evidence(),
                        M12EvidenceWriter.UNIT_TAG,
                        M12EvidenceWriter.PRODUCT_RELEASE));

    assertTrue(failure.getMessage().contains("owned port block"));
    assertFalse(Files.exists(lab.evidence(), LinkOption.NOFOLLOW_LINKS));
  }

  @Test
  void strictHistoryReaderRejectsReboundOracleResultTampering(@TempDir Path temporary) {
    Lab lab = createLab(temporary);
    ObjectNode history =
        (ObjectNode) JsonSupport.parse(read(lab.checks().resolve("m12-command-history.json")));
    ((ObjectNode) history.path("bindings").path(0)).put("resultDigest", "d".repeat(64));
    rebindReport(lab, "m12-command-history.json", "commandOutcomes", history);

    IllegalStateException failure =
        assertThrows(
            IllegalStateException.class,
            () ->
                writer(lab, new AtomicInteger())
                    .write(
                        lab.root(),
                        lab.checks(),
                        lab.evidence(),
                        M12EvidenceWriter.UNIT_TAG,
                        M12EvidenceWriter.PRODUCT_RELEASE));

    assertTrue(failure.getMessage().contains("direct runtime oracle"));
    assertFalse(Files.exists(lab.evidence(), LinkOption.NOFOLLOW_LINKS));
  }

  @Test
  void strictCorpusReaderRejectsReboundCanonicalIdentityTampering(@TempDir Path temporary) {
    Lab lab = createLab(temporary);
    ObjectNode corpus = (ObjectNode) JsonSupport.parse(read(lab.checks().resolve("corpus.json")));
    ((ObjectNode) corpus.path("identities").path(0)).put("canonicalEnvelopeSha256", "d".repeat(64));
    rebindReport(lab, "corpus.json", "workloadProfile", corpus);

    IllegalStateException failure =
        assertThrows(
            IllegalStateException.class,
            () ->
                writer(lab, new AtomicInteger())
                    .write(
                        lab.root(),
                        lab.checks(),
                        lab.evidence(),
                        M12EvidenceWriter.UNIT_TAG,
                        M12EvidenceWriter.PRODUCT_RELEASE));

    assertTrue(failure.getMessage().contains("canonical identity bytes"));
  }

  @Test
  void strictHistoryReaderRejectsNonCanonicalUuidDespiteDisabledFormatChecks(
      @TempDir Path temporary) {
    Lab lab = createLab(temporary);
    ObjectNode history =
        (ObjectNode) JsonSupport.parse(read(lab.checks().resolve("m12-command-history.json")));
    ((ObjectNode) history.path("attempts").path(0)).put("correlationId", "0-0-0-0-1");
    rebindReport(lab, "m12-command-history.json", "commandOutcomes", history);

    IllegalStateException failure =
        assertThrows(
            IllegalStateException.class,
            () ->
                writer(lab, new AtomicInteger())
                    .write(
                        lab.root(),
                        lab.checks(),
                        lab.evidence(),
                        M12EvidenceWriter.UNIT_TAG,
                        M12EvidenceWriter.PRODUCT_RELEASE));

    assertTrue(failure.getMessage().contains("non-canonical UUID"));
  }

  @Test
  void strictTopologyReaderRejectsStableRoleDriftAfterProjectionAndHashRebind(
      @TempDir Path temporary) {
    Lab lab = createLab(temporary);
    ObjectNode topology =
        (ObjectNode) JsonSupport.parse(read(lab.checks().resolve("topology.json")));
    ((ObjectNode) topology.path("stableSnapshotWitnesses").path(0).path("secondSnapshot").path(0))
        .put("role", "FOLLOWER");
    rebindReport(lab, "topology.json", "clusterTopology", topology);

    IllegalStateException failure =
        assertThrows(
            IllegalStateException.class,
            () ->
                writer(lab, new AtomicInteger())
                    .write(
                        lab.root(),
                        lab.checks(),
                        lab.evidence(),
                        M12EvidenceWriter.UNIT_TAG,
                        M12EvidenceWriter.PRODUCT_RELEASE));

    assertTrue(failure.getMessage().contains("stable witness changed role"));
  }

  @Test
  void strictTopologyReaderRejectsReboundRestartLivenessArithmetic(@TempDir Path temporary) {
    Lab lab = createLab(temporary);
    ObjectNode topology =
        (ObjectNode) JsonSupport.parse(read(lab.checks().resolve("topology.json")));
    ObjectNode witness = (ObjectNode) topology.path("restartSafetyWitnesses").path(0);
    witness.put("ageMillis", witness.path("ageMillis").longValue() + 1);
    rebindReport(lab, "topology.json", "clusterTopology", topology);

    IllegalStateException failure =
        assertThrows(
            IllegalStateException.class,
            () ->
                writer(lab, new AtomicInteger())
                    .write(
                        lab.root(),
                        lab.checks(),
                        lab.evidence(),
                        M12EvidenceWriter.UNIT_TAG,
                        M12EvidenceWriter.PRODUCT_RELEASE));

    assertTrue(failure.getMessage().contains("mark-file liveness expiry"));
  }

  @Test
  void strictCrossReaderRejectsReboundFinalLeaderProjection(@TempDir Path temporary) {
    Lab lab = createLab(temporary);
    ObjectNode state =
        (ObjectNode) JsonSupport.parse(read(lab.checks().resolve("state-equivalence.json")));
    ((ObjectNode) state.path("members").path(0)).put("role", "LEADER");
    ((ObjectNode) state.path("members").path(1)).put("role", "FOLLOWER");
    rebindReport(lab, "state-equivalence.json", "stateEquivalence", state);

    IllegalStateException failure =
        assertThrows(
            IllegalStateException.class,
            () ->
                writer(lab, new AtomicInteger())
                    .write(
                        lab.root(),
                        lab.checks(),
                        lab.evidence(),
                        M12EvidenceWriter.UNIT_TAG,
                        M12EvidenceWriter.PRODUCT_RELEASE));

    assertTrue(failure.getMessage().contains("final Leader differs"));
  }

  @Test
  void semanticFailureWithInfrastructureSuppressedIsSystemError() {
    M12SemanticFailure semantic = new M12SemanticFailure("semantic test failure");
    assertEquals(M12CheckRunner.STUDENT_FAILURE, M12CheckRunner.classifySemanticFailure(semantic));

    semantic.addSuppressed(new IllegalStateException("teardown failed"));
    assertEquals(M12CheckRunner.SYSTEM_ERROR, M12CheckRunner.classifySemanticFailure(semantic));
  }

  @Test
  void unexpectedAeronWarningCannotEnterCompletionEvidence(@TempDir Path temporary) {
    Lab lab = createLab(temporary);
    ObjectNode state =
        (ObjectNode) JsonSupport.parse(read(lab.checks().resolve("state-equivalence.json")));
    ObjectNode member = (ObjectNode) state.path("members").path(0);
    member.put("diagnosticWarningCount", 1);
    ((ArrayNode) member.path("diagnosticWarnings"))
        .add("io.aeron.cluster.client.ClusterEvent: WARN - unexpected warning");
    state.put("diagnosticWarningCount", 1);
    rebindReport(lab, "state-equivalence.json", "stateEquivalence", state);

    assertThrows(
        FixtureSchemaException.class,
        () ->
            writer(lab, new AtomicInteger())
                .write(
                    lab.root(),
                    lab.checks(),
                    lab.evidence(),
                    M12EvidenceWriter.UNIT_TAG,
                    M12EvidenceWriter.PRODUCT_RELEASE));
    assertFalse(Files.exists(lab.evidence(), LinkOption.NOFOLLOW_LINKS));
  }

  @Test
  void droppedAeronWarningCannotEnterCompletionEvidence(@TempDir Path temporary) {
    Lab lab = createLab(temporary);
    ObjectNode state =
        (ObjectNode) JsonSupport.parse(read(lab.checks().resolve("state-equivalence.json")));
    ((ObjectNode) state.path("members").path(0)).put("droppedDiagnosticWarnings", 1);
    state.put("droppedDiagnosticWarnings", 1);
    rebindReport(lab, "state-equivalence.json", "stateEquivalence", state);

    assertThrows(
        FixtureSchemaException.class,
        () ->
            writer(lab, new AtomicInteger())
                .write(
                    lab.root(),
                    lab.checks(),
                    lab.evidence(),
                    M12EvidenceWriter.UNIT_TAG,
                    M12EvidenceWriter.PRODUCT_RELEASE));
    assertFalse(Files.exists(lab.evidence(), LinkOption.NOFOLLOW_LINKS));
  }

  @Test
  void symlinkedEvidenceDestinationIsRejectedBeforeRunningTheCheck(@TempDir Path temporary) {
    Lab lab = createLab(temporary);
    Path target = temporary.resolve("redirected-evidence");
    createDirectories(target);
    createDirectories(lab.evidence().getParent());
    createSymlink(lab.evidence(), target);
    AtomicInteger checks = new AtomicInteger();

    IllegalStateException failure =
        assertThrows(
            IllegalStateException.class,
            () ->
                writer(lab, checks)
                    .write(
                        lab.root(),
                        lab.checks(),
                        lab.evidence(),
                        M12EvidenceWriter.UNIT_TAG,
                        M12EvidenceWriter.PRODUCT_RELEASE));

    assertTrue(failure.getMessage().contains("symlink"));
    assertEquals(0, checks.get());
    assertTrue(fileInventory(target).isEmpty());
  }

  @Test
  void symlinkedReportArtifactCannotEnterEvidence(@TempDir Path temporary) {
    Lab lab = createLab(temporary);
    Path artifact = lab.checks().resolve(M12EvidenceWriter.REPORT_ARTIFACTS.getFirst());
    Path target = lab.checks().resolve("symlink-target.json");
    write(target, read(artifact));
    delete(artifact);
    createSymlink(artifact, target);

    IllegalStateException failure =
        assertThrows(
            IllegalStateException.class,
            () ->
                writer(lab, new AtomicInteger())
                    .write(
                        lab.root(),
                        lab.checks(),
                        lab.evidence(),
                        M12EvidenceWriter.UNIT_TAG,
                        M12EvidenceWriter.PRODUCT_RELEASE));

    assertTrue(
        failure.getMessage().contains("missing strict M12 report")
            || failure.getMessage().contains("symlink"));
    assertFalse(Files.exists(lab.evidence(), LinkOption.NOFOLLOW_LINKS));
  }

  @ParameterizedTest(name = "{0}")
  @EnumSource(HashMutationBoundary.class)
  void hashMutationAtEitherPublicationBoundaryLeavesNoEvidence(
      HashMutationBoundary boundary, @TempDir Path temporary) {
    Lab lab = createLab(temporary);
    M12EvidenceWriter writer =
        new M12EvidenceWriter(
            executor(new AtomicInteger()),
            new M12EvidenceWriter.BoundaryHook() {
              @Override
              public void beforeFinalVerification(Path root) {
                if (boundary == HashMutationBoundary.SOURCE_BEFORE_PUBLICATION) {
                  mutate(lab.checks().resolve("leadership.json"));
                }
              }

              @Override
              public void beforePostPublishVerification(Path root) {
                if (boundary == HashMutationBoundary.PUBLISHED_TREE) {
                  mutate(lab.evidence().resolve("reports/check/leadership.json"));
                }
              }
            },
            lab.expectations());

    IllegalStateException failure =
        assertThrows(
            IllegalStateException.class,
            () ->
                writer.write(
                    lab.root(),
                    lab.checks(),
                    lab.evidence(),
                    M12EvidenceWriter.UNIT_TAG,
                    M12EvidenceWriter.PRODUCT_RELEASE));

    assertTrue(failure.getMessage().contains("artifact"));
    assertFalse(Files.exists(lab.evidence(), LinkOption.NOFOLLOW_LINKS));
    assertTrue(stagingDirectories(lab.root()).isEmpty());
  }

  @ParameterizedTest(name = "{0}")
  @EnumSource(ReleaseMutationBoundary.class)
  void releaseStateIsRecheckedImmediatelyBeforeAndAfterPublication(
      ReleaseMutationBoundary boundary, @TempDir Path temporary) {
    Lab lab = createLab(temporary);
    M12EvidenceWriter writer =
        new M12EvidenceWriter(
            executor(new AtomicInteger()),
            new M12EvidenceWriter.BoundaryHook() {
              @Override
              public void beforeFinalVerification(Path root) {
                if (boundary == ReleaseMutationBoundary.BEFORE_PUBLICATION) {
                  moveProductTagToStart(lab);
                }
              }

              @Override
              public void beforePostPublishVerification(Path root) {
                if (boundary == ReleaseMutationBoundary.AFTER_PUBLICATION) {
                  moveProductTagToStart(lab);
                }
              }
            },
            lab.expectations());

    IllegalStateException failure =
        assertThrows(
            IllegalStateException.class,
            () ->
                writer.write(
                    lab.root(),
                    lab.checks(),
                    lab.evidence(),
                    M12EvidenceWriter.UNIT_TAG,
                    M12EvidenceWriter.PRODUCT_RELEASE));

    assertTrue(failure.getMessage().contains("does not peel to HEAD"));
    assertFalse(Files.exists(lab.evidence(), LinkOption.NOFOLLOW_LINKS));
    assertTrue(stagingDirectories(lab.root()).isEmpty());
  }

  @Test
  void failedPostPublishVerificationRestoresTheOriginalDestination(@TempDir Path temporary) {
    Lab lab = createLab(temporary);
    byte[] original = "previous verified M12 evidence\n".getBytes(StandardCharsets.UTF_8);
    write(lab.evidence().resolve("prior-evidence.txt"), original);
    M12EvidenceWriter writer =
        new M12EvidenceWriter(
            executor(new AtomicInteger()),
            new M12EvidenceWriter.BoundaryHook() {
              @Override
              public void beforeFinalVerification(Path root) {}

              @Override
              public void beforePostPublishVerification(Path root) {
                throw new IllegalStateException("injected post-publication verification failure");
              }
            },
            lab.expectations());

    IllegalStateException failure =
        assertThrows(
            IllegalStateException.class,
            () ->
                writer.write(
                    lab.root(),
                    lab.checks(),
                    lab.evidence(),
                    M12EvidenceWriter.UNIT_TAG,
                    M12EvidenceWriter.PRODUCT_RELEASE));

    assertTrue(failure.getMessage().contains("injected post-publication"));
    assertEquals(Set.of("prior-evidence.txt"), fileInventory(lab.evidence()));
    assertTrue(
        java.util.Arrays.equals(original, read(lab.evidence().resolve("prior-evidence.txt"))));
    assertTrue(stagingDirectories(lab.root()).isEmpty());
    assertTrue(backupDirectories(lab.root()).isEmpty());
  }

  private static M12EvidenceWriter writer(Lab lab, AtomicInteger checks) {
    return new M12EvidenceWriter(
        executor(checks), M12EvidenceWriter.BoundaryHook.NOOP, lab.expectations());
  }

  private static M12EvidenceWriter.CheckExecutor executor(AtomicInteger checks) {
    return (root, reports) -> {
      checks.incrementAndGet();
      return new M12CheckRunner.Result(M12CheckRunner.PASS, reports.resolve("check.json"));
    };
  }

  private static Lab createLab(Path temporary) {
    Path root = temporary.resolve("repo");
    Path source = Path.of(System.getProperty("matching.repositoryRoot"));
    for (String schema : M12EvidenceWriter.EVIDENCE_SCHEMAS) {
      copy(source.resolve(schema), root.resolve(schema));
    }
    copy(
        source.resolve(M12StartCheckRunner.WORKLOAD_SCHEMA_PATH),
        root.resolve(M12StartCheckRunner.WORKLOAD_SCHEMA_PATH));
    copy(
        source.resolve(M12StartCheckRunner.WORKLOAD_PATH),
        root.resolve(M12StartCheckRunner.WORKLOAD_PATH));
    write(root.resolve(".gitignore"), "/build/\n".getBytes(StandardCharsets.UTF_8));
    write(root.resolve("course.properties"), course().getBytes(StandardCharsets.UTF_8));

    git(root, "init", "-q");
    git(root, "config", "user.name", "M12 Evidence Test");
    git(root, "config", "user.email", "m12-evidence@example.invalid");
    git(root, "config", "commit.gpgsign", "false");
    git(root, "config", "tag.gpgSign", "false");
    git(root, "add", ".");
    git(root, "commit", "-q", "-m", "test: freeze M12 evidence inputs");
    String inheritedCommit = git(root, "rev-parse", "HEAD").strip();
    annotatedTag(root, M12EvidenceWriter.INHERITED_TAG);
    emptyCommit(root, "test: M12 start");
    String startCommit = git(root, "rev-parse", "HEAD").strip();
    annotatedTag(root, M12EvidenceWriter.START_TAG);
    emptyCommit(root, "test: M12 complete");
    String sourceCommit = git(root, "rev-parse", "HEAD").strip();
    annotatedTag(root, M12EvidenceWriter.UNIT_TAG);
    annotatedTag(root, M12EvidenceWriter.PRODUCT_RELEASE);

    Path reports = root.resolve("build/reports/m12");
    Path clusterRoot = root.resolve("build/tmp/m12-cluster").toAbsolutePath().normalize();
    createDirectories(clusterRoot);
    writeFixtureReports(root, reports, sourceCommit, clusterRoot);
    return new Lab(
        root,
        reports,
        root.resolve("build/lab-evidence/M12"),
        sourceCommit,
        startCommit,
        inheritedCommit);
  }

  private static void writeFixtureReports(
      Path root, Path reports, String sourceCommit, Path clusterRoot) {
    M12WorkloadLoader.Workload workload = M12WorkloadLoader.load(root);
    M12DeterministicCorpus.Corpus corpus = M12DeterministicCorpus.generate(workload);
    JsonNode checkSchema = JsonSupport.parse(read(root.resolve(M12CheckRunner.CHECK_SCHEMA_PATH)));
    ObjectNode check = (ObjectNode) materialize(checkSchema, checkSchema, "root", 0);
    ((ObjectNode) check.path("source")).put("commit", sourceCommit).put("dirty", false);

    enrichCorpus((ObjectNode) check.path("workloadProfile"), workload, corpus);
    ArrayNode scenarios = (ArrayNode) check.path("fixed").path("scenarioIds");
    scenarios.removeAll();
    M12StartCheckRunner.SCENARIO_IDS.forEach(scenarios::add);

    ObjectNode coverage = materializeRoot(root, M12CheckRunner.COVERAGE_SCHEMA_PATH);
    ObjectNode mutants = materializeRoot(root, M12CheckRunner.MUTANTS_SCHEMA_PATH);
    ObjectNode replay = materializeRoot(root, M12CheckRunner.REPLAY_SCHEMA_PATH);
    check.set("coverage", coverage);
    check.set("mutants", mutants);
    check.set("replay", replay);
    ObjectNode correction = (ObjectNode) check.path("contractCorrection");
    correction.put("initialLeaderId", 0);
    correction.put(
        "reason",
        "Aeron appointedLeaderId disables automatic election and prevents the required Leader"
            + " failover.");
    ((ObjectNode) check.path("clusterTopology")).set("contractCorrection", correction.deepCopy());
    enrichHistory((ObjectNode) check.path("commandOutcomes"), corpus);
    ObjectNode topology = (ObjectNode) check.path("clusterTopology");
    enrichTopology(topology, corpus);
    enrichLeadership((ObjectNode) check.path("leadership"));
    ((ObjectNode) check.path("quorum"))
        .put("restoredLeaderId", 1)
        .put("restoredLeadershipTermId", 2);
    ObjectNode catchup = (ObjectNode) check.path("catchup");
    catchup
        .put("formerLeaderId", 0)
        .put("catchupCommitPosition", 100)
        .put("catchupLogPosition", 100)
        .put("archiveMarkFileLivenessTimeoutMillis", 10_000)
        .put("restartSafetyPredicate", "ARCHIVE_MARK_FILE_ACTIVITY_AGE_GT_LIVENESS_TIMEOUT")
        .put("firstReturnRestartSafetyWitnessOrdinal", 1);
    catchup.set(
        "firstReturnRestartSafetyWitness",
        topology.path("restartSafetyWitnesses").path(0).deepCopy());
    enrichArchitecture((ObjectNode) check.path("architecture"));
    enrichEnvironment((ObjectNode) check.path("environment"), clusterRoot);
    enrichStateEquivalence((ObjectNode) check.path("stateEquivalence"), corpus);
    enrichCoverage(coverage, workload, corpus);
    enrichJudgeInspection((ObjectNode) check.path("judgeInspection"), corpus);

    Map<String, String> projections =
        Map.ofEntries(
            Map.entry("inherited-m11.json", "inheritedM11"),
            Map.entry("corpus.json", "workloadProfile"),
            Map.entry("m12-command-history.json", "commandOutcomes"),
            Map.entry("topology.json", "clusterTopology"),
            Map.entry("leadership.json", "leadership"),
            Map.entry("quorum.json", "quorum"),
            Map.entry("catchup.json", "catchup"),
            Map.entry("state-equivalence.json", "stateEquivalence"),
            Map.entry("coverage.json", "coverage"),
            Map.entry("mutants.json", "mutants"),
            Map.entry("replay.json", "replay"),
            Map.entry("architecture.json", "architecture"),
            Map.entry("environment.json", "environment"));
    projections.forEach(
        (name, field) -> write(reports.resolve(name), JsonSupport.prettyBytes(check.path(field))));
    write(
        reports.resolve("counterexamples.json"),
        JsonSupport.prettyBytes(materializeRoot(root, M12CheckRunner.COUNTEREXAMPLES_SCHEMA_PATH)));
    write(reports.resolve("commands.canonical.bin"), canonicalCommandBytes(corpus));

    ArrayNode bindings = (ArrayNode) check.path("artifactBindings");
    bindings.removeAll();
    for (String name : M12CheckRunner.OUTPUTS) {
      byte[] bytes = read(reports.resolve(name));
      bindings
          .addObject()
          .put("path", name)
          .put("bytes", bytes.length)
          .put("sha256", Hashing.sha256Hex(bytes));
    }
    write(reports.resolve("check.json"), JsonSupport.prettyBytes(check));
  }

  private static ObjectNode materializeRoot(Path root, String schemaPath) {
    JsonNode schema = JsonSupport.parse(read(root.resolve(schemaPath)));
    return (ObjectNode) materialize(schema, schema, "root", 0);
  }

  private static void enrichCorpus(
      ObjectNode report,
      M12WorkloadLoader.Workload workload,
      M12DeterministicCorpus.Corpus corpus) {
    report.put("workloadSha256", workload.sha256());
    report.put("corpusSha256", corpus.corpusSha256());
    report.put("expectedFinalSemanticDigest", corpus.expectedFinalSemanticDigest());
    ArrayNode phases = report.putArray("phaseOrder");
    workload.phaseOrder().forEach(phases::add);
    ArrayNode identities = report.putArray("identities");
    corpus
        .identities()
        .forEach(
            identity -> {
              ObjectNode item = identities.addObject();
              item.put("index", identity.index());
              item.put("commandId", identity.commandId().toString());
              item.put("producerId", identity.producerId());
              item.put("producerEpoch", identity.producerEpoch());
              item.put("shardId", identity.shardId());
              item.put("producerSequence", identity.producerSequence());
              item.put("payloadSha256", identity.payloadSha256());
              item.put("canonicalEnvelopeSha256", identity.canonicalSha256());
              item.put("canonicalEnvelopeBytes", identity.canonicalBytes().length);
            });
  }

  private static void enrichHistory(ObjectNode history, M12DeterministicCorpus.Corpus corpus) {
    history.put("corpusSha256", corpus.corpusSha256());
    OracleState first33 = oracleState(corpus, 33);
    ArrayNode applied = history.putArray("appliedUnknownMembersBeforeLeaderKill");
    for (int memberId = 0; memberId < 3; memberId++) {
      addStatus(
          applied.addObject(),
          memberId,
          3000 + memberId,
          memberId == 0 ? "LEADER" : "FOLLOWER",
          1,
          34,
          33,
          first33.semanticDigest(),
          first33.identityDigest());
    }
    ArrayNode attempts = history.putArray("attempts");
    for (M12DeterministicCorpus.Attempt expected : corpus.attempts()) {
      ObjectNode attempt = attempts.addObject();
      attempt.put("ordinal", expected.ordinal());
      attempt.put("phase", expected.phase());
      attempt.put("identityIndex", expected.identity().index());
      attempt.put("canonicalEnvelopeSha256", expected.identity().canonicalSha256());
      attempt.put("commandId", expected.identity().commandId().toString());
      attempt.put("correlationId", expected.correlationId().toString());
      attempt.put("ingressAccepted", expected.ingressAccepted());
      attempt.put("outcome", expected.outcome().name());
      attempt.put("trustedResponseObserved", expected.trustedResponseObserved());
      nullable(attempt, "responseCorrelationId", expected.responseCorrelationId());
      nullable(attempt, "responseStatus", expected.responseStatus());
      nullable(attempt, "applicationSequence", expected.applicationSequence());
      nullable(attempt, "resultDigest", expected.resultDigest());
      attempt.put("businessEffectApplied", expected.businessEffectApplied());
      nullable(attempt, "retryOfAttemptOrdinal", expected.retryOfAttemptOrdinal());
      attempt.put("authorityTerm", expected.authorityTerm());
      attempt.put("authorityLeaderId", expected.authorityLeaderId());
      attempt.put(
          "responseAcceptedUnderCurrentClientAuthority",
          expected.responseAcceptedUnderCurrentClientAuthority());
      attempt.put("noQuorumWindow", expected.noQuorumWindow());
      attempt.put(
          "timeoutClassifiedAsBusinessRejection", expected.timeoutClassifiedAsBusinessRejection());
    }
    ArrayNode bindings = history.putArray("bindings");
    corpus
        .bindings()
        .forEach(
            binding ->
                bindings
                    .addObject()
                    .put("identityIndex", binding.identity().index())
                    .put("canonicalEnvelopeSha256", binding.identity().canonicalSha256())
                    .put("applicationSequence", binding.applicationSequence())
                    .put("resultDigest", binding.resultDigest())
                    .put("businessEffectCount", binding.businessEffectCount())
                    .put("observedResponseAuthorityTerm", binding.observedResponseAuthorityTerm()));
  }

  private static void enrichTopology(ObjectNode topology, M12DeterministicCorpus.Corpus corpus) {
    OracleState empty = oracleState(corpus, 0);
    OracleState first33 = oracleState(corpus, 33);
    OracleState first65 = oracleState(corpus, 65);
    OracleState finalState = oracleState(corpus, 66);
    topology.put("teardownComplete", true);
    topology.put("childProcessesAliveAfterTeardown", 0);
    topology.put("ownerProcessId", 9000);
    topology.put("archiveMarkFileLivenessTimeoutMillis", 10_000);
    topology.put("restartSafetyPredicate", "ARCHIVE_MARK_FILE_ACTIVITY_AGE_GT_LIVENESS_TIMEOUT");
    topology.put("restartSafetyWitnessCount", 3);
    ArrayNode restartSafety = topology.putArray("restartSafetyWitnesses");
    addRestartSafetyWitness(restartSafety.addObject(), 1, 0, 3000);
    addRestartSafetyWitness(restartSafety.addObject(), 2, 0, 3003);
    addRestartSafetyWitness(restartSafety.addObject(), 3, 2, 3002);
    topology.put("stableSnapshotWitnessCount", 3);
    ArrayNode witnesses = topology.putArray("stableSnapshotWitnesses");
    addStableWitness(witnesses.addObject(), 1, new long[] {3000, 3001, 3002}, 0, 1, 1, 0, empty);
    addStableWitness(
        witnesses.addObject(), 2, new long[] {3003, 3001, 3002}, 1, 2, 66, 65, first65);
    addStableWitness(
        witnesses.addObject(), 3, new long[] {3004, 3001, 3005}, 1, 2, 67, 66, finalState);

    ArrayNode applied = topology.putArray("appliedUnknownMembersBeforeLeaderKill");
    for (int memberId = 0; memberId < 3; memberId++) {
      addStatus(
          applied.addObject(),
          memberId,
          3000 + memberId,
          memberId == 0 ? "LEADER" : "FOLLOWER",
          1,
          34,
          33,
          first33.semanticDigest(),
          first33.identityDigest());
    }
    ArrayNode initial = topology.putArray("initialMembers");
    ArrayNode converged = topology.putArray("convergedMembers");
    for (int memberId = 0; memberId < 3; memberId++) {
      addTopologyMember(
          initial.addObject(),
          memberId,
          3000 + memberId,
          memberId == 0 ? "LEADER" : "FOLLOWER",
          1,
          1,
          0,
          empty);
      addTopologyMember(
          converged.addObject(),
          memberId,
          memberId == 0 ? 3004 : memberId == 1 ? 3001 : 3005,
          memberId == 1 ? "LEADER" : "FOLLOWER",
          2,
          67,
          66,
          finalState);
    }
    ArrayNode starts = topology.putArray("processStarts");
    int[] members = {0, 1, 2, 0, 0, 2};
    for (int index = 0; index < members.length; index++) {
      int memberId = members[index];
      int portBase = 52100 + memberId * 10;
      ObjectNode start = starts.addObject();
      start.put("memberId", memberId);
      start.put("processId", 3000 + index);
      start.put("freshStart", index < 3);
      start.put("aliveAfterTeardown", false);
      start.put("portBlockBase", portBase);
      addPorts(start.putArray("udpPorts"), portBase);
      start.put("rootDirectory", "/tmp/m12/member-" + memberId);
      start.put("aeronDirectory", "/tmp/m12/member-" + memberId + "/aeron");
      start.put("archiveDirectory", "/tmp/m12/member-" + memberId + "/archive");
      start.put("clusterDirectory", "/tmp/m12/member-" + memberId + "/cluster");
    }
    ArrayNode stops = topology.putArray("forcedStops");
    int[] stoppedMembers = {0, 0, 2};
    long[] stoppedPids = {3000, 3003, 3002};
    for (int index = 0; index < stoppedMembers.length; index++) {
      int memberId = stoppedMembers[index];
      ObjectNode stop = stops.addObject();
      stop.put("memberId", memberId);
      stop.put("processId", stoppedPids[index]);
      stop.put("destroyForciblyRequested", true);
      stop.put("exitCode", 137);
      stop.put("externalController", true);
      stop.put("roleBeforeStop", index == 0 ? "LEADER" : "FOLLOWER");
      stop.put("termBeforeStop", index == 0 ? 1 : 2);
      stop.put("commitPositionBeforeStop", 100);
      stop.put("logPositionBeforeStop", 100);
      stop.put("componentErrorCount", 0);
      stop.put("diagnosticWarningCount", 0);
      stop.put("droppedDiagnosticWarnings", 0);
      stop.putArray("diagnosticWarnings");
    }
  }

  private static void addRestartSafetyWitness(
      ObjectNode witness, int ordinal, int memberId, long stoppedProcessId) {
    long lastActivity = 1_000L + ordinal;
    long observedAt = lastActivity + 10_001L;
    witness.put("ordinal", ordinal);
    witness.put("memberId", memberId);
    witness.put("stoppedProcessId", stoppedProcessId);
    witness.put("archiveMarkFile", "/tmp/m12/member-" + memberId + "/archive/archive-mark.dat");
    witness.put("lastActivityTimestampMillis", lastActivity);
    witness.put("observedAtMillis", observedAt);
    witness.put("ageMillis", observedAt - lastActivity);
    witness.put("livenessTimeoutMillis", 10_000);
    witness.put("probeCount", 1);
    witness.put("waitElapsedNanos", 1_000_000);
    witness.put("aeronVersion", "1.52.2");
    witness.put("predicate", "ARCHIVE_MARK_FILE_ACTIVITY_AGE_GT_LIVENESS_TIMEOUT");
    witness.put("activityTimestampPositive", true);
    witness.put("ageStrictlyExceedsLivenessTimeout", true);
  }

  private static void enrichLeadership(ObjectNode leadership) {
    leadership.put("initialLeaderId", 0);
    leadership.put("initialLeadershipTermId", 1);
    leadership.put("faultTargetLeaderId", 0);
    leadership.put("faultTargetLeadershipTermId", 1);
    leadership.put("replacementLeaderId", 1);
    leadership.put("replacementLeadershipTermId", 2);
    leadership.put("leadershipTermAdvanced", true);
    leadership.put("staleLeaderAcknowledgements", 0);
    ArrayNode generations = leadership.putArray("clientGenerations");
    for (int generation = 1; generation <= 3; generation++) {
      generations
          .addObject()
          .put("clientGeneration", generation)
          .put("clusterSessionId", generation)
          .put("leadershipTermId", generation == 1 ? 1 : 2)
          .put("leaderMemberId", generation == 1 ? 0 : 1)
          .put("acceptedOffers", 1)
          .put("decodedResponses", 1)
          .put("rejectedResponses", 0)
          .put("componentErrors", 0);
    }
  }

  private static void enrichArchitecture(ObjectNode architecture) {
    architecture.put("headCoreTree", architecture.path("m11CoreTree").stringValue());
    architecture.put("headGoldensTree", architecture.path("m11GoldensTree").stringValue());
    architecture.put("m11ClusteredServiceAdapterBaselineSha256", "a".repeat(64));
    architecture.put("m12ClusteredServiceAdapterSha256", "b".repeat(64));
  }

  private static void addTopologyMember(
      ObjectNode member,
      int memberId,
      long processId,
      String role,
      long term,
      int nextSequence,
      int identities,
      OracleState state) {
    int portBase = 52100 + memberId * 10;
    member.put("memberId", memberId);
    member.put("processId", processId);
    member.put("role", role);
    member.put("leadershipTermId", term);
    member.put("nextApplicationSequence", nextSequence);
    member.put("identityCount", identities);
    member.put("semanticStateDigest", state.semanticDigest());
    member.put("identityResultDigest", state.identityDigest());
    member.put("aeronDirectory", "/tmp/m12/member-" + memberId + "/aeron");
    member.put("archiveDirectory", "/tmp/m12/member-" + memberId + "/archive");
    member.put("clusterDirectory", "/tmp/m12/member-" + memberId + "/cluster");
    member.put("portBlockBase", portBase);
    addPorts(member.putArray("udpPorts"), portBase);
    member.put("componentErrorCount", 0);
  }

  private static void addStabilityStatus(
      ObjectNode status,
      int memberId,
      long processId,
      long statusSequence,
      String role,
      long term,
      int nextSequence,
      int identityCount,
      OracleState state) {
    status.put("memberId", memberId);
    status.put("processId", processId);
    status.put("statusSequence", statusSequence);
    status.put("observedAtEpochMillis", 1_000 + statusSequence);
    status.put("role", role);
    status.put("electionState", "CLOSED");
    status.put("leadershipTermId", term);
    status.put("commitPosition", nextSequence == 67 ? 1000 : 100);
    status.put("logPosition", nextSequence == 67 ? 1000 : 100);
    status.put("nextApplicationSequence", nextSequence);
    status.put("identityCount", identityCount);
    status.put("semanticStateDigest", state.semanticDigest());
    status.put("identityResultDigest", state.identityDigest());
    status.put("componentErrorCount", 0);
    status.put("diagnosticWarningCount", 0);
    status.put("droppedDiagnosticWarnings", 0);
    status.putArray("diagnosticWarnings");
  }

  private static void addStatus(
      ObjectNode status,
      int memberId,
      long processId,
      String role,
      long term,
      int nextSequence,
      int identityCount,
      String semanticDigest,
      String identityDigest) {
    status.put("memberId", memberId);
    status.put("processId", processId);
    status.put("role", role);
    status.put("electionState", "CLOSED");
    status.put("leadershipTermId", term);
    status.put("commitPosition", 100);
    status.put("logPosition", 100);
    status.put("nextApplicationSequence", nextSequence);
    status.put("identityCount", identityCount);
    status.put("semanticStateDigest", semanticDigest);
    status.put("identityResultDigest", identityDigest);
    status.put("componentErrorCount", 0);
    status.put("diagnosticWarningCount", 0);
    status.put("droppedDiagnosticWarnings", 0);
    status.putArray("diagnosticWarnings");
  }

  private static void addStableWitness(
      ObjectNode witness,
      int ordinal,
      long[] processIds,
      int leaderId,
      long term,
      int nextSequence,
      int identityCount,
      OracleState state) {
    witness.put("ordinal", ordinal);
    witness.put("condition", "test stable topology " + ordinal);
    witness.put("statusPublishIntervalMillis", 10);
    witness.put("freshnessBoundMillis", 100);
    witness.put("elapsedNanos", 1_000_000);
    witness.put("allMemberStatusSequencesAdvanced", true);
    witness.put("monotonicElapsedWithinFreshnessBound", true);
    ArrayNode first = witness.putArray("firstSnapshot");
    ArrayNode second = witness.putArray("secondSnapshot");
    for (int memberId = 0; memberId < 3; memberId++) {
      String role = memberId == leaderId ? "LEADER" : "FOLLOWER";
      addStabilityStatus(
          first.addObject(),
          memberId,
          processIds[memberId],
          ordinal * 10L,
          role,
          term,
          nextSequence,
          identityCount,
          state);
      addStabilityStatus(
          second.addObject(),
          memberId,
          processIds[memberId],
          ordinal * 10L + 1L,
          role,
          term,
          nextSequence,
          identityCount,
          state);
    }
  }

  private static void nullable(ObjectNode node, String field, Object value) {
    if (value == null) {
      node.putNull(field);
    } else if (value instanceof Number number) {
      node.put(field, number.longValue());
    } else if (value instanceof Enum<?> enumeration) {
      node.put(field, enumeration.name());
    } else {
      node.put(field, value.toString());
    }
  }

  private static OracleState oracleState(M12DeterministicCorpus.Corpus corpus, int identities) {
    DirectM11MatchingRuntime runtime = new DirectM11MatchingRuntime();
    for (int index = 0; index < identities; index++) {
      M12DeterministicCorpus.DurableIdentity identity = corpus.identities().get(index);
      var response =
          runtime
              .submit(
                  M12DeterministicCorpus.requestFor(
                      identity, new java.util.UUID(0x4d31322d54455354L, index + 1L)))
              .response();
      assertEquals("NEW_APPLIED", response.status().name());
    }
    return new OracleState(
        runtime.semanticStateDigest(),
        new M11RuntimeStateCodec().identityTableDigest(runtime.stateImage().identityBindings()));
  }

  private static byte[] canonicalCommandBytes(M12DeterministicCorpus.Corpus corpus) {
    int size =
        corpus.identities().stream()
            .mapToInt(identity -> Integer.BYTES + identity.canonicalBytes().length)
            .sum();
    ByteBuffer buffer = ByteBuffer.allocate(size);
    corpus
        .identities()
        .forEach(
            identity -> {
              byte[] bytes = identity.canonicalBytes();
              buffer.putInt(bytes.length).put(bytes);
            });
    return buffer.array();
  }

  private static void enrichCoverage(
      ObjectNode coverage,
      M12WorkloadLoader.Workload workload,
      M12DeterministicCorpus.Corpus corpus) {
    List<M12CoverageLedger.Fact> facts = new ArrayList<>();
    for (String obligation : workload.coverageRequirements()) {
      String scenarioId =
          workload.scenarios().stream()
              .filter(scenario -> scenario.proofObligations().contains(obligation))
              .map(M12WorkloadLoader.Scenario::id)
              .findFirst()
              .orElseThrow();
      boolean systemControl = "SYSTEM_ERROR_NEVER_SEMANTIC".equals(obligation);
      String sourceArtifact =
          systemControl ? "m12-system-controls.json" : "m12-command-history.json";
      String producer =
          systemControl
              ? "M12CoverageLedger#assertSystemControls(SYSTEM_ERROR_NEVER_SEMANTIC)"
              : "M12HistoryJudge#assertObligation(" + obligation + ')';
      facts.add(
          M12CoverageLedger.Fact.executed(
              obligation,
              scenarioId,
              sourceArtifact,
              "M12." + scenarioId + '.' + obligation + ".V1",
              producer,
              "fixture assertion for " + obligation,
              "fixture observation for " + obligation));
    }
    coverage.put("semanticDigest", corpus.expectedFinalSemanticDigest());
    coverage.put("ledgerSha256", M12CoverageLedger.ledgerSha256(facts));
    ArrayNode ledger = coverage.putArray("factLedger");
    facts.forEach(fact -> fact.write(ledger.addObject()));
    ArrayNode witnesses = coverage.putArray("witnesses");
    facts.forEach(
        fact ->
            witnesses
                .addObject()
                .put("obligation", fact.obligation())
                .put("scenarioId", fact.scenarioId())
                .put("assertionId", fact.assertionId())
                .put("witnessSha256", fact.witnessSha256())
                .put("executed", true)
                .put("passed", true));
  }

  private static void enrichJudgeInspection(
      ObjectNode judge, M12DeterministicCorpus.Corpus corpus) {
    judge.put("realAeronChildProcesses", true);
    judge.put("assertions", 24);
    judge.put("semanticDigest", corpus.expectedFinalSemanticDigest());
    judge.put("acknowledged", 82);
    judge.put("unknown", 2);
    judge.put("notSubmitted", 1);
    judge.put("sameIdentityRetries", 18);
    judge.put("duplicateReplays", 17);
    judge.put("noQuorumRetryStatus", "NEW_APPLIED");
  }

  private static void addPorts(ArrayNode ports, int base) {
    for (int offset = 1; offset <= 5; offset++) ports.add(base + offset);
  }

  private static void enrichEnvironment(ObjectNode environment, Path clusterRoot) {
    environment.put("schemaVersion", "matching.m12.environment.v1");
    environment.put("status", M12CheckRunner.PASS);
    environment.put("javaRuntime", "test-runtime");
    environment.put("javaVersion", "25-test");
    environment.put("javaVendor", "test-vendor");
    environment.put("vmName", "test-vm");
    ArrayNode arguments = environment.putArray("jvmArguments");
    arguments.add("-Dm12.evidence.test=true");
    environment.put("osName", "test-os");
    environment.put("osVersion", "1");
    environment.put("osArchitecture", "test-arch");
    environment.put("availableProcessors", 1);
    environment.put("physicalMemoryBytes", 8_589_934_592L);
    environment.put("maximumHeapBytes", 1_073_741_824L);
    environment.putArray("garbageCollectorNames").add("Test GC");
    environment.put("cpuModel", "test-cpu");
    environment.put("storageDevice", "test-storage");
    environment.put("filesystem", "test-filesystem");
    environment.put("powerPolicy", "test-power-policy");
    environment.put("walRoot", clusterRoot.toString());
    environment.put("walRootUri", clusterRoot.toUri().toASCIIString());
    environment.put("walFileStoreName", "test-store");
    environment.put("walFileStoreType", "test-type");
    environment.put("walFileStoreTotalSpaceBytes", 1_000_000L);
    environment.put("walFileStoreUsableSpaceBytes", 700_000L);
    environment.put("walFileStoreUnallocatedSpaceBytes", 800_000L);
    environment.put("runStartedAt", "2026-09-03T00:00:00Z");
    environment.put("runFinishedAt", "2026-09-03T00:00:01Z");
    environment.put("correctnessOnly", true);
    environment.put("performanceQualified", false);
    environment.put("singleHost", true);
  }

  private static void enrichStateEquivalence(
      ObjectNode state, M12DeterministicCorpus.Corpus corpus) {
    String semanticDigest = corpus.expectedFinalSemanticDigest();
    String identityDigest = corpus.expectedIdentityResultDigest();
    state.put("semanticStateDigest", semanticDigest);
    state.put("expectedSemanticStateDigest", semanticDigest);
    state.put("stateEquivalent", true);
    state.put("memberCount", 3);
    state.put("nextApplicationSequence", 67);
    state.put("identityCount", 66);
    state.put("identityResultDigest", identityDigest);
    state.put("expectedIdentityResultDigest", identityDigest);
    state.put("allMembersIdentityResultDigestMatchDirectOracle", true);
    state.put("allMembersIdentityCountExact", true);
    state.put("commitPosition", 1000);
    state.put("logPosition", 1000);
    state.put("componentErrorCount", 0);
    state.put("diagnosticWarningCount", 0);
    state.put("droppedDiagnosticWarnings", 0);
    ArrayNode members = state.putArray("members");
    for (int memberId = 0; memberId < 3; memberId++) {
      members
          .addObject()
          .put("memberId", memberId)
          .put("processId", memberId == 0 ? 3004 : memberId == 1 ? 3001 : 3005)
          .put("role", memberId == 1 ? "LEADER" : "FOLLOWER")
          .put("electionState", "CLOSED")
          .put("leadershipTermId", 2)
          .put("commitPosition", 1000)
          .put("logPosition", 1000)
          .put("nextApplicationSequence", 67)
          .put("identityCount", 66)
          .put("semanticStateDigest", semanticDigest)
          .put("identityResultDigest", identityDigest)
          .put("componentErrorCount", 0)
          .put("diagnosticWarningCount", 0)
          .put("droppedDiagnosticWarnings", 0)
          .putArray("diagnosticWarnings");
    }
  }

  private record OracleState(String semanticDigest, String identityDigest) {}

  private static JsonNode materialize(
      JsonNode rootSchema, JsonNode schema, String field, int ordinal) {
    if (schema.has("$ref")) {
      String reference = schema.path("$ref").stringValue();
      if (reference == null || !reference.startsWith("#/")) {
        throw new IllegalStateException("unsupported M12 schema reference: " + reference);
      }
      return materialize(rootSchema, rootSchema.at(reference.substring(1)), field, ordinal);
    }
    if (schema.has("allOf")) {
      ObjectNode merged = JsonSupport.MAPPER.createObjectNode();
      for (JsonNode part : schema.path("allOf")) {
        JsonNode value = materialize(rootSchema, part, field, ordinal);
        if (!(value instanceof ObjectNode object)) {
          throw new IllegalStateException("M12 allOf fixture is not an object");
        }
        merged.setAll(object);
      }
      return merged;
    }
    if (schema.has("oneOf")) {
      return materialize(rootSchema, schema.path("oneOf").get(0), field, ordinal);
    }
    if (schema.has("const")) return schema.path("const").deepCopy();
    if (schema.has("enum")) return schema.path("enum").get(0).deepCopy();
    String type = schema.path("type").stringValue();
    if ("object".equals(type)) {
      ObjectNode object = JsonSupport.MAPPER.createObjectNode();
      for (JsonNode required : schema.path("required")) {
        String name = required.stringValue();
        object.set(
            name, materialize(rootSchema, schema.path("properties").path(name), name, ordinal));
      }
      return object;
    }
    if ("array".equals(type)) {
      ArrayNode array = JsonSupport.MAPPER.createArrayNode();
      int minimum =
          schema.path("minItems").isIntegralNumber() ? schema.path("minItems").intValue() : 0;
      for (int index = 0; index < minimum; index++) {
        array.add(materialize(rootSchema, schema.path("items"), field, index));
      }
      return array;
    }
    if ("string".equals(type)) {
      String pattern = schema.has("pattern") ? schema.path("pattern").stringValue() : "";
      if (pattern.contains("{64}")) return StringNode.valueOf("a".repeat(64));
      if (pattern.contains("{40}")) return StringNode.valueOf("b".repeat(40));
      if (pattern.contains("-CONTROL")) return StringNode.valueOf("M12-TEST-CONTROL");
      if (pattern.startsWith("^M12\\.")) return StringNode.valueOf("M12.TEST.TEST.V1");
      if (pattern.startsWith("^M12-")) return StringNode.valueOf("M12-TEST");
      if (pattern.startsWith("^cex-m12-")) return StringNode.valueOf("cex-m12-test");
      if (pattern.contains("[A-Z0-9_]+")) return StringNode.valueOf("TEST_FAILURE");
      if (schema.has("format") && "date-time".equals(schema.path("format").stringValue())) {
        return StringNode.valueOf("2026-09-03T00:00:00Z");
      }
      if (schema.has("format") && "uuid".equals(schema.path("format").stringValue())) {
        return StringNode.valueOf(String.format("00000000-0000-0000-0000-%012d", ordinal + 1));
      }
      return StringNode.valueOf("path".equals(field) ? "artifact-" + ordinal : "value");
    }
    if ("integer".equals(type)) {
      long minimum =
          schema.path("minimum").isIntegralNumber() ? schema.path("minimum").longValue() : 0;
      return LongNode.valueOf(minimum);
    }
    if ("boolean".equals(type)) return BooleanNode.FALSE;
    if ("null".equals(type)) return NullNode.getInstance();
    throw new IllegalStateException("unsupported M12 fixture at " + field + ": " + schema);
  }

  private static Set<String> expectedEvidencePaths() {
    Set<String> expected = new LinkedHashSet<>();
    expected.add("inputs/workload-v1.json");
    for (String schema : M12EvidenceWriter.EVIDENCE_SCHEMAS) {
      expected.add("schemas/" + Path.of(schema).getFileName());
    }
    M12CheckRunner.OUTPUTS.forEach(name -> expected.add("reports/check/" + name));
    expected.add("reports/check/check.json");
    expected.add("manifest.json");
    return expected;
  }

  private static JsonNode claim(JsonNode manifest, String id) {
    return manifest
        .path("claims")
        .valueStream()
        .filter(item -> id.equals(item.path("id").stringValue()))
        .findFirst()
        .orElseThrow();
  }

  private static Path staging(Path root) {
    return stagingDirectories(root).stream().findFirst().orElseThrow();
  }

  private static List<Path> stagingDirectories(Path root) {
    Path parent = root.resolve("build/lab-evidence");
    if (!Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)) return List.of();
    try (var paths = Files.list(parent)) {
      return paths
          .filter(path -> path.getFileName().toString().startsWith(".M12-staging-"))
          .sorted()
          .toList();
    } catch (IOException failure) {
      throw new IllegalStateException("cannot inspect M12 staging directories", failure);
    }
  }

  private static List<Path> backupDirectories(Path root) {
    Path parent = root.resolve("build/lab-evidence");
    if (!Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)) return List.of();
    try (var paths = Files.list(parent)) {
      return paths
          .filter(path -> path.getFileName().toString().startsWith(".M12-backup-"))
          .sorted()
          .toList();
    } catch (IOException failure) {
      throw new IllegalStateException("cannot inspect M12 backup directories", failure);
    }
  }

  private static Set<String> fileInventory(Path root) {
    if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return new LinkedHashSet<>();
    try (var paths = Files.walk(root)) {
      return new LinkedHashSet<>(
          paths
              .filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
              .map(root::relativize)
              .map(path -> path.toString().replace(java.io.File.separatorChar, '/'))
              .sorted()
              .toList());
    } catch (IOException failure) {
      throw new IllegalStateException("cannot inventory M12 test evidence", failure);
    }
  }

  private static void mutate(Path path) {
    write(path, "mutated M12 artifact\n".getBytes(StandardCharsets.UTF_8));
  }

  private static void tamperAndRebind(Lab lab, String reportName, String checkField) {
    Path reportPath = lab.checks().resolve(reportName);
    ObjectNode report = (ObjectNode) JsonSupport.parse(read(reportPath));
    report.put("unexpectedAuditBypass", true);
    rebindReport(lab, reportName, checkField, report);
  }

  private static void rebindReport(
      Lab lab, String reportName, String checkField, ObjectNode report) {
    Path reportPath = lab.checks().resolve(reportName);
    byte[] bytes = JsonSupport.prettyBytes(report);
    write(reportPath, bytes);

    Path checkPath = lab.checks().resolve("check.json");
    ObjectNode check = (ObjectNode) JsonSupport.parse(read(checkPath));
    check.set(checkField, report.deepCopy());
    check
        .path("artifactBindings")
        .valueStream()
        .filter(binding -> reportName.equals(binding.path("path").stringValue()))
        .findFirst()
        .map(ObjectNode.class::cast)
        .orElseThrow()
        .put("bytes", bytes.length)
        .put("sha256", Hashing.sha256Hex(bytes));
    write(checkPath, JsonSupport.prettyBytes(check));
  }

  private static void moveProductTagToStart(Lab lab) {
    git(lab.root(), "tag", "-d", M12EvidenceWriter.PRODUCT_RELEASE);
    git(
        lab.root(),
        "tag",
        "-a",
        M12EvidenceWriter.PRODUCT_RELEASE,
        "-m",
        "test: moved product tag",
        lab.startCommit());
  }

  private static void emptyCommit(Path root, String message) {
    git(root, "commit", "-q", "--allow-empty", "-m", message);
  }

  private static void annotatedTag(Path root, String tag) {
    git(root, "tag", "-a", tag, "-m", "test: " + tag);
  }

  private static String course() {
    return """
    case=high-availability-cex
    profile=SPOT-CEX-1.0
    planVersion=0.15
    project=matching
    unit=M12
    lifecycle=COMPLETE
    designDepth=IMPLEMENTED
    startRef=course/m12-start
    completeRef=course/m12-complete
    productRelease=matching-0.8.0
    m12Check.expectedStatus=PASS
    evidencePath=build/lab-evidence/M12/manifest.json
    """;
  }

  private static byte[] read(Path path) {
    try {
      return Files.readAllBytes(path);
    } catch (IOException failure) {
      throw new IllegalStateException("cannot read M12 test artifact", failure);
    }
  }

  private static void copy(Path source, Path target) {
    try {
      Files.createDirectories(target.getParent());
      Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
    } catch (IOException failure) {
      throw new IllegalStateException("cannot copy M12 test input", failure);
    }
  }

  private static void write(Path path, byte[] bytes) {
    try {
      Files.createDirectories(path.getParent());
      Files.write(path, bytes);
    } catch (IOException failure) {
      throw new IllegalStateException("cannot write M12 test artifact", failure);
    }
  }

  private static void createDirectories(Path path) {
    try {
      Files.createDirectories(path);
    } catch (IOException failure) {
      throw new IllegalStateException("cannot create M12 test directory", failure);
    }
  }

  private static void createSymlink(Path link, Path target) {
    try {
      Files.createSymbolicLink(link, target);
    } catch (IOException failure) {
      throw new IllegalStateException("cannot create M12 test symlink", failure);
    }
  }

  private static void delete(Path path) {
    try {
      Files.delete(path);
    } catch (IOException failure) {
      throw new IllegalStateException("cannot delete M12 test path", failure);
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
      if (exit != 0) throw new IllegalStateException("git failed: " + error);
      return output;
    } catch (IOException | InterruptedException failure) {
      if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
      throw new IllegalStateException("cannot run git in M12 evidence test", failure);
    }
  }

  private enum ReleaseMutation {
    LIGHTWEIGHT_UNIT {
      @Override
      void apply(Lab lab) {
        git(lab.root(), "tag", "-d", M12EvidenceWriter.UNIT_TAG);
        git(lab.root(), "tag", M12EvidenceWriter.UNIT_TAG);
      }
    },
    LIGHTWEIGHT_PRODUCT {
      @Override
      void apply(Lab lab) {
        git(lab.root(), "tag", "-d", M12EvidenceWriter.PRODUCT_RELEASE);
        git(lab.root(), "tag", M12EvidenceWriter.PRODUCT_RELEASE);
      }
    },
    PRODUCT_NOT_AT_HEAD {
      @Override
      void apply(Lab lab) {
        git(lab.root(), "tag", "-d", M12EvidenceWriter.PRODUCT_RELEASE);
        git(
            lab.root(),
            "tag",
            "-a",
            M12EvidenceWriter.PRODUCT_RELEASE,
            "-m",
            "test: product tag at start",
            lab.startCommit());
      }
    };

    abstract void apply(Lab lab);
  }

  private enum HashMutationBoundary {
    SOURCE_BEFORE_PUBLICATION,
    PUBLISHED_TREE
  }

  private enum StrictChildReport {
    HISTORY("m12-command-history.json", "commandOutcomes"),
    TOPOLOGY("topology.json", "clusterTopology"),
    LEADERSHIP("leadership.json", "leadership"),
    QUORUM("quorum.json", "quorum"),
    CATCHUP("catchup.json", "catchup"),
    STATE_EQUIVALENCE("state-equivalence.json", "stateEquivalence"),
    ARCHITECTURE("architecture.json", "architecture");

    private final String reportName;
    private final String checkField;

    StrictChildReport(String reportName, String checkField) {
      this.reportName = reportName;
      this.checkField = checkField;
    }
  }

  private enum ReleaseMutationBoundary {
    BEFORE_PUBLICATION,
    AFTER_PUBLICATION
  }

  private record Lab(
      Path root,
      Path checks,
      Path evidence,
      String sourceCommit,
      String startCommit,
      String inheritedCommit) {
    M12EvidenceWriter.ReleaseExpectations expectations() {
      return new M12EvidenceWriter.ReleaseExpectations(startCommit, inheritedCommit);
    }
  }
}
