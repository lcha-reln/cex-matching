package io.github.lchareln.cex.matching.testkit;

import io.github.lchareln.cex.matching.reference.M06MarketRuleSetArtifact;
import io.github.lchareln.cex.matching.reference.M06RuleSetIdentity;
import io.github.lchareln.cex.matching.reference.M07ReferenceCommand;
import io.github.lchareln.cex.matching.reference.M07SemanticBook;
import io.github.lchareln.cex.matching.reference.M07SemanticMarketState;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

/** Repository-owned SplitMix64-v1 generator for the frozen five-lane M07 finite corpus. */
final class M07GeneratedSuite {
  static final String ALGORITHM = "splitmix64-v1";
  private static final long GOLDEN_GAMMA = 0x9e3779b97f4a7c15L;

  List<History> generate(M07Corpus.Profile profile, M07Corpus.Fixed fixed) {
    List<History> histories = new ArrayList<>(profile.histories());
    for (int index = 0; index < profile.histories(); index++) {
      M07Corpus.Lane lane = profile.lanes().get(Math.floorMod(index, profile.lanes().size()));
      long seed = mix64(profile.baseSeed() + GOLDEN_GAMMA * (index + 1L));
      SplitMix64 random = new SplitMix64(seed);
      M07ReferenceCandidate shadow = new M07ReferenceCandidate();
      List<M07ReferenceCommand> commands = new ArrayList<>(profile.commandsPerHistory());
      M07Corpus.Scenario prefix = fixed.byId().get(lane.prefixScenario());
      require(prefix != null, "M07 lane prefix scenario is absent");
      for (M07Corpus.Case item : prefix.cases()) {
        append(commands, shadow, item.command());
      }
      int nextOrder = 1;
      int nextRuleVersion = 1;
      while (commands.size() < profile.commandsPerHistory()) {
        M07SemanticMarketState state = shadow.snapshot();
        M07ReferenceCommand command =
            randomCommand(profile.domain(), random, state, index, nextOrder, nextRuleVersion);
        if (command instanceof M07ReferenceCommand.Place) {
          nextOrder++;
        }
        if (command instanceof M07ReferenceCommand.PrepareRuleSet) {
          nextRuleVersion++;
        }
        append(commands, shadow, command);
      }
      histories.add(
          new History(
              index, lane.id(), "0x" + Long.toUnsignedString(seed, 16), List.copyOf(commands)));
    }
    require(histories.size() == 160, "M07 generated history count changed");
    require(
        histories.stream().mapToInt(history -> history.commands().size()).sum() == 10_240,
        "M07 generated command count changed");
    for (String lane : M07StartCheckRunner.LANE_IDS) {
      require(
          histories.stream().filter(history -> history.lane().equals(lane)).count() == 32,
          "M07 lane population changed: " + lane);
    }
    return List.copyOf(histories);
  }

  private static M07ReferenceCommand randomCommand(
      M07Corpus.Domain domain,
      SplitMix64 random,
      M07SemanticMarketState state,
      int historyIndex,
      int nextOrder,
      int nextRuleVersion) {
    int selector = random.nextInt(100);
    if (selector < domain.legacyPlaceWeight()) {
      return legacyPlace(domain, random, historyIndex, nextOrder);
    }
    selector -= domain.legacyPlaceWeight();
    if (selector < domain.stpPlaceWeight()) {
      return stpPlace(domain, random, state, historyIndex, nextOrder, false);
    }
    selector -= domain.stpPlaceWeight();
    if (selector < domain.governedStpPlaceWeight()) {
      return stpPlace(domain, random, state, historyIndex, nextOrder, true);
    }
    selector -= domain.governedStpPlaceWeight();
    if (selector < domain.cancelWeight()) {
      return cancel(random, state, historyIndex, nextOrder);
    }
    selector -= domain.cancelWeight();
    if (selector < domain.prepareWeight()) {
      return prepare(state, nextRuleVersion);
    }
    selector -= domain.prepareWeight();
    if (selector < domain.activateWeight()) {
      return activate(state);
    }
    selector -= domain.activateWeight();
    if (selector < domain.changeModeWeight()) {
      return changeMode(domain, random, state, historyIndex);
    }
    return massCancel(state, historyIndex);
  }

