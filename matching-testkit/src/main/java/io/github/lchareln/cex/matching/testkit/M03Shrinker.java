package io.github.lchareln.cex.matching.testkit;

import io.github.lchareln.cex.matching.reference.ReferenceCommand;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Deterministic, fingerprint-preserving reducer for generated M03 command histories. */
final class M03Shrinker {
  static final int MAX_TRIALS = 50_000;

  private final M03PropertyJudge judge = new M03PropertyJudge();

  Result shrink(
      List<ReferenceCommand> original,
      M03Candidate.Factory candidateFactory,
      Fingerprint fingerprint) {
    return shrink("shrink", "0000000000000000", original, candidateFactory, fingerprint);
  }

  Result shrink(
      String historyId,
      String seed,
      List<ReferenceCommand> original,
      M03Candidate.Factory candidateFactory,
      Fingerprint fingerprint) {
    Objects.requireNonNull(historyId, "historyId");
    Objects.requireNonNull(seed, "seed");
    List<ReferenceCommand> initial = List.copyOf(original);
    Objects.requireNonNull(candidateFactory, "candidateFactory");
    Objects.requireNonNull(fingerprint, "fingerprint");
    if (initial.isEmpty()) {
      throw new IllegalArgumentException("cannot shrink an empty command history");
    }

    Trials trials = new Trials(historyId, seed, candidateFactory, fingerprint);
    M03PropertyJudge.Observation initialObservation = trials.evaluate(initial);
    requireMatch(initialObservation, fingerprint, "original history");

    State state = new State(initial, initialObservation);
    int failingPrefixLength = initialObservation.failure().commandIndex() + 1;
    if (failingPrefixLength < state.commands.size()) {
      acceptIfMatching(state, initial.subList(0, failingPrefixLength), trials);
    }

    ddmin(state, trials);
    boolean changed;
    do {
      changed = removeSingleCommand(state, trials);
      if (!changed) {
        changed = simplifyOneScalar(state, trials);
      }
    } while (changed);

    boolean oneMinimal = verifyOneMinimal(state.commands, trials);
    if (!oneMinimal) {
      throw new IllegalStateException("M03 shrink result is not 1-minimal");
    }
    return new Result(state.commands, state.observation, trials.count, true);
  }

  private static void ddmin(State state, Trials trials) {
    int partitions = 2;
    while (state.commands.size() >= 2) {
      int size = state.commands.size();
      int chunkSize = ceilDiv(size, partitions);
      boolean reduced = false;
      for (int start = 0; start < size; start += chunkSize) {
        int end = Math.min(size, start + chunkSize);
        List<ReferenceCommand> candidate = withoutRange(state.commands, start, end);
        if (candidate.isEmpty()) {
          continue;
        }
        if (acceptIfMatching(state, candidate, trials)) {
          partitions = Math.max(2, partitions - 1);
          reduced = true;
          break;
        }
      }
      if (!reduced) {
        if (partitions >= size) {
          return;
        }
        partitions = Math.min(size, partitions * 2);
      }
    }
  }

  private static boolean removeSingleCommand(State state, Trials trials) {
    for (int index = 0; index < state.commands.size(); index++) {
      List<ReferenceCommand> candidate = withoutRange(state.commands, index, index + 1);
      if (!candidate.isEmpty() && acceptIfMatching(state, candidate, trials)) {
        return true;
      }
    }
    return false;
  }

  private static boolean simplifyOneScalar(State state, Trials trials) {
    for (int index = 0; index < state.commands.size(); index++) {
      for (ReferenceCommand simplified : scalarSimplifications(state.commands.get(index))) {
        List<ReferenceCommand> candidate = new ArrayList<>(state.commands);
        candidate.set(index, simplified);
        if (acceptIfMatching(state, candidate, trials)) {
          return true;
        }
      }
    }
    return false;
  }

  private static boolean verifyOneMinimal(List<ReferenceCommand> commands, Trials trials) {
    for (int index = 0; index < commands.size(); index++) {
      List<ReferenceCommand> candidate = withoutRange(commands, index, index + 1);
      if (!candidate.isEmpty() && trials.matches(candidate)) {
        return false;
      }
    }
    for (int index = 0; index < commands.size(); index++) {
      for (ReferenceCommand simplified : scalarSimplifications(commands.get(index))) {
        List<ReferenceCommand> candidate = new ArrayList<>(commands);
        candidate.set(index, simplified);
        if (trials.matches(candidate)) {
          return false;
        }
      }
    }
    return true;
  }

  private static boolean acceptIfMatching(
      State state, List<ReferenceCommand> candidate, Trials trials) {
    M03PropertyJudge.Observation observation = trials.evaluate(candidate);
    if (!trials.fingerprint.matches(observation)) {
      return false;
    }
    state.commands = List.copyOf(candidate);
    state.observation = observation;
    return true;
  }

