package io.github.lchareln.cex.matching.testkit;

import io.github.lchareln.cex.matching.reference.ReferenceCommand;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Pure deterministic M04 history generator; it observes no matcher state. */
final class M04HistoryGenerator {
  List<M04GeneratedHistory> generate(M04GeneratorProfile profile) {
    Objects.requireNonNull(profile, "profile");
    List<M04GeneratedHistory> histories = new ArrayList<>(profile.histories());
    for (int historyIndex = 0; historyIndex < profile.histories(); historyIndex++) {
      long seed = historySeed(profile.baseSeed(), historyIndex);
      M03SplitMix64V1 random = new M03SplitMix64V1(seed);
      M04GeneratorProfile.Lane lane = profile.laneForHistory(historyIndex);
      List<ReferenceCommand> commands = new ArrayList<>(profile.commandsPerHistory());
      commands.addAll(lane.prefix());
      while (commands.size() < profile.commandsPerHistory()) {
        commands.add(randomCommand(profile.randomDomain(), random));
      }
      histories.add(new M04GeneratedHistory(historyIndex, seed, lane.id(), commands));
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
      M04GeneratorProfile.RandomDomain domain, M03SplitMix64V1 random) {
    if (random.nextInt(domain.totalCommandWeight()) < domain.placeWeight()) {
      return randomPlace(domain, random);
    }
    return new ReferenceCommand.Cancel(
        invalid(random, domain.invalidOneIn())
            ? domain.invalidInstrumentId()
            : domain.validInstrumentId(),
        invalid(random, domain.invalidOneIn())
            ? BigInteger.ZERO
            : inRange(random, domain.minimumOrderId(), domain.maximumOrderId()));
  }

  private static ReferenceCommand randomPlace(
      M04GeneratorProfile.RandomDomain domain, M03SplitMix64V1 random) {
    String policy =
        invalid(random, domain.unknownPolicyOneIn())
            ? domain.invalidExecutionPolicy()
            : weightedPolicy(domain, random);
    return new ReferenceCommand.Place(
        invalid(random, domain.invalidOneIn())
            ? domain.invalidInstrumentId()
            : domain.validInstrumentId(),
        invalid(random, domain.invalidOneIn())
            ? BigInteger.ZERO
            : inRange(random, domain.minimumOrderId(), domain.maximumOrderId()),
        invalid(random, domain.invalidOneIn())
            ? domain.invalidSide()
            : domain.validSides().get(random.nextInt(domain.validSides().size())),
        invalid(random, domain.invalidOneIn())
            ? BigInteger.ZERO
            : inRange(random, domain.minimumPriceTicks(), domain.maximumPriceTicks()),
        invalid(random, domain.invalidOneIn())
            ? BigInteger.ZERO
            : inRange(random, domain.minimumQuantityLots(), domain.maximumQuantityLots()),
        policy);
  }

  private static String weightedPolicy(
      M04GeneratorProfile.RandomDomain domain, M03SplitMix64V1 random) {
    int draw = random.nextInt(domain.totalPolicyWeight());
    for (M04GeneratorProfile.WeightedPolicy policy : domain.policies()) {
      if (draw < policy.weight()) {
        return policy.id();
      }
      draw -= policy.weight();
    }
    throw new IllegalStateException("M04 weighted policy draw escaped domain");
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
