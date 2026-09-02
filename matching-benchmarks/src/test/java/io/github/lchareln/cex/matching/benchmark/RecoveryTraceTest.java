package io.github.lchareln.cex.matching.benchmark;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RecoveryTraceTest {
  @TempDir Path temporary;

  @Test
  void acceptedFieldsReconstructTheExactV2BinaryHash() throws Exception {
    QualificationArtifactSink.PointIdentity point =
        new QualificationArtifactSink.PointIdentity("point-1", "MEASUREMENT", 1, 250, 100);
    RecoveryTrace.Entry first =
        new RecoveryTrace.Entry(1, "point-1", "op-1", 0, new byte[] {1, 2}, "r1", "s1");
    RecoveryTrace.Entry second =
        new RecoveryTrace.Entry(2, "point-1", "op-2", 2, new byte[] {3, 4}, "r2", "s2");
    RecoveryTrace trace = new RecoveryTrace(temporary.resolve("trace.m10r"), "trace-1");
    trace.append(point, "op-1", 0, first.envelope(), "r1", "s1");
    trace.append(point, "op-2", 2, second.envelope(), "r2", "s2");
    trace.close();

    MessageDigest reconstructed = RecoveryTrace.beginReconstructedDigest("trace-1");
    RecoveryTrace.updateReconstructedDigest(reconstructed, first);
    RecoveryTrace.updateReconstructedDigest(reconstructed, second);

    assertEquals(trace.sha256(), HexFormat.of().formatHex(reconstructed.digest()));
  }
}
