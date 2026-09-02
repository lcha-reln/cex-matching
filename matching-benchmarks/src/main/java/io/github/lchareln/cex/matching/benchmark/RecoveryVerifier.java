package io.github.lchareln.cex.matching.benchmark;

import io.github.lchareln.cex.matching.local.LocalMatchingRuntime;
import io.github.lchareln.cex.matching.local.RecoverySuffixStats;
import io.github.lchareln.cex.matching.local.SubmissionResult;
import io.github.lchareln.cex.matching.local.WalConfig;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Reopens the actual M09 directory and duplicate-replays every streamed accepted envelope. */
final class RecoveryVerifier {
  RecoveryVerification verify(
      Path walDirectory,
      Path directReplayDirectory,
      RecoveryTrace trace,
      QualificationProfile profile,
      long actualSuffixRecords,
      long actualSuffixBytes)
      throws IOException {
    trace.close();
    MessageDigest live = sha256();
    MessageDigest recovered = sha256();
    long[] duplicateCount = {0};
    String[] liveSemantic = {null};
    long recoveryStartedNanos = System.nanoTime();
    LocalMatchingRuntime recoveredRuntime =
        LocalMatchingRuntime.open(profile.qualificationWalConfig(walDirectory, 1));
    long recoveryElapsedNanos = Math.subtractExact(System.nanoTime(), recoveryStartedNanos);
    try (LocalMatchingRuntime runtime = recoveredRuntime) {
      RecoverySuffixStats recoveredSuffix = runtime.recoverySuffixStats();
      if (recoveredSuffix.records() != actualSuffixRecords
          || recoveredSuffix.bytes() != actualSuffixBytes) {
        throw new IllegalStateException(
            "fresh reopen recovery suffix differs from decompressed raw accounting: recovered="
                + recoveredSuffix.records()
                + "/"
                + recoveredSuffix.bytes()
                + ", raw="
                + actualSuffixRecords
                + "/"
                + actualSuffixBytes);
      }
      long records =
          RecoveryTrace.read(
              trace.path(),
              trace.traceId(),
              entry -> {
                update(live, entry.resultDigest());
                liveSemantic[0] = entry.semanticStateDigest();
                SubmissionResult replay = runtime.submit(entry.envelope());
                if (!(replay instanceof SubmissionResult.DuplicateReplayed duplicate)) {
                  throw new IllegalStateException(
                      "recovery replay did not return DuplicateReplayed: "
                          + replay.getClass().getSimpleName());
                }
                if (!entry.resultDigest().equals(duplicate.originalResult().resultDigest())) {
                  throw new IllegalStateException("recovery changed canonical result digest");
                }
                update(recovered, duplicate.originalResult().resultDigest());
                duplicateCount[0] = Math.incrementExact(duplicateCount[0]);
              });
      if (records == 0 || liveSemantic[0] == null) {
        throw new IllegalStateException("recovery trace contains no durable operations");
      }
      String recoveredSemantic = runtime.semanticStateDigest();
      String liveResultDigest = HexFormat.of().formatHex(live.digest());
      String recoveredResultDigest = HexFormat.of().formatHex(recovered.digest());
      DirectReplay direct = directReplay(directReplayDirectory, trace, records);
      return new RecoveryVerification(
          trace.traceId(),
          records,
          duplicateCount[0],
          liveResultDigest,
          recoveredResultDigest,
          direct.resultDigest(),
          liveSemantic[0],
          recoveredSemantic,
          direct.semanticStateDigest(),
          trace.sha256(),
          profile.recoveryBudgetMaxSuffixRecords(),
          profile.recoveryBudgetMaxSuffixBytes(),
          recoveredSuffix.records(),
          recoveredSuffix.bytes(),
          recoveryElapsedNanos);
    }
  }

  private static DirectReplay directReplay(
      Path directReplayDirectory, RecoveryTrace trace, long expectedRecords) throws IOException {
    java.nio.file.Files.createDirectory(directReplayDirectory);
    MessageDigest results = sha256();
    long[] applied = {0};
    try (LocalMatchingRuntime runtime =
        LocalMatchingRuntime.open(WalConfig.defaults(directReplayDirectory, 1))) {
      long records =
          RecoveryTrace.read(
              trace.path(),
              trace.traceId(),
              entry -> {
                SubmissionResult result = runtime.submit(entry.envelope());
                if (!(result instanceof SubmissionResult.NewDurablyApplied appliedResult)) {
                  throw new IllegalStateException(
                      "direct replay did not durably apply: " + result.getClass().getSimpleName());
                }
                if (!entry.resultDigest().equals(appliedResult.result().resultDigest())) {
                  throw new IllegalStateException("direct replay changed canonical result digest");
                }
                update(results, appliedResult.result().resultDigest());
                applied[0] = Math.incrementExact(applied[0]);
              });
      if (records != expectedRecords || applied[0] != expectedRecords) {
        throw new IllegalStateException("direct replay record count changed");
      }
      return new DirectReplay(
          HexFormat.of().formatHex(results.digest()), runtime.semanticStateDigest());
    }
  }

  private static MessageDigest sha256() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is unavailable", impossible);
    }
  }

  private static void update(MessageDigest digest, String value) {
    digest.update(value.getBytes(StandardCharsets.UTF_8));
    digest.update((byte) '\n');
  }

  private record DirectReplay(String resultDigest, String semanticStateDigest) {}
}
