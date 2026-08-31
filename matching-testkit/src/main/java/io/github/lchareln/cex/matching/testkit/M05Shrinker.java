package io.github.lchareln.cex.matching.testkit;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Deterministic, fingerprint-preserving M05 history reducer with deletion minimality. */
final class M05Shrinker {
  static final int MAX_TRIALS = 50_000;
  private final M05PropertyJudge judge = new M05PropertyJudge();

  Result shrink(
      String historyId,
      String seed,
      List<M05Command> original,
      M05Candidate.Factory factory,
      Fingerprint fingerprint) {
    List<M05Command> initial = List.copyOf(original);
    if (initial.isEmpty()) {
      throw new IllegalArgumentException("cannot shrink empty M05 history");
    }
    Trials trials = new Trials(historyId, seed, factory, fingerprint);
    M05PropertyJudge.Observation initialObservation = trials.evaluate(initial);
    requireMatch(initialObservation, fingerprint, "original M05 history");
    State state = new State(initial, initialObservation);
    int failingPrefix = initialObservation.failure().commandIndex() + 1;
    if (failingPrefix < state.commands.size()) {
      accept(state, initial.subList(0, failingPrefix), trials);
    }
    ddmin(state, trials);
    while (removeOne(state, trials)) {
      // Repeat from the first index because one deletion can make an earlier deletion possible.
    }
    boolean oneDeletionMinimal = verifyOneDeletionMinimal(state.commands, trials);
    if (!oneDeletionMinimal) {
      throw new IllegalStateException("M05 shrink result is not one-deletion-minimal");
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
        List<M05Command> candidate = without(state.commands, start, Math.min(size, start + chunk));
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
      List<M05Command> candidate = without(state.commands, index, index + 1);
      if (!candidate.isEmpty() && accept(state, candidate, trials)) {
        return true;
      }
    }
    return false;
  }

  private static boolean verifyOneDeletionMinimal(List<M05Command> commands, Trials trials) {
    for (int index = 0; index < commands.size(); index++) {
      List<M05Command> candidate = without(commands, index, index + 1);
      if (!candidate.isEmpty() && trials.matches(candidate)) {
        return false;
      }
    }
    return true;
  }

  private static boolean accept(State state, List<M05Command> candidate, Trials trials) {
    M05PropertyJudge.Observation observation = trials.evaluate(candidate);
    if (!trials.fingerprint.matches(observation)) {
      return false;
    }
    state.commands = List.copyOf(candidate);
    state.observation = observation;
    return true;
  }

  private static List<M05Command> without(List<M05Command> source, int start, int end) {
    List<M05Command> result = new ArrayList<>(source.size() - (end - start));
    result.addAll(source.subList(0, start));
    result.addAll(source.subList(end, source.size()));
    return List.copyOf(result);
  }

  private static void requireMatch(
      M05PropertyJudge.Observation observation, Fingerprint fingerprint, String subject) {
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

    boolean matches(M05PropertyJudge.Observation observation) {
      return M05PropertyJudge.STUDENT_FAILURE.equals(observation.classification())
          && observation.failure() != null
          && propertyId.equals(observation.failure().propertyId())
          && divergenceKind.equals(observation.failure().divergenceKind());
    }
  }

  record Result(
      List<M05Command> commands,
      M05PropertyJudge.Observation observation,
      int trials,
      boolean oneMinimal) {
    Result {
      commands = List.copyOf(commands);
      if (commands.isEmpty() || trials < 1 || trials > MAX_TRIALS || !oneMinimal) {
        throw new IllegalArgumentException("invalid M05 shrink result");
      }
    }
  }

  private static final class State {
    private List<M05Command> commands;
    private M05PropertyJudge.Observation observation;

    private State(List<M05Command> commands, M05PropertyJudge.Observation observation) {
      this.commands = List.copyOf(commands);
      this.observation = observation;
    }
  }

  private final class Trials {
    private final String historyId;
    private final String seed;
    private final M05Candidate.Factory factory;
    private final Fingerprint fingerprint;
    private int count;

    private Trials(
        String historyId, String seed, M05Candidate.Factory factory, Fingerprint fingerprint) {
      this.historyId = historyId;
      this.seed = seed;
      this.factory = factory;
      this.fingerprint = fingerprint;
    }

    private M05PropertyJudge.Observation evaluate(List<M05Command> commands) {
      if (count >= MAX_TRIALS) {
        throw new IllegalStateException("M05 shrink exceeded trial limit");
      }
      count++;
      M05PropertyJudge.Observation observation = judge.judge(historyId, seed, commands, factory);
      if (M05PropertyJudge.SYSTEM_ERROR.equals(observation.classification())) {
        throw new IllegalStateException(
            "M05 shrink trial failed closed with SYSTEM_ERROR: " + observation.message());
      }
      return observation;
    }

    private boolean matches(List<M05Command> commands) {
      return fingerprint.matches(evaluate(commands));
    }
  }
}
