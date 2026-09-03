package io.github.lchareln.cex.matching.cluster;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.lchareln.cex.matching.local.M08Command;
import io.github.lchareln.cex.matching.local.M08EnvelopeCodec;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class M11ProtocolCompatibilityTest {
  private static final long SHARD = 1;
  private static final UUID CORRELATION = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
  private static final UUID COMMAND = UUID.fromString("11111111-2222-3333-4444-555555555555");

  private final M11RequestCodec requestCodec = new M11RequestCodec();
  private final M11ResponseCodec responseCodec = new M11ResponseCodec();
  private final M11SnapshotCodec snapshotCodec = new M11SnapshotCodec();

  @Test
  void currentEncodersAreByteExactAndCurrentReadersRestorePreviousSnapshot() throws Exception {
    byte[] envelope = goldenEnvelope();
    M11CommandRequest requestV1 = requestCodec.create(1, 1, CORRELATION, envelope, SHARD);
    M11CommandRequest requestV2 = requestCodec.create(2, 2, CORRELATION, envelope, SHARD);
    assertGolden("request-v1.bin", requestCodec.encode(requestV1));
    assertGolden("request-v2.bin", requestCodec.encode(requestV2));

    DirectM11MatchingRuntime direct = new DirectM11MatchingRuntime();
    M11ApplicationResult appliedV2 = direct.submit(requestV2);
    M11CommandResponse responseV1 =
        M11CommandResponse.applied(requestV1, appliedV2.fullResult().orElseThrow());
    assertGolden("response-v1.bin", responseCodec.encode(responseV1));
    assertGolden("response-v2.bin", responseCodec.encode(appliedV2.response()));

    M11Snapshot previous = snapshotCodec.decodeCanonical(golden("snapshot-v1.bin"));
    M11Snapshot current = snapshotCodec.decodeCanonical(golden("snapshot-v2.bin"));
    assertEquals(2, previous.state().identityBindings().size());
    assertEquals(previous.state(), current.state());
    assertGolden("snapshot-v1.bin", snapshotCodec.encode(1, previous.state()));
    assertGolden("snapshot-v2.bin", snapshotCodec.encodeCurrent(current.state()));

    DirectM11MatchingRuntime restored = DirectM11MatchingRuntime.restore(previous.state());
    M11CommandRequest retry = requestV2.withCorrelationId(new UUID(9, 9));
    M11ApplicationResult duplicate = restored.submit(retry);
    assertEquals(M11ResponseStatus.DUPLICATE_REPLAYED, duplicate.response().status());
    assertArrayEquals(
        appliedV2.fullResult().orElseThrow().auditBytes(),
        duplicate.fullResult().orElseThrow().auditBytes());
    assertEquals(
        previous.state().commandState().semanticStateDigest(), restored.semanticStateDigest());
  }

  @Test
  void malformedUnsupportedAndTruncatedBytesFailClosed() throws Exception {
    byte[] request = golden("request-v2.bin");
    byte[] future = request.clone();
    ByteBuffer.wrap(future).putInt(Integer.BYTES, 3);
    assertEquals(
        M11ProtocolException.Code.UNSUPPORTED_VERSION,
        assertThrows(M11ProtocolException.class, () -> requestCodec.decodeCanonical(future, SHARD))
            .code());
    assertEquals(
        M11ProtocolException.Code.TRUNCATED,
        assertThrows(
                M11ProtocolException.class,
                () ->
                    requestCodec.decodeCanonical(Arrays.copyOf(request, request.length - 1), SHARD))
            .code());
    byte[] invalidResponseVersion = request.clone();
    ByteBuffer.wrap(invalidResponseVersion).putInt(28, 3);
    assertEquals(
        M11ProtocolException.Code.INVALID_VALUE,
        assertThrows(
                M11ProtocolException.class,
                () -> requestCodec.decodeCanonical(invalidResponseVersion, SHARD))
            .code());

    byte[] snapshot = golden("snapshot-v2.bin");
    byte[] corrupt = snapshot.clone();
    corrupt[corrupt.length / 2] ^= 1;
    assertEquals(
        M11ProtocolException.Code.CHECKSUM_MISMATCH,
        assertThrows(M11ProtocolException.class, () -> snapshotCodec.decodeCanonical(corrupt))
            .code());
    assertThrows(
        M11ProtocolException.class,
        () -> snapshotCodec.decodeCanonical(Arrays.copyOf(snapshot, snapshot.length - 1)));
  }

  @Test
  void identityConflictsAndCorrelationRetriesDoNotMutateBusinessState() throws Exception {
    DirectM11MatchingRuntime runtime = new DirectM11MatchingRuntime();
    byte[] envelope = goldenEnvelope();
    M11CommandRequest first = requestCodec.create(2, 2, CORRELATION, envelope, SHARD);
    M11ApplicationResult applied = runtime.submit(first);
    String digest = runtime.semanticStateDigest();
    long nextSequence = runtime.nextApplicationSequence();

    M11ApplicationResult duplicate =
        runtime.submit(
            first.withCorrelationId(UUID.fromString("00000000-0000-0000-0000-000000000002")));
    assertEquals(M11ResponseStatus.DUPLICATE_REPLAYED, duplicate.response().status());
    assertNotEquals(applied.response().correlationId(), duplicate.response().correlationId());
    assertEquals(digest, runtime.semanticStateDigest());
    assertEquals(nextSequence, runtime.nextApplicationSequence());

    byte[] conflictingEnvelope =
        new M08EnvelopeCodec().encode("m11-golden", 1, SHARD, 1, COMMAND, cancel(42));
    M11CommandRequest conflict =
        requestCodec.create(2, 2, new UUID(3, 3), conflictingEnvelope, SHARD);
    M11ApplicationResult rejected = runtime.submit(conflict);
    assertEquals(M11ResponseStatus.REJECTED, rejected.response().status());
    assertEquals("COMMAND_ID_CONFLICT", rejected.response().rejectionCode().orElseThrow());
    assertEquals(digest, runtime.semanticStateDigest());
    assertEquals(nextSequence, runtime.nextApplicationSequence());
  }

  @Test
  void frameAccumulatorRejectsReorderingTruncationAndCorruption() throws Exception {
    byte[] snapshot = golden("snapshot-v2.bin");
    M11SnapshotFrameCodec codec = new M11SnapshotFrameCodec();
    var frames = codec.encode(snapshot, 1);
    M11SnapshotFrameCodec.Accumulator accumulator = codec.accumulator();
    frames.forEach(
        frame -> {
          try {
            accumulator.accept(frame);
          } catch (M11ProtocolException failure) {
            throw new AssertionError(failure);
          }
        });
    assertEquals(1, accumulator.snapshotSequence());
    assertArrayEquals(snapshot, accumulator.finish());

    M11SnapshotFrameCodec.Accumulator incomplete = codec.accumulator();
    assertThrows(M11ProtocolException.class, incomplete::finish);

    byte[] wide = new byte[M11SnapshotFrameCodec.MAX_FRAME_PAYLOAD * 2];
    var wideFrames = codec.encode(wide, 0);
    M11SnapshotFrameCodec.Accumulator reordered = codec.accumulator();
    assertThrows(M11ProtocolException.class, () -> reordered.accept(wideFrames.get(1)));

    byte[] corrupt = frames.getFirst().clone();
    corrupt[corrupt.length - 1] ^= 1;
    M11SnapshotFrameCodec.Accumulator corrupted = codec.accumulator();
    corrupted.accept(corrupt);
    assertThrows(M11ProtocolException.class, corrupted::finish);
  }

  private static byte[] goldenEnvelope() {
    return new M08EnvelopeCodec()
        .encode(
            "m11-golden",
            1,
            SHARD,
            1,
            COMMAND,
            new M08Command.Place(
                "BTC-USDT",
                BigInteger.valueOf(42),
                "BUY",
                BigInteger.valueOf(6_500_000),
                BigInteger.valueOf(3),
                "GTC",
                0,
                "NONE",
                Optional.empty()));
  }

  private static M08Command.Cancel cancel(long orderId) {
    return new M08Command.Cancel("BTC-USDT", BigInteger.valueOf(orderId));
  }

  private static void assertGolden(String name, byte[] actual) throws Exception {
    byte[] expected = golden(name);
    assertEquals(expected.length, actual.length, name);
    assertArrayEquals(expected, actual, name);
  }

  private static byte[] golden(String name) throws Exception {
    Path root = Path.of(System.getProperty("matching.repositoryRoot"));
    Path file = root.resolve("matching-testkit/src/test/resources/m11/goldens").resolve(name);
    assertTrue(Files.isRegularFile(file), file.toString());
    return Files.readAllBytes(file);
  }
}