  private static M07ReferenceCommand.Place legacyPlace(
      M07Corpus.Domain domain, SplitMix64 random, int historyIndex, int nextOrder) {
    return M07ReferenceCommand.Place.legacy(
        "BTC-USDT",
        orderId(historyIndex, nextOrder),
        random.nextBoolean() ? "BUY" : "SELL",
        price(domain, random),
        quantity(domain, random),
        policy(domain, random));
  }

  private static M07ReferenceCommand.Place stpPlace(
      M07Corpus.Domain domain,
      SplitMix64 random,
      M07SemanticMarketState state,
      int historyIndex,
      int nextOrder,
      boolean governed) {
    BigInteger group = group(domain, random, state);
    String stpPolicy = activeStpPolicy(domain, random);
    if (random.oneIn(domain.invalidGroupOneIn())) {
      group = BigInteger.valueOf(-1);
    } else if (random.oneIn(domain.invalidPolicyOneIn())) {
      stpPolicy = "UNKNOWN";
    } else if (random.oneIn(domain.invalidPairOneIn())) {
      if (random.nextBoolean()) {
        group = BigInteger.ZERO;
      } else {
        stpPolicy = "NONE";
      }
    }
    String side = random.nextBoolean() ? "BUY" : "SELL";
    BigInteger id = orderId(historyIndex, nextOrder);
    BigInteger price = price(domain, random);
    BigInteger quantity = quantity(domain, random);
    String executionPolicy = policy(domain, random);
    if (governed) {
      return M07ReferenceCommand.Place.governedStp(
          state.activeIdentity(),
          "BTC-USDT",
          id,
          side,
          price,
          quantity,
          executionPolicy,
          group,
          stpPolicy);
    }
    return M07ReferenceCommand.Place.stp(
        "BTC-USDT", id, side, price, quantity, executionPolicy, group, stpPolicy);
  }

  private static BigInteger group(
      M07Corpus.Domain domain, SplitMix64 random, M07SemanticMarketState state) {
    List<BigInteger> active = activeGroups(state.book());
    if (!active.isEmpty() && random.oneIn(domain.sameGroupOneIn())) {
      return active.get(random.nextInt(active.size()));
    }
    long width = domain.maximumParticipantGroupId() - domain.minimumParticipantGroupId() + 1L;
    return BigInteger.valueOf(domain.minimumParticipantGroupId() + random.nextLong(width));
  }

  private static M07ReferenceCommand.Cancel cancel(
      SplitMix64 random, M07SemanticMarketState state, int historyIndex, int nextOrder) {
    List<BigInteger> active = activeOrderIds(state.book());
    BigInteger id =
        !active.isEmpty() && random.nextBoolean()
            ? active.get(random.nextInt(active.size()))
            : BigInteger.valueOf(9_000_000L + historyIndex * 10_000L + nextOrder);
    return new M07ReferenceCommand.Cancel("BTC-USDT", id);
  }

  private static M07ReferenceCommand.PrepareRuleSet prepare(
      M07SemanticMarketState state, int nextRuleVersion) {
    BigInteger version =
        state
            .activeIdentity()
            .version()
            .add(BigInteger.ONE)
            .max(BigInteger.valueOf(nextRuleVersion));
    return new M07ReferenceCommand.PrepareRuleSet(
        state.activeIdentity(),
        M06MarketRuleSetArtifact.canonical(
            version, BigInteger.valueOf(85), BigInteger.valueOf(115)));
  }

  private static M07ReferenceCommand.ActivateRuleSet activate(M07SemanticMarketState state) {
    M06RuleSetIdentity target =
        state
            .preparedRuleSet()
            .map(M06MarketRuleSetArtifact::identity)
            .orElse(state.activeIdentity());
    return new M07ReferenceCommand.ActivateRuleSet(
        state.nextApplicationSequence(), state.activeIdentity(), target);
  }

