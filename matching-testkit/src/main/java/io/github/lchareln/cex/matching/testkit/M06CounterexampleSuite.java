package io.github.lchareln.cex.matching.testkit;

import io.github.lchareln.cex.matching.reference.M06ReferenceCommand;
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

/** Kills, minimizes, persists, reparses, and strictly replays all required M06 mutants. */
final class M06CounterexampleSuite {
  static final String SCHEMA_PATH = "schemas/matching.m06.counterexamples.v1.schema.json";

  Result run(Path root) {
    M06PropertyJudge judge = new M06PropertyJudge();
    List<Counterexample> values = new ArrayList<>();
    for (M06Mutants.Mutant mutant : M06Mutants.required()) {
      M06PropertyJudge.Observation original = judge.judge(mutant.seedCommands(), mutant.factory());
      require(
          M06PropertyJudge.STUDENT_FAILURE.equals(original.classification()),
          "required M06 mutant was not semantically killed: " + mutant.id());
      Shrunk shrunk = shrink(mutant, original.fingerprint(), judge);
      values.add(
          new Counterexample(
              mutant.id(),
              mutant.seedCommands(),
              shrunk.commands(),
              shrunk.observation(),
              shrunk.oneMinimal()));
    }
    M06PropertyJudge.Observation system =
        judge.judge(
            List.of(M06Mutants.required().getFirst().seedCommands().getLast()),
            M06Mutants.systemErrorControl());
    require(
        M06PropertyJudge.SYSTEM_ERROR.equals(system.classification()),
        "M06 SYSTEM_ERROR control did not remain distinct from semantic mutant kills");

    ObjectNode persisted = persisted(values);
    JsonSupport.validate(persisted, readString(root.resolve(SCHEMA_PATH)), false);
    byte[] persistedBytes = JsonSupport.prettyBytes(persisted);
    Replay replay = replay(root, persistedBytes);
    require(replay.passed() == values.size(), "M06 strict replay count changed");
    M06Canonical.Canonical canonical = canonical(values);
    return new Result(
        List.copyOf(values), persisted, persistedBytes, replay, canonical, system.classification());
  }

  private static Shrunk shrink(
      M06Mutants.Mutant mutant, String fingerprint, M06PropertyJudge judge) {
    List<M06ReferenceCommand> current = new ArrayList<>(mutant.seedCommands());
    boolean changed;
    do {
      changed = false;
      for (int index = 0; index < current.size(); index++) {
        List<M06ReferenceCommand> trial = without(current, index);
        M06PropertyJudge.Observation observation = judge.judge(trial, mutant.factory());
        if (sameKill(observation, fingerprint)) {
          current = new ArrayList<>(trial);
          changed = true;
          break;
        }
      }
    } while (changed);
    M06PropertyJudge.Observation finalObservation = judge.judge(current, mutant.factory());
    require(sameKill(finalObservation, fingerprint), "M06 shrink lost its target fingerprint");
    boolean oneMinimal = true;
    for (int index = 0; index < current.size(); index++) {
      if (sameKill(judge.judge(without(current, index), mutant.factory()), fingerprint)) {
        oneMinimal = false;
        break;
      }
    }
    require(oneMinimal, "M06 shrinker did not reach one-minimality");
    return new Shrunk(List.copyOf(current), finalObservation, true);
  }

  private static Replay replay(Path root, byte[] bytes) {
    JsonNode document = JsonSupport.parse(bytes);
    JsonSupport.validate(document, readString(root.resolve(SCHEMA_PATH)), false);
    Map<String, M06Mutants.Mutant> mutants = new LinkedHashMap<>();
    M06Mutants.required().forEach(mutant -> mutants.put(mutant.id(), mutant));
    List<ReplayScenario> scenarios = new ArrayList<>();
    M06PropertyJudge judge = new M06PropertyJudge();
    for (JsonNode node : document.path("counterexamples")) {
      String id = node.path("mutantId").stringValue();
      M06Mutants.Mutant mutant = mutants.get(id);
      require(mutant != null, "persisted M06 replay references an unknown mutant");
      List<M06ReferenceCommand> commands = new ArrayList<>();
      node.path("commands").forEach(command -> commands.add(M06CommandJson.read(command)));
      M06PropertyJudge.Observation observation = judge.judge(commands, mutant.factory());
      boolean passed =
          M06PropertyJudge.STUDENT_FAILURE.equals(observation.classification())
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
    root.put("schemaVersion", "matching.m06.counterexamples.v1");
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
      value.minimized().forEach(command -> commands.add(M06CommandJson.write(command)));
    }
    return root;
  }

  private static M06Canonical.Canonical canonical(List<Counterexample> values) {
    List<String> lines = new ArrayList<>();
    lines.add("M06X1");
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
                + M06Canonical.command(value.minimized().get(index)));
        commands++;
      }
    }
    return M06Canonical.canonical(lines, commands);
  }

  private static boolean sameKill(M06PropertyJudge.Observation observation, String fingerprint) {
    return M06PropertyJudge.STUDENT_FAILURE.equals(observation.classification())
        && fingerprint.equals(observation.fingerprint());
  }

  private static List<M06ReferenceCommand> without(
      List<M06ReferenceCommand> commands, int removedIndex) {
    List<M06ReferenceCommand> result = new ArrayList<>(commands.size() - 1);
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
      List<M06ReferenceCommand> original,
      List<M06ReferenceCommand> minimized,
      M06PropertyJudge.Observation observation,
      boolean oneMinimal) {}

  record ReplayScenario(
      String mutantId, boolean passed, String classification, String fingerprint, int commands) {}

  record Replay(List<ReplayScenario> scenarios, int passed) {}

  record Result(
      List<Counterexample> counterexamples,
      ObjectNode persisted,
      byte[] persistedBytes,
      Replay replay,
      M06Canonical.Canonical canonical,
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
      List<M06ReferenceCommand> commands,
      M06PropertyJudge.Observation observation,
      boolean oneMinimal) {}
}
