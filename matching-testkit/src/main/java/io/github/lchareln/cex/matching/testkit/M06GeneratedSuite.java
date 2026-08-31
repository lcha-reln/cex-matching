package io.github.lchareln.cex.matching.testkit;

import io.github.lchareln.cex.matching.reference.M06MarketRuleSetArtifact;
import io.github.lchareln.cex.matching.reference.M06ReferenceCommand;
import io.github.lchareln.cex.matching.reference.M06RuleSetIdentity;
import io.github.lchareln.cex.matching.reference.M06SemanticMarketState;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

/** Deterministic SplitMix64 generator for the frozen five-lane M06 finite corpus. */
final class M06GeneratedSuite {
  static final String ALGORITHM = "splitmix64-v1";
  private static final long GOLDEN_GAMMA = 0x9e3779b97f4a7c15L;

  List<History> generate(M06Corpus.Profile profile, M06Corpus.Fixed fixed) {
    List<History> histories = new ArrayList<>(profile.histories());
    for (int index = 0; index < profile.histories(); index++) {
      M06Corpus.Lane lane = profile.lanes().get(Math.floorMod(index, profile.lanes().size()));
      long seed = mix64(profile.baseSeed() + GOLDEN_GAMMA * (index + 1L));
      SplitMix64 random = new SplitMix64(seed);
      M06ReferenceCandidate shadow = new M06ReferenceCandidate();
      List<M06ReferenceCommand> commands = new ArrayList<>(profile.commandsPerHistory());
      M06Corpus.Scenario prefix = fixed.byId().get(lane.prefixScenario());
      if (prefix == null) {
        throw new IllegalStateException("M06 lane prefix scenario is absent");
      }
      for (M06Corpus.Case item : prefix.cases()) {
        append(commands, shadow, item.command());
      }
      int nextOrder = 1;
      int nextRuleVersion = 1;
      while (commands.size() < profile.commandsPerHistory()) {
        M06SemanticMarketState state = shadow.snapshot();
        M06ReferenceCommand command =
            randomCommand(profile.domain(), random, state, index, nextOrder, nextRuleVersion);
        if (command instanceof M06ReferenceCommand.Place) {
          nextOrder++;
        }
        if (command instanceof M06ReferenceCommand.PrepareRuleSet) {
          nextRuleVersion++;
        }
        append(commands, shadow, command);
      }
      histories.add(
          new History(
              index, lane.id(), "0x" + Long.toUnsignedString(seed, 16), List.copyOf(commands)));
    }
    require(histories.size() == 160, "M06 generated history count changed");
    require(
        histories.stream().mapToInt(history -> history.commands().size()).sum() == 10_240,
        "M06 generated command count changed");
    for (String lane : M06StartCheckRunner.LANE_IDS) {
      require(
          histories.stream().filter(history -> history.lane().equals(lane)).count() == 32,
          "M06 lane population changed: " + lane);
    }
    return List.copyOf(histories);
  }

  private static M06ReferenceCommand randomCommand(
      M06Corpus.Domain domain,
      SplitMix64 random,
      M06SemanticMarketState state,
      int historyIndex,
      int nextOrder,
      int nextRuleVersion) {
    int selector = random.nextInt(100);
    if (selector < domain.placeWeight()) {
      return place(domain, random, state, historyIndex, nextOrder);
    }
    selector -= domain.placeWeight();
    if (selector < domain.cancelWeight()) {
      return cancel(random, state, historyIndex, nextOrder);
    }
    selector -= domain.cancelWeight();
    if (selector < domain.changeModeWeight()) {
      return changeMode(domain, random, state, historyIndex);
    }
    selector -= domain.changeModeWeight();
    if (selector < domain.massCancelWeight()) {
      return massCancel(domain, random, state, historyIndex);
    }
    selector -= domain.massCancelWeight();
    if (selector < domain.prepareWeight()) {
      return prepare(state, nextRuleVersion);
    }
    return activate(random, state);
  }

  private static M06ReferenceCommand place(
      M06Corpus.Domain domain,
      SplitMix64 random,
      M06SemanticMarketState state,
      int historyIndex,
      int nextOrder) {
    BigInteger orderId = BigInteger.valueOf(1_000_000L + historyIndex * 10_000L + nextOrder);
    String instrument = random.oneIn(domain.invalidFieldOneIn()) ? "ETH-USDT" : "BTC-USDT";
    String side = random.nextBoolean() ? "BUY" : "SELL";
    long price =
        domain.minimumPriceTicks()
            + random.nextLong(domain.maximumPriceTicks() - domain.minimumPriceTicks() + 1L);
    long quantity = 1L + random.nextLong(domain.maximumQuantityLots());
    String policy =
        domain.executionPolicies().get(random.nextInt(domain.executionPolicies().size()));
    if (random.nextInt(4) == 0) {
      M06RuleSetIdentity expected =
          random.oneIn(8)
              ? new M06RuleSetIdentity(
                  state.activeIdentity().version(),
                  "sha256:0000000000000000000000000000000000000000000000000000000000000000")
              : state.activeIdentity();
      return M06ReferenceCommand.Place.governed(
          expected,
          instrument,
          orderId,
          side,
          BigInteger.valueOf(price),
          BigInteger.valueOf(quantity),
          policy);
    }
    return M06ReferenceCommand.Place.legacy(
        instrument, orderId, side, BigInteger.valueOf(price), BigInteger.valueOf(quantity), policy);
  }

