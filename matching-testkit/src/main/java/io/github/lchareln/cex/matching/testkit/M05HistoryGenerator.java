package io.github.lchareln.cex.matching.testkit;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

/** Pure SplitMix64 M05 history generator with a private input-construction control tracker. */
final class M05HistoryGenerator {
  List<M05GeneratedHistory> generate(M05GeneratorProfile profile, M05ScenarioCorpus.Corpus corpus) {
    List<M05GeneratedHistory> histories = new ArrayList<>(profile.histories());
    for (int historyIndex = 0; historyIndex < profile.histories(); historyIndex++) {
      long seed = M04HistoryGenerator.historySeed(profile.baseSeed(), historyIndex);
      M03SplitMix64V1 random = new M03SplitMix64V1(seed);
      M05GeneratorProfile.Lane lane = profile.laneForHistory(historyIndex);
      List<M05Command> commands = new ArrayList<>(profile.commandsPerHistory());
      GenerationState state = new GenerationState();
      for (M05ScenarioCorpus.Step step : corpus.scenario(lane.prefixScenario()).steps()) {
        commands.add(step.command());
        state.observe(step.command(), commands.size());
      }
      while (commands.size() < profile.commandsPerHistory()) {
        M05Command command =
            randomCommand(profile.randomDomain(), random, state, commands.size() + 1);
        commands.add(command);
        state.observe(command, commands.size());
      }
      histories.add(new M05GeneratedHistory(historyIndex, seed, lane.id(), commands));
    }
    return List.copyOf(histories);
  }

  private static M05Command randomCommand(
      M05GeneratorProfile.RandomDomain domain,
      M03SplitMix64V1 random,
      GenerationState state,
      int applicationSequence) {
    int draw = random.nextInt(100);
    if (draw < domain.placeWeight()) {
      return place(domain, random, state);
    }
    draw -= domain.placeWeight();
    if (draw < domain.cancelWeight()) {
      return cancel(domain, random);
    }
    draw -= domain.cancelWeight();
    if (draw < domain.prepareWeight()) {
      return prepare(domain, random, state);
    }
    return activate(domain, random, state, applicationSequence);
  }

  private static M05Command place(
      M05GeneratorProfile.RandomDomain domain, M03SplitMix64V1 random, GenerationState state) {
    boolean invalid = oneIn(random, domain.invalidFieldOneIn());
    boolean governed = random.nextInt(10) != 0;
    BigInteger price;
    if (oneIn(random, domain.outOfBandOneIn())) {
      price =
          random.nextInt(2) == 0
              ? state.active.lowerInclusive().subtract(BigInteger.ONE).max(BigInteger.ONE)
              : state
                  .active
                  .upperInclusive()
                  .add(BigInteger.ONE)
                  .min(BigInteger.valueOf(Long.MAX_VALUE));
    } else {
      price =
          BigInteger.valueOf(
              domain.minimumPriceTicks()
                  + random.nextInt(domain.maximumPriceTicks() - domain.minimumPriceTicks() + 1));
    }
    M05Command.Identity expected = null;
    if (governed) {
      expected =
          oneIn(random, domain.staleRuleOneIn()) ? state.staleIdentity() : state.active.identity();
    }
    return new M05Command.Place(
        governed ? "GOVERNED" : "LEGACY",
        invalid ? "ETH-USDT" : "BTC-USDT",
        invalid ? BigInteger.ZERO : BigInteger.valueOf(1 + random.nextInt(40)),
        invalid ? "HOLD" : (random.nextInt(2) == 0 ? "BUY" : "SELL"),
        invalid ? BigInteger.ZERO : price,
        invalid
            ? BigInteger.ZERO
            : BigInteger.valueOf(1 + random.nextInt(domain.maximumQuantityLots())),
        weightedPolicy(domain, random),
        expected);
  }

  private static M05Command cancel(
      M05GeneratorProfile.RandomDomain domain, M03SplitMix64V1 random) {
    boolean invalid = oneIn(random, domain.invalidFieldOneIn());
    return new M05Command.Cancel(
        invalid ? "ETH-USDT" : "BTC-USDT",
        invalid ? BigInteger.ZERO : BigInteger.valueOf(1 + random.nextInt(40)));
  }