  private static M07ReferenceCommand.ChangeMarketMode changeMode(
      M07Corpus.Domain domain, SplitMix64 random, M07SemanticMarketState state, int historyIndex) {
    String target = domain.marketModes().get(random.nextInt(domain.marketModes().size()));
    return new M07ReferenceCommand.ChangeMarketMode(
        state.nextApplicationSequence(),
        state.marketMode(),
        target,
        "generated-mode-" + historyIndex);
  }

  private static M07ReferenceCommand.MassCancel massCancel(
      M07SemanticMarketState state, int historyIndex) {
    return new M07ReferenceCommand.MassCancel(
        state.nextApplicationSequence(), state.marketMode(), "generated-mass-" + historyIndex);
  }

  private static BigInteger orderId(int historyIndex, int nextOrder) {
    return BigInteger.valueOf(1_000_000L + historyIndex * 10_000L + nextOrder);
  }

  private static BigInteger price(M07Corpus.Domain domain, SplitMix64 random) {
    return BigInteger.valueOf(
        domain.minimumPriceTicks()
            + random.nextLong(domain.maximumPriceTicks() - domain.minimumPriceTicks() + 1L));
  }

  private static BigInteger quantity(M07Corpus.Domain domain, SplitMix64 random) {
    return BigInteger.valueOf(1L + random.nextLong(domain.maximumQuantityLots()));
  }

  private static String policy(M07Corpus.Domain domain, SplitMix64 random) {
    return domain.executionPolicies().get(random.nextInt(domain.executionPolicies().size()));
  }

  private static String activeStpPolicy(M07Corpus.Domain domain, SplitMix64 random) {
    List<String> active =
        domain.stpPolicies().stream().filter(value -> !"NONE".equals(value)).toList();
    return active.get(random.nextInt(active.size()));
  }

  private static List<BigInteger> activeOrderIds(M07SemanticBook book) {
    return java.util.stream.Stream.concat(book.bids().stream(), book.asks().stream())
        .flatMap(level -> level.orders().stream())
        .map(M07SemanticBook.RestingOrder::orderId)
        .toList();
  }

  private static List<BigInteger> activeGroups(M07SemanticBook book) {
    return java.util.stream.Stream.concat(book.bids().stream(), book.asks().stream())
        .flatMap(level -> level.orders().stream())
        .map(M07SemanticBook.RestingOrder::participantGroupId)
        .filter(group -> group.signum() > 0)
        .distinct()
        .toList();
  }

  private static void append(
      List<M07ReferenceCommand> commands,
      M07ReferenceCandidate shadow,
      M07ReferenceCommand command) {
    commands.add(command);
    shadow.apply(command);
  }

  private static long mix64(long value) {
    long mixed = value;
    mixed = (mixed ^ (mixed >>> 30)) * 0xbf58476d1ce4e5b9L;
    mixed = (mixed ^ (mixed >>> 27)) * 0x94d049bb133111ebL;
    return mixed ^ (mixed >>> 31);
  }

  private static void require(boolean condition, String message) {
    if (!condition) {
      throw new IllegalStateException(message);
    }
  }

  record History(int index, String lane, String seedHex, List<M07ReferenceCommand> commands) {
    History {
      commands = List.copyOf(commands);
    }
  }

  private static final class SplitMix64 {
    private long state;

    private SplitMix64(long seed) {
      state = seed;
    }

    private long nextLong() {
      state += GOLDEN_GAMMA;
      return mix64(state);
    }

    private long nextLong(long bound) {
      if (bound <= 0) {
        throw new IllegalArgumentException("bound must be positive");
      }
      return Long.remainderUnsigned(nextLong(), bound);
    }

    private int nextInt(int bound) {
      return Math.toIntExact(nextLong(bound));
    }

    private boolean nextBoolean() {
      return (nextLong() & 1L) == 0L;
    }

    private boolean oneIn(int denominator) {
      return nextInt(denominator) == 0;
    }
  }
}
