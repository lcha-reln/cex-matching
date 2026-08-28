package io.github.lchareln.cex.matching.testkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.lchareln.cex.matching.reference.ReferenceCommand;
import io.github.lchareln.cex.matching.reference.SemanticBook;
import io.github.lchareln.cex.matching.reference.SemanticEvent;
import io.github.lchareln.cex.matching.reference.SemanticOutcome;
import java.math.BigInteger;
import java.util.List;
import org.junit.jupiter.api.Test;

final class M03JsonTest {
  @Test
  void rawCommandsRoundTripWithoutLongOrBusinessNormalization() {
    BigInteger widerThanLong = BigInteger.ONE.shiftLeft(100);
    List<ReferenceCommand> commands =
        List.of(
            new ReferenceCommand.Place(
                "\uD83D\uDE80/原始", widerThanLong, "SIDE?", widerThanLong.negate(), BigInteger.ZERO),
            new ReferenceCommand.Cancel("", widerThanLong.negate()));

    assertEquals(commands, M03Json.commands(M03Json.commands(commands)));
    for (ReferenceCommand command : commands) {
      assertEquals(command, M03Json.command(M03Json.command(command)));
    }
  }

  @Test
  void everySemanticEventAndFullDepthBookRoundTripsLosslessly() {
    List<SemanticEvent> events =
        List.of(
            new SemanticEvent.Rejected("INVALID", "side"),
            new SemanticEvent.PlaceRejected(BigInteger.ONE, "DUPLICATE_ORDER_ID"),
            new SemanticEvent.CancelRejected(BigInteger.TWO, "ORDER_ALREADY_FILLED"),
            new SemanticEvent.Accepted(
                BigInteger.ONE, BigInteger.TWO, "BUY", BigInteger.valueOf(101), BigInteger.TEN),
            new SemanticEvent.Trade(
                BigInteger.ONE,
                BigInteger.TWO,
                BigInteger.valueOf(3),
                BigInteger.valueOf(4),
                BigInteger.valueOf(101),
                BigInteger.valueOf(5)),
            new SemanticEvent.Rested(
                BigInteger.valueOf(3),
                BigInteger.valueOf(4),
                "SELL",
                BigInteger.valueOf(102),
                BigInteger.valueOf(6)),
            new SemanticEvent.Canceled(
                BigInteger.valueOf(3),
                BigInteger.valueOf(4),
                "SELL",
                BigInteger.valueOf(102),
                BigInteger.valueOf(6)));
    SemanticBook book =
        new SemanticBook(
            List.of(
                new SemanticBook.PriceLevel(
                    "BUY",
                    BigInteger.valueOf(101),
                    List.of(
                        new SemanticBook.RestingOrder(
                            BigInteger.ONE, BigInteger.TWO, BigInteger.TEN)))),
            List.of(
                new SemanticBook.PriceLevel(
                    "SELL",
                    BigInteger.valueOf(102),
                    List.of(
                        new SemanticBook.RestingOrder(
                            BigInteger.valueOf(3),
                            BigInteger.valueOf(4),
                            BigInteger.valueOf(6))))));
    SemanticOutcome outcome = new SemanticOutcome(events, book);

    for (SemanticEvent event : events) {
      assertEquals(event, M03Json.event(M03Json.event(event)));
    }
    assertEquals(book, M03Json.book(M03Json.book(book)));
    assertEquals(outcome, M03Json.outcome(M03Json.outcome(outcome)));
  }

  @Test
  void unknownDiscriminatorsFailClosed() {
    var command = JsonSupport.MAPPER.createObjectNode();
    command.put("type", "MARKET");
    command.putObject("input");
    var event = JsonSupport.MAPPER.createObjectNode();
    event.put("type", "MYSTERY");

    assertThrows(FixtureSchemaException.class, () -> M03Json.command(command));
    assertThrows(FixtureSchemaException.class, () -> M03Json.event(event));
  }
}
