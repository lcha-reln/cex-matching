package io.github.lchareln.cex.matching.testkit;

import io.github.lchareln.cex.matching.reference.ReferenceCommand;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Deterministic fingerprint-preserving M04 history reducer. */
final class M04Shrinker {
  static final int MAX_TRIALS = 50_000;
  private final M04PropertyJudge judge = new M04PropertyJudge();

  Result shrink(
      String historyId,
      String seed,
      List<ReferenceCommand> original,
      M04Candidate.Factory factory,
      Fingerprint fingerprint) {
    List<ReferenceCommand> initial = List.copyOf(original);
    if (initial.isEmpty()) {
      throw new IllegalArgumentException("cannot shrink empty M04 history");
    }
    Trials trials = new Trials(historyId, seed, factory, fingerprint);
    M04PropertyJudge.Observation initialObservation = trials.evaluate(initial);
    requireMatch(initialObservation, fingerprint, "original M04 history");
    State state = new State(initial, initialObservation);
    int failingPrefix = initialObservation.failure().commandIndex() + 1;
    if (failingPrefix < state.commands.size()) {
      accept(state, initial.subList(0, failingPrefix), trials);
    }
    ddmin(state, trials);
    boolean changed;
    do {
      changed = removeOne(state, trials);
      if (!changed) {
        changed = simplifyOne(state, trials);
      }
    } while (changed);
    boolean oneMinimal = verifyOneMinimal(state.commands, trials);
    if (!oneMinimal) {
      throw new IllegalStateException("M04 shrink result is not 1-minimal");
    }
    return new Result(state.commands, state.observation, trials.count, true);
  }

