package io.github.lchareln.cex.matching.testkit;

import io.github.lchareln.cex.matching.reference.M07ReferenceCommand;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Kills, minimizes, persists, reparses, and strictly replays all required M07 mutants. */
final class M07CounterexampleSuite {
  static final String SCHEMA_PATH = "schemas/matching.m07.counterexamples.v1.schema.json";

  Result run(Path root) {
    M07PropertyJudge judge = new M07PropertyJudge();
    List<Counterexample> values = new ArrayList<>();
    for (M07Mutants.Mutant mutant : M07Mutants.required()) {
      M07PropertyJudge.Observation original = judge.judge(mutant.seedCommands(), mutant.factory());
      require(
          M07PropertyJudge.STUDENT_FAILURE.equals(original.classification()),
          "required M07 mutant was not semantically killed: " + mutant.id());
      Shrunk shrunk = shrink(mutant, original.fingerprint(), judge);
      requireInteractionCounterexample(mutant, shrunk, judge);
      values.add(
          new Counterexample(
              mutant.id(),
              mutant.seedCommands(),
              shrunk.commands(),
              shrunk.observation(),
              shrunk.oneMinimal()));
    }
    M07PropertyJudge.Observation system =
        judge.judge(
            List.of(M07Mutants.required().getFirst().seedCommands().getLast()),
            M07Mutants.systemErrorControl());
    require(
        M07PropertyJudge.SYSTEM_ERROR.equals(system.classification()),
        "M07 SYSTEM_ERROR control did not remain distinct from semantic mutant kills");

    ObjectNode persisted = persisted(values);
    JsonSupport.validate(persisted, readString(root.resolve(SCHEMA_PATH)), false);
    byte[] persistedBytes = JsonSupport.prettyBytes(persisted);
    Replay replay = replay(root, persistedBytes);
    require(replay.passed() == values.size(), "M07 strict replay count changed");
    M07Canonical.Canonical canonical = canonical(values);
    return new Result(
        List.copyOf(values), persisted, persistedBytes, replay, canonical, system.classification());
  }

  private static Shrunk shrink(
      M07Mutants.Mutant mutant, String fingerprint, M07PropertyJudge judge) {
    List<M07ReferenceCommand> current = new ArrayList<>(mutant.seedCommands());
    boolean changed;
    do {
      changed = false;
      for (int index = 0; index < current.size(); index++) {
        List<M07ReferenceCommand> trial = without(current, index);
        M07PropertyJudge.Observation observation = judge.judge(trial, mutant.factory());
        if (sameKill(observation, fingerprint)) {
          current = new ArrayList<>(trial);
          changed = true;
          break;
        }
      }
    } while (changed);
    M07PropertyJudge.Observation finalObservation = judge.judge(current, mutant.factory());
    require(sameKill(finalObservation, fingerprint), "M07 shrink lost its target fingerprint");
    boolean oneMinimal = true;
    for (int index = 0; index < current.size(); index++) {
      if (sameKill(judge.judge(without(current, index), mutant.factory()), fingerprint)) {
        oneMinimal = false;
        break;
      }
    }
    require(oneMinimal, "M07 shrinker did not reach one-minimality");
    return new Shrunk(List.copyOf(current), finalObservation, true);
  }

  private static void requireInteractionCounterexample(
      M07Mutants.Mutant mutant, Shrunk shrunk, M07PropertyJudge judge) {
    int commands = shrunk.commands().size();
    require(commands >= 2, mutant.id() + " counterexample never reaches a maker/taker interaction");
    for (int prefixLength = 1; prefixLength < commands; prefixLength++) {
      M07PropertyJudge.Observation prefix =
          judge.judge(shrunk.commands().subList(0, prefixLength), mutant.factory());
      require(
          M07PropertyJudge.PASS.equals(prefix.classification()),
          mutant.id() + " diverges before its final interaction command");
    }
    require(
        shrunk.observation().commandIndex() == commands - 1,
        mutant.id() + " first difference is not the final interaction command");
    if ("M07-FOK-COUNTS-RAW-SELF-LIQUIDITY".equals(mutant.id())
        || "M07-CANCEL-MAKER-BEST-LEVEL-ONLY".equals(mutant.id())) {
      require(commands >= 3, mutant.id() + " lacks the state needed by its named fault");
    }
  }