  private static M06ReferenceCommand cancel(
      SplitMix64 random, M06SemanticMarketState state, int historyIndex, int nextOrder) {
    List<BigInteger> active = activeOrderIds(state);
    BigInteger orderId;
    if (!active.isEmpty() && random.nextBoolean()) {
      orderId = active.get(random.nextInt(active.size()));
    } else {
      orderId = BigInteger.valueOf(9_000_000L + historyIndex * 10_000L + nextOrder);
    }
    return new M06ReferenceCommand.Cancel("BTC-USDT", orderId);
  }

  private static M06ReferenceCommand changeMode(
      M06Corpus.Domain domain, SplitMix64 random, M06SemanticMarketState state, int historyIndex) {
    BigInteger expectedSequence = state.nextApplicationSequence();
    if (random.oneIn(domain.staleApplicationOneIn())) {
      expectedSequence =
          expectedSequence.add(random.nextBoolean() ? BigInteger.ONE : BigInteger.TWO);
    }
    String expectedMode = state.marketMode();
    if (random.oneIn(domain.staleModeOneIn())) {
      expectedMode = differentMode(expectedMode, random);
    }
    String target = domain.targetModes().get(random.nextInt(domain.targetModes().size()));
    return new M06ReferenceCommand.ChangeMarketMode(
        expectedSequence, expectedMode, target, "generated-mode-" + historyIndex);
  }

  private static M06ReferenceCommand massCancel(
      M06Corpus.Domain domain, SplitMix64 random, M06SemanticMarketState state, int historyIndex) {
    BigInteger expectedSequence = state.nextApplicationSequence();
    if (random.oneIn(domain.staleApplicationOneIn())) {
      expectedSequence = expectedSequence.add(BigInteger.ONE);
    }
    String expectedMode = state.marketMode();
    if (random.oneIn(domain.staleModeOneIn())) {
      expectedMode = differentMode(expectedMode, random);
    }
    if ("HALTED".equals(state.marketMode())
        && random.oneIn(domain.massCancelOutsideHaltedOneIn())) {
      expectedMode = random.nextBoolean() ? "OPEN" : "CANCEL_ONLY";
    }
    return new M06ReferenceCommand.MassCancel(
        expectedSequence, expectedMode, "generated-mass-" + historyIndex);
  }

  private static M06ReferenceCommand prepare(M06SemanticMarketState state, int nextRuleVersion) {
    BigInteger minimum = state.activeIdentity().version().add(BigInteger.ONE);
    BigInteger version = minimum.max(BigInteger.valueOf(nextRuleVersion));
    long lower = 85L + Math.floorMod(version.longValue(), 6L);
    long upper = 115L - Math.floorMod(version.longValue(), 6L);
    return new M06ReferenceCommand.PrepareRuleSet(
        state.activeIdentity(),
        M06MarketRuleSetArtifact.canonical(
            version, BigInteger.valueOf(lower), BigInteger.valueOf(upper)));
  }

  private static M06ReferenceCommand activate(SplitMix64 random, M06SemanticMarketState state) {
    M06RuleSetIdentity target =
        state
            .preparedRuleSet()
            .map(M06MarketRuleSetArtifact::identity)
            .orElse(state.activeIdentity());
    BigInteger expectedSequence = state.nextApplicationSequence();
    if (random.oneIn(16)) {
      expectedSequence = expectedSequence.add(BigInteger.ONE);
    }
    return new M06ReferenceCommand.ActivateRuleSet(
        expectedSequence, state.activeIdentity(), target);
  }

  private static List<BigInteger> activeOrderIds(M06SemanticMarketState state) {
    List<BigInteger> values = new ArrayList<>();
    state
        .book()
        .bids()
        .forEach(level -> level.orders().forEach(order -> values.add(order.orderId())));
    state
        .book()
        .asks()
        .forEach(level -> level.orders().forEach(order -> values.add(order.orderId())));
    return List.copyOf(values);
  }

  private static String differentMode(String mode, SplitMix64 random) {
    List<String> others =
        List.of("OPEN", "CANCEL_ONLY", "HALTED").stream()
            .filter(value -> !value.equals(mode))
            .toList();
    return others.get(random.nextInt(others.size()));
  }

  private static void append(
      List<M06ReferenceCommand> commands,
      M06ReferenceCandidate shadow,
      M06ReferenceCommand command) {
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

  record History(int index, String lane, String seedHex, List<M06ReferenceCommand> commands) {
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