  private static M05Command prepare(
      M05GeneratorProfile.RandomDomain domain, M03SplitMix64V1 random, GenerationState state) {
    BigInteger version = state.highestVersion().add(BigInteger.ONE);
    if (random.nextInt(8) == 0 && state.prepared != null) {
      version = state.prepared.version();
    }
    int first = domain.minimumPriceTicks() + random.nextInt(21);
    int second = first + random.nextInt(domain.maximumPriceTicks() - first + 1);
    M05Command.Artifact artifact =
        M05RuleSetCanonical.artifact(
            version, BigInteger.valueOf(first), BigInteger.valueOf(second));
    if (random.nextInt(8) == 0) {
      artifact =
          new M05Command.Artifact(
              artifact.schemaVersion(),
              artifact.instrumentId(),
              artifact.version(),
              artifact.lowerInclusive(),
              artifact.upperInclusive(),
              "sha256:0000000000000000000000000000000000000000000000000000000000000000");
    }
    M05Command.Identity expected =
        oneIn(random, domain.staleRuleOneIn()) ? state.staleIdentity() : state.active.identity();
    return new M05Command.PrepareRuleSet(expected, artifact);
  }

  private static M05Command activate(
      M05GeneratorProfile.RandomDomain domain,
      M03SplitMix64V1 random,
      GenerationState state,
      int applicationSequence) {
    M05Command.Identity target =
        state.prepared == null
            ? new M05Command.Identity(
                state.highestVersion().add(BigInteger.ONE),
                "sha256:0000000000000000000000000000000000000000000000000000000000000000")
            : state.prepared.identity();
    BigInteger expectedSequence = BigInteger.valueOf(applicationSequence);
    if (oneIn(random, domain.staleRuleOneIn())) {
      expectedSequence = expectedSequence.subtract(BigInteger.ONE).max(BigInteger.ONE);
    }
    M05Command.Identity expectedActive =
        oneIn(random, domain.staleRuleOneIn()) ? state.staleIdentity() : state.active.identity();
    return new M05Command.ActivateRuleSet(expectedSequence, expectedActive, target);
  }

  private static String weightedPolicy(
      M05GeneratorProfile.RandomDomain domain, M03SplitMix64V1 random) {
    int draw = random.nextInt(100);
    for (M05GeneratorProfile.WeightedPolicy policy : domain.policies()) {
      if (draw < policy.weight()) {
        return policy.id();
      }
      draw -= policy.weight();
    }
    throw new IllegalStateException("M05 weighted policy draw escaped domain");
  }

  private static boolean oneIn(M03SplitMix64V1 random, int ratio) {
    return random.nextInt(ratio) == 0;
  }

  private static final class GenerationState {
    private M05Command.Artifact active = M05RuleSetCanonical.BOOTSTRAP;
    private M05Command.Artifact prepared;

    private void observe(M05Command command, int applicationSequence) {
      switch (command) {
        case M05Command.PrepareRuleSet prepare -> observePrepare(prepare);
        case M05Command.ActivateRuleSet activate -> observeActivate(activate, applicationSequence);
        case M05Command.Place ignored -> {
          // Order state is deliberately not needed to construct later rule-control inputs.
        }
        case M05Command.Cancel ignored -> {
          // Order state is deliberately not needed to construct later rule-control inputs.
        }
      }
    }

    private void observePrepare(M05Command.PrepareRuleSet command) {
      M05Command.Artifact candidate = command.artifact();
      if (!command.expectedActive().equals(active.identity())
          || !M05RuleSetCanonical.matches(candidate)
          || candidate.version().compareTo(active.version()) <= 0) {
        return;
      }
      if (prepared == null
          || candidate.identity().equals(prepared.identity())
          || candidate.version().compareTo(prepared.version()) > 0) {
        prepared = candidate;
      }
    }

    private void observeActivate(M05Command.ActivateRuleSet command, int applicationSequence) {
      if (command.expectedApplicationSequence().equals(BigInteger.valueOf(applicationSequence))
          && command.expectedActive().equals(active.identity())
          && prepared != null
          && command.target().equals(prepared.identity())) {
        active = prepared;
        prepared = null;
      }
    }

    private BigInteger highestVersion() {
      return prepared == null ? active.version() : active.version().max(prepared.version());
    }

    private M05Command.Identity staleIdentity() {
      if (active.version().signum() != 0) {
        return M05RuleSetCanonical.BOOTSTRAP.identity();
      }
      return new M05Command.Identity(
          BigInteger.valueOf(99),
          "sha256:0000000000000000000000000000000000000000000000000000000000000000");
    }
  }
}