  private static List<ReferenceCommand> scalarSimplifications(ReferenceCommand command) {
    List<ReferenceCommand> result = new ArrayList<>();
    switch (command) {
      case ReferenceCommand.Place place -> {
        for (String value : simplerString(place.instrumentId())) {
          result.add(
              new ReferenceCommand.Place(
                  value, place.orderId(), place.side(), place.priceTicks(), place.quantityLots()));
        }
        for (BigInteger value : simplerInteger(place.orderId())) {
          result.add(
              new ReferenceCommand.Place(
                  place.instrumentId(),
                  value,
                  place.side(),
                  place.priceTicks(),
                  place.quantityLots()));
        }
        for (String value : simplerString(place.side())) {
          result.add(
              new ReferenceCommand.Place(
                  place.instrumentId(),
                  place.orderId(),
                  value,
                  place.priceTicks(),
                  place.quantityLots()));
        }
        for (BigInteger value : simplerInteger(place.priceTicks())) {
          result.add(
              new ReferenceCommand.Place(
                  place.instrumentId(),
                  place.orderId(),
                  place.side(),
                  value,
                  place.quantityLots()));
        }
        for (BigInteger value : simplerInteger(place.quantityLots())) {
          result.add(
              new ReferenceCommand.Place(
                  place.instrumentId(), place.orderId(), place.side(), place.priceTicks(), value));
        }
      }
      case ReferenceCommand.Cancel cancel -> {
        for (String value : simplerString(cancel.instrumentId())) {
          result.add(new ReferenceCommand.Cancel(value, cancel.orderId()));
        }
        for (BigInteger value : simplerInteger(cancel.orderId())) {
          result.add(new ReferenceCommand.Cancel(cancel.instrumentId(), value));
        }
      }
    }
    return List.copyOf(result);
  }

  private static List<String> simplerString(String value) {
    return value.isEmpty() ? List.of() : List.of("");
  }

  private static List<BigInteger> simplerInteger(BigInteger value) {
    if (value.signum() == 0) {
      return List.of();
    }
    Set<BigInteger> candidates = new LinkedHashSet<>();
    candidates.add(BigInteger.ZERO);
    if (value.abs().compareTo(BigInteger.ONE) > 0) {
      candidates.add(BigInteger.valueOf(value.signum()));
      BigInteger half = value.divide(BigInteger.TWO);
      if (half.abs().compareTo(BigInteger.ONE) > 0) {
        candidates.add(half);
      }
    }
    candidates.remove(value);
    return List.copyOf(candidates);
  }

  private static List<ReferenceCommand> withoutRange(
      List<ReferenceCommand> source, int start, int end) {
    List<ReferenceCommand> result = new ArrayList<>(source.size() - (end - start));
    result.addAll(source.subList(0, start));
    result.addAll(source.subList(end, source.size()));
    return List.copyOf(result);
  }

  private static int ceilDiv(int value, int divisor) {
    return (value + divisor - 1) / divisor;
  }

  private static void requireMatch(
      M03PropertyJudge.Observation observation, Fingerprint fingerprint, String subject) {
    if (!fingerprint.matches(observation)) {
      throw new IllegalArgumentException(
          subject
              + " does not fail as "
              + M03PropertyJudge.STUDENT_FAILURE
              + " with fingerprint "
              + fingerprint.value());
    }
  }

  record Fingerprint(String propertyId, String divergenceKind) {
    Fingerprint {
      Objects.requireNonNull(propertyId, "propertyId");
      Objects.requireNonNull(divergenceKind, "divergenceKind");
      if (propertyId.isBlank() || divergenceKind.isBlank()) {
        throw new IllegalArgumentException("fingerprint components must not be blank");
      }
    }

    static Fingerprint from(M03PropertyJudge.Failure failure) {
      Objects.requireNonNull(failure, "failure");
      return new Fingerprint(failure.propertyId(), failure.divergenceKind());
    }

    String value() {
      return propertyId + "/" + divergenceKind;
    }

    boolean matches(M03PropertyJudge.Observation observation) {
      if (!M03PropertyJudge.STUDENT_FAILURE.equals(observation.classification())
          || observation.failure() == null) {
        return false;
      }
      return propertyId.equals(observation.failure().propertyId())
          && divergenceKind.equals(observation.failure().divergenceKind());
    }
  }

  record Result(
      List<ReferenceCommand> commands,
      M03PropertyJudge.Observation observation,
      int trials,
      boolean oneMinimal) {
    Result {
      commands = List.copyOf(commands);
      Objects.requireNonNull(observation, "observation");
      if (commands.isEmpty()) {
        throw new IllegalArgumentException("minimized commands must not be empty");
      }
      if (trials < 1 || trials > MAX_TRIALS) {
        throw new IllegalArgumentException("shrink trials are outside the frozen limit");
      }
      if (!oneMinimal) {
        throw new IllegalArgumentException("M03 shrink result must be 1-minimal");
      }
    }
  }

  private static final class State {
    private List<ReferenceCommand> commands;
    private M03PropertyJudge.Observation observation;

    private State(List<ReferenceCommand> commands, M03PropertyJudge.Observation observation) {
      this.commands = List.copyOf(commands);
      this.observation = observation;
    }
  }

  private final class Trials {
    private final String historyId;
    private final String seed;
    private final M03Candidate.Factory candidateFactory;
    private final Fingerprint fingerprint;
    private int count;

    private Trials(
        String historyId,
        String seed,
        M03Candidate.Factory candidateFactory,
        Fingerprint fingerprint) {
      this.historyId = historyId;
      this.seed = seed;
      this.candidateFactory = candidateFactory;
      this.fingerprint = fingerprint;
    }

    private M03PropertyJudge.Observation evaluate(List<ReferenceCommand> commands) {
      if (count >= MAX_TRIALS) {
        throw new IllegalStateException("M03 shrink exceeded " + MAX_TRIALS + " trials");
      }
      count++;
      M03PropertyJudge.Observation observation =
          judge.judge(historyId, seed, List.copyOf(commands), candidateFactory);
      if (M03PropertyJudge.SYSTEM_ERROR.equals(observation.classification())) {
        throw new IllegalStateException(
            "M03 shrink trial "
                + count
                + " failed closed with SYSTEM_ERROR: "
                + observation.message());
      }
      return observation;
    }

    private boolean matches(List<ReferenceCommand> commands) {
      return fingerprint.matches(evaluate(commands));
    }
  }
}
