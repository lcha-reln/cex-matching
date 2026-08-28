package io.github.lchareln.cex.matching.testkit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.lchareln.cex.matching.reference.ReferenceCommand;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

final class M03HistoryGeneratorTest {
  @Test
  void splitMix64V1AndHistorySeedDerivationHaveRepositoryOwnedKnownAnswers() {
    M03SplitMix64V1 random = new M03SplitMix64V1(0L);

    assertEquals("e220a8397b1dcdaf", HexFormat.of().toHexDigits(random.nextLong()));
    assertEquals("6e789e6aa1b965f4", HexFormat.of().toHexDigits(random.nextLong()));
    assertEquals("06c45d188009454f", HexFormat.of().toHexDigits(random.nextLong()));
    assertEquals(new M03SplitMix64V1(6824L).nextLong(), M03HistoryGenerator.historySeed(6824L, 0));
    assertEquals(new M03SplitMix64V1(6825L).nextLong(), M03HistoryGenerator.historySeed(6824L, 1));
    assertEquals(
        "017c2abb12062b72", HexFormat.of().toHexDigits(M03HistoryGenerator.historySeed(6824L, 0)));
    assertEquals(
        "b64bd275be614d7d", HexFormat.of().toHexDigits(M03HistoryGenerator.historySeed(6824L, 1)));
  }

