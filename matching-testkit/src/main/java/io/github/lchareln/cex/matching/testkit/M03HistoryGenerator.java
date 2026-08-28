package io.github.lchareln.cex.matching.testkit;

import io.github.lchareln.cex.matching.reference.ReferenceCommand;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Pure deterministic command generator; it never observes a production or reference state. */
public final class M03HistoryGenerator {
  public List<M03GeneratedHistory> generate(M03GeneratorProfile profile) {
    Objects.requireNonNull(profile, "profile");
    List<M03GeneratedHistory> histories = new ArrayList<>(profile.histories());
    for (int historyIndex = 0; historyIndex < profile.histories(); historyIndex++) {
      long seed = historySeed(profile.baseSeed(), historyIndex);
      M03SplitMix64V1 random = new M03SplitMix64V1(seed);
      M03GeneratorProfile.Lane lane = profile.laneForHistory(historyIndex);
      List<ReferenceCommand> commands = new ArrayList<>(profile.commandsPerHistory());
      commands.addAll(lane.prefix());
      while (commands.size() < profile.commandsPerHistory()) {
        commands.add(randomCommand(profile.randomDomain(), random));
      }
      histories.add(new M03GeneratedHistory(historyIndex, seed, lane.id(), commands));
    }
    return List.copyOf(histories);
  }

  static long historySeed(long baseSeed, int historyIndex) {
    if (historyIndex < 0) {
      throw new IllegalArgumentException("historyIndex must not be negative");
    }
    return new M03SplitMix64V1(baseSeed + historyIndex).nextLong();
  }

  private static ReferenceCommand randomCommand(
      M03GeneratorProfile.RandomDomain domain, M03SplitMix64V1 random) {
    if (random.nextInt(domain.totalWeight()) < domain.placeWeight()) {
      return randomPlace(domain, random);
    }
    return randomCancel(domain, random);
  }

  private static ReferenceCommand randomPlace(
      M03GeneratorProfile.RandomDomain domain, M03SplitMix64V1 random) {
    String instrumentId =
        invalid(random, domain.invalidOneIn())
            ? domain.invalidInstrumentId()
            : domain.validInstrumentId();
    BigInteger orderId =
        invalid(random, domain.invalidOneIn())
            ? BigInteger.ZERO
            : inRange(random, domain.minimumOrderId(), domain.maximumOrderId());
    String side =
        invalid(random, domain.invalidOneIn())
            ? domain.invalidSide()
            : domain.validSides().get(random.nextInt(domain.validSides().size()));
    BigInteger priceTicks =
        invalid(random, domain.invalidOneIn())
            ? BigInteger.ZERO
            : inRange(random, domain.minimumPriceTicks(), domain.maximumPriceTicks());
    BigInteger quantityLots =
        invalid(random, domain.invalidOneIn())
            ? BigInteger.ZERO
            : inRange(random, domain.minimumQuantityLots(), domain.maximumQuantityLots());
    return new ReferenceCommand.Place(instrumentId, orderId, side, priceTicks, quantityLots);
  }

  private static ReferenceCommand randomCancel(
      M03GeneratorProfile.RandomDomain domain, M03SplitMix64V1 random) {
    String instrumentId =
        invalid(random, domain.invalidOneIn())
            ? domain.invalidInstrumentId()
            : domain.validInstrumentId();
    BigInteger orderId =
        invalid(random, domain.invalidOneIn())
            ? BigInteger.ZERO
            : inRange(random, domain.minimumOrderId(), domain.maximumOrderId());
    return new ReferenceCommand.Cancel(instrumentId, orderId);
  }

  private static boolean invalid(M03SplitMix64V1 random, int oneIn) {
    return random.nextInt(oneIn) == 0;
  }

  private static BigInteger inRange(
      M03SplitMix64V1 random, BigInteger minimum, BigInteger maximum) {
    int bound = maximum.subtract(minimum).add(BigInteger.ONE).intValueExact();
    return minimum.add(BigInteger.valueOf(random.nextInt(bound)));
  }
}