  private static Replay replay(Path root, byte[] bytes) {
    JsonNode document = JsonSupport.parse(bytes);
    JsonSupport.validate(document, readString(root.resolve(SCHEMA_PATH)), false);
    Map<String, M07Mutants.Mutant> mutants = new LinkedHashMap<>();
    M07Mutants.required().forEach(mutant -> mutants.put(mutant.id(), mutant));
    List<ReplayScenario> scenarios = new ArrayList<>();
    M07PropertyJudge judge = new M07PropertyJudge();
    for (JsonNode node : document.path("counterexamples")) {
      String id = node.path("mutantId").stringValue();
      M07Mutants.Mutant mutant = mutants.get(id);
      require(mutant != null, "persisted M07 replay references an unknown mutant");
      List<M07ReferenceCommand> commands = new ArrayList<>();
      node.path("commands").forEach(command -> commands.add(M07CommandJson.read(command)));
      M07PropertyJudge.Observation observation = judge.judge(commands, mutant.factory());
      boolean passed =
          M07PropertyJudge.STUDENT_FAILURE.equals(observation.classification())
              && node.path("classification").stringValue().equals(observation.classification())
              && node.path("fingerprint").stringValue().equals(observation.fingerprint());
      scenarios.add(
          new ReplayScenario(
              id,
              passed,
              observation.classification(),
              observation.fingerprint(),
              commands.size()));
    }
    return new Replay(
        List.copyOf(scenarios), (int) scenarios.stream().filter(ReplayScenario::passed).count());
  }

  private static ObjectNode persisted(List<Counterexample> values) {
    ObjectNode root = JsonSupport.MAPPER.createObjectNode();
    root.put("schemaVersion", "matching.m07.counterexamples.v1");
    ArrayNode counterexamples = root.putArray("counterexamples");
    for (Counterexample value : values) {
      ObjectNode node = counterexamples.addObject();
      node.put("mutantId", value.mutantId());
      node.put("classification", value.observation().classification());
      node.put("fingerprint", value.observation().fingerprint());
      node.put("originalCommands", value.original().size());
      node.put("minimizedCommands", value.minimized().size());
      node.put("oneMinimal", value.oneMinimal());
      ArrayNode commands = node.putArray("commands");
      value.minimized().forEach(command -> commands.add(M07CommandJson.write(command)));
    }
    return root;
  }

  private static M07Canonical.Canonical canonical(List<Counterexample> values) {
    List<String> lines = new ArrayList<>();
    lines.add("M07X1");
    int commands = 0;
    for (Counterexample value : values) {
      for (int index = 0; index < value.minimized().size(); index++) {
        lines.add(
            "mutant="
                + value.mutantId().length()
                + ":"
                + value.mutantId()
                + "|fingerprint="
                + value.observation().fingerprint()
                + "|commandIndex="
                + index
                + "|command="
                + M07Canonical.command(value.minimized().get(index)));
        commands++;
      }
    }
    return M07Canonical.canonical(lines, commands);
  }

  private static boolean sameKill(M07PropertyJudge.Observation observation, String fingerprint) {
    return M07PropertyJudge.STUDENT_FAILURE.equals(observation.classification())
        && fingerprint.equals(observation.fingerprint());
  }

  private static List<M07ReferenceCommand> without(
      List<M07ReferenceCommand> commands, int removedIndex) {
    List<M07ReferenceCommand> result = new ArrayList<>(commands.size() - 1);
    for (int index = 0; index < commands.size(); index++) {
      if (index != removedIndex) {
        result.add(commands.get(index));
      }
    }
    return List.copyOf(result);
  }

  private static String readString(Path path) {
    try {
      return Files.readString(path);
    } catch (IOException failure) {
      throw new IllegalStateException("cannot read " + path, failure);
    }
  }

  private static void require(boolean condition, String message) {
    if (!condition) {
      throw new IllegalStateException(message);
    }
  }

  record Counterexample(
      String mutantId,
      List<M07ReferenceCommand> original,
      List<M07ReferenceCommand> minimized,
      M07PropertyJudge.Observation observation,
      boolean oneMinimal) {}

  record ReplayScenario(
      String mutantId, boolean passed, String classification, String fingerprint, int commands) {}

  record Replay(List<ReplayScenario> scenarios, int passed) {}

  record Result(
      List<Counterexample> counterexamples,
      ObjectNode persisted,
      byte[] persistedBytes,
      Replay replay,
      M07Canonical.Canonical canonical,
      String systemErrorControl) {
    Result {
      persistedBytes = persistedBytes.clone();
    }

    @Override
    public byte[] persistedBytes() {
      return persistedBytes.clone();
    }
  }

  private record Shrunk(
      List<M07ReferenceCommand> commands,
      M07PropertyJudge.Observation observation,
      boolean oneMinimal) {}
}