  private static void ddmin(State state, Trials trials) {
    int partitions = 2;
    while (state.commands.size() >= 2) {
      int size = state.commands.size();
      int chunk = (size + partitions - 1) / partitions;
      boolean reduced = false;
      for (int start = 0; start < size; start += chunk) {
        List<ReferenceCommand> candidate =
            without(state.commands, start, Math.min(size, start + chunk));
        if (!candidate.isEmpty() && accept(state, candidate, trials)) {
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

  private static boolean removeOne(State state, Trials trials) {
    for (int index = 0; index < state.commands.size(); index++) {
      List<ReferenceCommand> candidate = without(state.commands, index, index + 1);
      if (!candidate.isEmpty() && accept(state, candidate, trials)) {
        return true;
      }
    }
    return false;
  }

  private static boolean simplifyOne(State state, Trials trials) {
    for (int index = 0; index < state.commands.size(); index++) {
      for (ReferenceCommand simplified : scalarSimplifications(state.commands.get(index))) {
        List<ReferenceCommand> candidate = new ArrayList<>(state.commands);
        candidate.set(index, simplified);
        if (accept(state, candidate, trials)) {
          return true;
        }
      }
    }
    return false;
  }

  private static boolean verifyOneMinimal(List<ReferenceCommand> commands, Trials trials) {
    for (int index = 0; index < commands.size(); index++) {
      List<ReferenceCommand> candidate = without(commands, index, index + 1);
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

  private static boolean accept(State state, List<ReferenceCommand> candidate, Trials trials) {
    M04PropertyJudge.Observation observation = trials.evaluate(candidate);
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
              place(
                  place,
                  value,
                  place.orderId(),
                  place.side(),
                  place.priceTicks(),
                  place.quantityLots(),
                  place.executionPolicy()));
        }
        for (BigInteger value : simplerInteger(place.orderId())) {
          result.add(
              place(
                  place,
                  place.instrumentId(),
                  value,
                  place.side(),
                  place.priceTicks(),
                  place.quantityLots(),
                  place.executionPolicy()));
        }
        for (String value : simplerString(place.side())) {
          result.add(
              place(
                  place,
                  place.instrumentId(),
                  place.orderId(),
                  value,
                  place.priceTicks(),
                  place.quantityLots(),
                  place.executionPolicy()));
        }
        for (BigInteger value : simplerInteger(place.priceTicks())) {
          result.add(
              place(
                  place,
                  place.instrumentId(),
                  place.orderId(),
                  place.side(),
                  value,
                  place.quantityLots(),
                  place.executionPolicy()));
        }
        for (BigInteger value : simplerInteger(place.quantityLots())) {
          result.add(
              place(
                  place,
                  place.instrumentId(),
                  place.orderId(),
                  place.side(),
                  place.priceTicks(),
                  value,
                  place.executionPolicy()));
        }
        for (String value : simplerString(place.executionPolicy())) {
          result.add(
              place(
                  place,
                  place.instrumentId(),
                  place.orderId(),
                  place.side(),
                  place.priceTicks(),
                  place.quantityLots(),
                  value));
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

  private static ReferenceCommand.Place place(
      ReferenceCommand.Place ignored,
      String instrument,
      BigInteger orderId,
      String side,
      BigInteger price,
      BigInteger quantity,
      String policy) {
    return new ReferenceCommand.Place(instrument, orderId, side, price, quantity, policy);
  }

  private static List<String> simplerString(String value) {
    return value.isEmpty() ? List.of() : List.of("");
  }

  private static List<BigInteger> simplerInteger(BigInteger value) {
    if (value.signum() == 0) {
      return List.of();
    }
    Set<BigInteger> result = new LinkedHashSet<>();
    result.add(ZERO);
    if (value.abs().compareTo(BigInteger.ONE) > 0) {
      result.add(BigInteger.valueOf(value.signum()));
      BigInteger half = value.divide(BigInteger.TWO);
      if (half.abs().compareTo(BigInteger.ONE) > 0) {
        result.add(half);
      }
    }
    result.remove(value);
    return List.copyOf(result);
  }

  private static final BigInteger ZERO = BigInteger.ZERO;

  private static List<ReferenceCommand> without(List<ReferenceCommand> source, int start, int end) {
    List<ReferenceCommand> result = new ArrayList<>(source.size() - (end - start));
    result.addAll(source.subList(0, start));
    result.addAll(source.subList(end, source.size()));
    return List.copyOf(result);
  }

  private static void requireMatch(
      M04PropertyJudge.Observation observation, Fingerprint fingerprint, String subject) {
    if (!fingerprint.matches(observation)) {
      throw new IllegalArgumentException(subject + " does not fail as " + fingerprint.value());
    }
  }

  record Fingerprint(String propertyId, String divergenceKind) {
    Fingerprint {
      Objects.requireNonNull(propertyId, "propertyId");
      Objects.requireNonNull(divergenceKind, "divergenceKind");
    }

    String value() {
      return propertyId + "/" + divergenceKind;
    }

    boolean matches(M04PropertyJudge.Observation observation) {
      return M04PropertyJudge.STUDENT_FAILURE.equals(observation.classification())
          && observation.failure() != null
          && propertyId.equals(observation.failure().propertyId())
          && divergenceKind.equals(observation.failure().divergenceKind());
    }
  }

  record Result(
      List<ReferenceCommand> commands,
      M04PropertyJudge.Observation observation,
      int trials,
      boolean oneMinimal) {
    Result {
      commands = List.copyOf(commands);
      if (commands.isEmpty() || trials < 1 || trials > MAX_TRIALS || !oneMinimal) {
        throw new IllegalArgumentException("invalid M04 shrink result");
      }
    }
  }

  private static final class State {
    private List<ReferenceCommand> commands;
    private M04PropertyJudge.Observation observation;

    private State(List<ReferenceCommand> commands, M04PropertyJudge.Observation observation) {
      this.commands = List.copyOf(commands);
      this.observation = observation;
    }
  }

  private final class Trials {
    private final String historyId;
    private final String seed;
    private final M04Candidate.Factory factory;
    private final Fingerprint fingerprint;
    private int count;

    private Trials(
        String historyId, String seed, M04Candidate.Factory factory, Fingerprint fingerprint) {
      this.historyId = historyId;
      this.seed = seed;
      this.factory = factory;
      this.fingerprint = fingerprint;
    }

    private M04PropertyJudge.Observation evaluate(List<ReferenceCommand> commands) {
      if (count >= MAX_TRIALS) {
        throw new IllegalStateException("M04 shrink exceeded trial limit");
      }
      count++;
      M04PropertyJudge.Observation observation = judge.judge(historyId, seed, commands, factory);
      if (M04PropertyJudge.SYSTEM_ERROR.equals(observation.classification())) {
        throw new IllegalStateException(
            "M04 shrink trial failed closed with SYSTEM_ERROR: " + observation.message());
      }
      return observation;
    }

    private boolean matches(List<ReferenceCommand> commands) {
      return fingerprint.matches(evaluate(commands));
    }
  }
}
