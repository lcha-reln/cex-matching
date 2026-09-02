package io.github.lchareln.cex.matching.benchmark;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.lchareln.cex.matching.local.LocalMatchingRuntime;
import io.github.lchareln.cex.matching.local.M08Command;
import io.github.lchareln.cex.matching.local.M08EnvelopeCodec;
import io.github.lchareln.cex.matching.local.RecoverySuffixStats;
import io.github.lchareln.cex.matching.local.SubmissionResult;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RecoveryVerifierTest {
  private static final long SHARD_ID = 1;
  private static final QualificationProfile PROFILE = QualificationProfile.CI_SMOKE;

  @TempDir Path temporary;

  @Test
  void closesRecoveredRuntimeBeforeDirectReplayAndPreservesExactEvidence() throws Exception {
    Fixture fixture = fixture();
    LifecycleProbe lifecycle = new LifecycleProbe();

    RecoveryVerification verification =
        new RecoveryVerifier(lifecycle)
            .verify(
                fixture.walDirectory(),
                fixture.directReplayDirectory(),
                fixture.trace(),
                PROFILE,
                fixture.suffix().records(),
                fixture.suffix().bytes());

    assertEquals(1, lifecycle.maxActiveRuntimes());
    assertEquals(0, lifecycle.activeRuntimes());
    assertEquals(
        List.of(
            "RECOVERED:OPENED", "RECOVERED:CLOSED", "DIRECT_REPLAY:OPENED", "DIRECT_REPLAY:CLOSED"),
        lifecycle.events());
    assertEquals(fixture.trace().traceId(), verification.recoveryTraceId());
    assertEquals(fixture.resultDigests().size(), verification.durableOperations());
    assertEquals(fixture.resultDigests().size(), verification.duplicatesReplayed());
    assertEquals(fixture.aggregateResultDigest(), verification.liveResultDigest());
    assertEquals(fixture.aggregateResultDigest(), verification.recoveredResultDigest());
    assertEquals(fixture.aggregateResultDigest(), verification.directReplayResultDigest());
    assertEquals(fixture.semanticStateDigest(), verification.liveSemanticStateDigest());
    assertEquals(fixture.semanticStateDigest(), verification.recoveredSemanticStateDigest());
    assertEquals(fixture.semanticStateDigest(), verification.directReplaySemanticStateDigest());
    assertEquals(fixture.traceSha256(), verification.recoveryTraceSha256());
    assertEquals(
        PROFILE.recoveryBudgetMaxSuffixRecords(), verification.configuredMaxSuffixRecords());
    assertEquals(PROFILE.recoveryBudgetMaxSuffixBytes(), verification.configuredMaxSuffixBytes());
    assertEquals(fixture.suffix().records(), verification.actualSuffixRecords());
    assertEquals(fixture.suffix().bytes(), verification.actualSuffixBytes());
    assertTrue(verification.recoveryElapsedNanos() > 0);
  }

  @Test
  void recoveryFailureClosesRecoveredRuntimeWithoutStartingDirectReplay() throws Exception {
    Fixture fixture = fixture();
    LifecycleProbe lifecycle = new LifecycleProbe();

    assertThrows(
        IllegalStateException.class,
        () ->
            new RecoveryVerifier(lifecycle)
                .verify(
                    fixture.walDirectory(),
                    fixture.directReplayDirectory(),
                    fixture.trace(),
                    PROFILE,
                    Math.addExact(fixture.suffix().records(), 1),
                    fixture.suffix().bytes()));

    assertEquals(1, lifecycle.maxActiveRuntimes());
    assertEquals(0, lifecycle.activeRuntimes());
    assertEquals(List.of("RECOVERED:OPENED", "RECOVERED:CLOSED"), lifecycle.events());
    assertFalse(Files.exists(fixture.directReplayDirectory()));
  }

  private Fixture fixture() throws Exception {
    Path walDirectory = Files.createDirectory(temporary.resolve("recovered-wal"));
    Path directReplayDirectory = temporary.resolve("direct-replay-wal");
    RecoveryTrace trace = new RecoveryTrace(walDirectory.resolve("recovery-trace.m10r"), "trace-1");
    QualificationArtifactSink.PointIdentity point =
        new QualificationArtifactSink.PointIdentity("point-1", "MEASUREMENT", 1, 250, 100);
    M08EnvelopeCodec codec = new M08EnvelopeCodec();
    List<String> resultDigests = new ArrayList<>();
    String semanticStateDigest = null;
    RecoverySuffixStats suffix;
    try (trace;
        LocalMatchingRuntime runtime =
            LocalMatchingRuntime.open(PROFILE.qualificationWalConfig(walDirectory, SHARD_ID))) {
      for (long sequence = 1; sequence <= 2; sequence++) {
        byte[] envelope = envelope(codec, sequence);
        SubmissionResult.NewDurablyApplied applied =
            assertInstanceOf(SubmissionResult.NewDurablyApplied.class, runtime.submit(envelope));
        resultDigests.add(applied.result().resultDigest());
        semanticStateDigest = applied.result().semanticStateDigest();
        trace.append(
            point,
            "operation-" + sequence,
            0,
            envelope,
            applied.result().resultDigest(),
            applied.result().semanticStateDigest());
      }
      suffix = runtime.recoverySuffixStats();
    }
    return new Fixture(
        walDirectory,
        directReplayDirectory,
        trace,
        List.copyOf(resultDigests),
        aggregateResultDigest(resultDigests),
        semanticStateDigest,
        trace.sha256(),
        suffix);
  }

  private static byte[] envelope(M08EnvelopeCodec codec, long sequence) {
    M08Command.Place command =
        new M08Command.Place(
            "BTC-USDT",
            BigInteger.valueOf(sequence),
            "BUY",
            BigInteger.valueOf(100),
            BigInteger.ONE,
            "IOC",
            0,
            "NONE",
            Optional.empty());
    return codec.encode(
        "recovery-verifier", 1, SHARD_ID, sequence, new UUID(0x10, sequence), command);
  }

  private static String aggregateResultDigest(List<String> resultDigests) throws Exception {
    MessageDigest digest = MessageDigest.getInstance("SHA-256");
    for (String resultDigest : resultDigests) {
      digest.update(resultDigest.getBytes(StandardCharsets.UTF_8));
      digest.update((byte) '\n');
    }
    return HexFormat.of().formatHex(digest.digest());
  }

  private static final class LifecycleProbe implements RecoveryVerifier.RuntimeLifecycleObserver {
    private final List<String> events = new ArrayList<>();
    private int activeRuntimes;
    private int maxActiveRuntimes;

    @Override
    public void onEvent(
        RecoveryVerifier.RuntimeKind kind, RecoveryVerifier.RuntimeLifecycleEvent event) {
      events.add(kind + ":" + event);
      if (event == RecoveryVerifier.RuntimeLifecycleEvent.OPENED) {
        activeRuntimes = Math.incrementExact(activeRuntimes);
        maxActiveRuntimes = Math.max(maxActiveRuntimes, activeRuntimes);
      } else {
        activeRuntimes = Math.decrementExact(activeRuntimes);
      }
    }

    private List<String> events() {
      return List.copyOf(events);
    }

    private int activeRuntimes() {
      return activeRuntimes;
    }

    private int maxActiveRuntimes() {
      return maxActiveRuntimes;
    }
  }

  private record Fixture(
      Path walDirectory,
      Path directReplayDirectory,
      RecoveryTrace trace,
      List<String> resultDigests,
      String aggregateResultDigest,
      String semanticStateDigest,
      String traceSha256,
      RecoverySuffixStats suffix) {}
}