  @Test
  void generatesTheFullFreshHistoryShapeAndPreservesEveryFrozenLanePrefix() {
    M03GeneratorProfile profile = loadProfile();
    List<M03GeneratedHistory> histories = new M03HistoryGenerator().generate(profile);

    assertEquals(256, histories.size());
    assertEquals(16_384, histories.stream().mapToInt(history -> history.commands().size()).sum());
    Map<String, Long> laneCounts =
        histories.stream()
            .map(M03GeneratedHistory::laneId)
            .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));
    assertEquals(
        Map.of(
            "BEST_PRICE", 64L,
            "SAME_PRICE_FIFO", 64L,
            "MAKER_PRICE", 64L,
            "CANCELED_IDENTITY", 64L),
        laneCounts);

    for (M03GeneratedHistory history : histories) {
      M03GeneratorProfile.Lane lane = profile.laneForHistory(history.historyIndex());
      assertEquals(64, history.commands().size());
      assertEquals(lane.id(), history.laneId());
      assertEquals(
          lane.prefix(), history.commands().subList(0, lane.prefix().size()), history::laneId);
      assertEquals(16, history.seedHex().length());
    }
  }

  @Test
  void randomSuffixUsesOnlyTheFrozenValidOrIndependentInvalidFieldDomains() {
    M03GeneratorProfile profile = loadProfile();
    M03GeneratorProfile.RandomDomain domain = profile.randomDomain();
    List<M03GeneratedHistory> histories = new M03HistoryGenerator().generate(profile);
    FieldCounts counts = new FieldCounts();

    for (M03GeneratedHistory history : histories) {
      int prefixSize = profile.laneForHistory(history.historyIndex()).prefix().size();
      for (ReferenceCommand command :
          history.commands().subList(prefixSize, history.commands().size())) {
        if (command instanceof ReferenceCommand.Place place) {
          counts.placeCommands++;
          counts.instrumentInvalid += invalidInstrument(place.instrumentId(), domain);
          counts.orderInvalid +=
              invalidNumber(place.orderId(), domain.minimumOrderId(), domain.maximumOrderId());
          counts.sideInvalid += invalidSide(place.side(), domain);
          counts.priceInvalid +=
              invalidNumber(
                  place.priceTicks(), domain.minimumPriceTicks(), domain.maximumPriceTicks());
          counts.quantityInvalid +=
              invalidNumber(
                  place.quantityLots(), domain.minimumQuantityLots(), domain.maximumQuantityLots());
        } else if (command instanceof ReferenceCommand.Cancel cancel) {
          counts.cancelCommands++;
          counts.instrumentInvalid += invalidInstrument(cancel.instrumentId(), domain);
          counts.orderInvalid +=
              invalidNumber(cancel.orderId(), domain.minimumOrderId(), domain.maximumOrderId());
        }
      }
    }

    assertEquals(15_680, counts.placeCommands + counts.cancelCommands);
    assertEquals(10_173, counts.placeCommands);
    assertEquals(5_507, counts.cancelCommands);
    assertEquals(492, counts.instrumentInvalid);
    assertEquals(494, counts.orderInvalid);
    assertEquals(297, counts.sideInvalid);
    assertEquals(285, counts.priceInvalid);
    assertEquals(299, counts.quantityInvalid);
  }

  @Test
  void twoFreshGenerationsProduceByteIdenticalCanonicalCommandsAndDigest() {
    M03GeneratorProfile profile = loadProfile();
    M03HistoryGenerator generator = new M03HistoryGenerator();
    M03CommandCanonicalizer canonicalizer = new M03CommandCanonicalizer();

    List<M03GeneratedHistory> first = generator.generate(profile);
    List<M03GeneratedHistory> second = generator.generate(profile);
    M03CommandCanonicalizer.CanonicalCommands firstCanonical =
        canonicalizer.canonicalize(profile, first);
    M03CommandCanonicalizer.CanonicalCommands secondCanonical =
        canonicalizer.canonicalize(profile, second);

    assertEquals(first, second);
    assertArrayEquals(firstCanonical.bytes(), secondCanonical.bytes());
    assertEquals(firstCanonical.digest(), secondCanonical.digest());
    assertEquals(
        "sha256:1920d6b8a480998825c72636d446854d9e795e91b0ab29520f203b12186979ce",
        firstCanonical.digest());
    assertEquals(1_682_592, firstCanonical.bytes().length);
    assertEquals(16_384, firstCanonical.commandCount());
    assertTrue(firstCanonical.digest().startsWith("sha256:"));
    assertTrue(
        new String(firstCanonical.bytes(), StandardCharsets.UTF_8)
            .startsWith("M03G1|algorithm=splitmix64-v1|seedDerivation="));
  }

  @Test
  void canonicalBytesAreDefensiveAndEveryRawCommandFieldIsDigestSensitive() {
    M03GeneratorProfile profile = loadProfile();
    List<M03GeneratedHistory> histories = new M03HistoryGenerator().generate(profile);
    M03CommandCanonicalizer canonicalizer = new M03CommandCanonicalizer();
    M03CommandCanonicalizer.CanonicalCommands baseline =
        canonicalizer.canonicalize(profile, histories);

    byte[] exposed = baseline.bytes();
    exposed[0] ^= 1;
    assertNotEquals(exposed[0], baseline.bytes()[0]);

    List<M03GeneratedHistory> changedHistories = new ArrayList<>(histories);
    M03GeneratedHistory firstHistory = histories.getFirst();
    List<ReferenceCommand> changedCommands = new ArrayList<>(firstHistory.commands());
    ReferenceCommand.Place first = (ReferenceCommand.Place) changedCommands.getFirst();
    changedCommands.set(
        0,
        new ReferenceCommand.Place(
            first.instrumentId(),
            first.orderId().add(BigInteger.ONE),
            first.side(),
            first.priceTicks(),
            first.quantityLots()));
    changedHistories.set(
        0,
        new M03GeneratedHistory(
            firstHistory.historyIndex(),
            firstHistory.seed(),
            firstHistory.laneId(),
            changedCommands));

    assertNotEquals(
        baseline.digest(), canonicalizer.canonicalize(profile, changedHistories).digest());
  }

  private static int invalidInstrument(
      String instrumentId, M03GeneratorProfile.RandomDomain domain) {
    assertTrue(
        instrumentId.equals(domain.validInstrumentId())
            || instrumentId.equals(domain.invalidInstrumentId()));
    return instrumentId.equals(domain.invalidInstrumentId()) ? 1 : 0;
  }

  private static int invalidSide(String side, M03GeneratorProfile.RandomDomain domain) {
    assertTrue(domain.validSides().contains(side) || side.equals(domain.invalidSide()));
    return side.equals(domain.invalidSide()) ? 1 : 0;
  }

  private static int invalidNumber(BigInteger value, BigInteger minimum, BigInteger maximum) {
    assertTrue(
        value.signum() == 0 || (value.compareTo(minimum) >= 0 && value.compareTo(maximum) <= 0));
    return value.signum() == 0 ? 1 : 0;
  }

  private static M03GeneratorProfile loadProfile() {
    Path root = Path.of(System.getProperty("matching.repositoryRoot"));
    return M03GeneratorProfile.load(
        root.resolve("matching-testkit/src/test/resources/m03/fixtures/property-suite-v1.json"),
        root.resolve("schemas/matching.m03.generator.v1.schema.json"));
  }

  private static final class FieldCounts {
    private int placeCommands;
    private int cancelCommands;
    private int instrumentInvalid;
    private int orderInvalid;
    private int sideInvalid;
    private int priceInvalid;
    private int quantityInvalid;
  }
}
