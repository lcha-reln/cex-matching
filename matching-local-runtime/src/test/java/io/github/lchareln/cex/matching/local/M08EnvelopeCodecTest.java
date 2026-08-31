package io.github.lchareln.cex.matching.local;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.lchareln.cex.matching.MarketMode;
import io.github.lchareln.cex.matching.MarketRuleSetArtifact;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class M08EnvelopeCodecTest {
  private static final long SHARD = 7;
  private static final UUID COMMAND_ID = UUID.fromString("12345678-1234-5678-9abc-def012345678");

  private final M08CommandCodec commandCodec = new M08CommandCodec();
  private final M08EnvelopeCodec envelopeCodec = new M08EnvelopeCodec(commandCodec);

  @Test
  void roundTripsEveryFrozenCommandIncludingReservedM07StpFields() throws Exception {
    MarketRuleSetArtifact bootstrap = MarketRuleSetArtifact.bootstrap();
    List<M08Command> commands =
        List.of(
            new M08Command.Place(
                "BTC-USDT",
                BigInteger.valueOf(101),
                "BUY",
                BigInteger.valueOf(20_000),
                BigInteger.valueOf(3),
                "FOK",
                88,
                "CANCEL_MAKER",
                Optional.of(bootstrap.identity())),
            new M08Command.Cancel("BTC-USDT", BigInteger.valueOf(101)),
            new M08Command.PrepareRuleSet(bootstrap.identity(), bootstrap),
            new M08Command.ActivateRuleSet(9, bootstrap.identity(), bootstrap.identity()),
            new M08Command.ChangeMarketMode(10, MarketMode.OPEN, MarketMode.HALTED, "ops"),
            new M08Command.MassCancel(11, MarketMode.HALTED, "ops"));

    long sequence = 1;
    for (M08Command command : commands) {
      byte[] first = envelopeCodec.encode("producer-a", 3, SHARD, sequence, COMMAND_ID, command);
      byte[] second = envelopeCodec.encode("producer-a", 3, SHARD, sequence, COMMAND_ID, command);
      assertArrayEquals(first, second);
      M08Envelope decoded = envelopeCodec.decodeCanonical(first, SHARD);
      assertEquals(command, decoded.command());
      assertEquals(new Slot("producer-a", 3, SHARD, sequence), decoded.slot());
      assertArrayEquals(commandCodec.encode(command), decoded.commandPayload());
      sequence++;
    }
  }

  @Test
  void rejectsHashMismatchTrailingBytesWrongShardAndEnvelopeLimit() throws Exception {
    byte[] canonical = envelope(1, COMMAND_ID, cancel(1));

    byte[] payloadChanged = canonical.clone();
    payloadChanged[payloadChanged.length - 1] ^= 1;
    assertCode(
        StructuralRejectionCode.PAYLOAD_HASH_MISMATCH,
        assertThrows(
            StructuralRejectionException.class,
            () -> envelopeCodec.decodeCanonical(payloadChanged, SHARD)));

    byte[] trailing = Arrays.copyOf(canonical, canonical.length + 1);
    assertCode(
        StructuralRejectionCode.NON_CANONICAL_ENVELOPE,
        assertThrows(
            StructuralRejectionException.class,
            () -> envelopeCodec.decodeCanonical(trailing, SHARD)));

    assertCode(
        StructuralRejectionCode.WRONG_SHARD,
        assertThrows(
            StructuralRejectionException.class,
            () -> envelopeCodec.decodeCanonical(canonical, SHARD + 1)));

    byte[] oversized = new byte[M08EnvelopeCodec.MAX_ENVELOPE_BYTES + 1];
    assertCode(
        StructuralRejectionCode.ENVELOPE_SIZE_LIMIT,
        assertThrows(
            StructuralRejectionException.class,
            () -> envelopeCodec.decodeCanonical(oversized, SHARD)));
  }

  @Test
  void commandPayloadIsDefensivelyCopied() throws Exception {
    M08Envelope decoded = envelopeCodec.decodeCanonical(envelope(1, COMMAND_ID, cancel(1)), SHARD);
    byte[] first = decoded.commandPayload();
    first[0] ^= 1;
    assertArrayEquals(commandCodec.encode(cancel(1)), decoded.commandPayload());
    assertInstanceOf(M08Command.Cancel.class, decoded.command());
  }

  private byte[] envelope(long sequence, UUID commandId, M08Command command) {
    return envelopeCodec.encode("producer-a", 1, SHARD, sequence, commandId, command);
  }

  private static M08Command.Cancel cancel(long orderId) {
    return new M08Command.Cancel("BTC-USDT", BigInteger.valueOf(orderId));
  }

  private static void assertCode(
      StructuralRejectionCode expected, StructuralRejectionException actual) {
    assertEquals(expected, actual.code());
  }
}
