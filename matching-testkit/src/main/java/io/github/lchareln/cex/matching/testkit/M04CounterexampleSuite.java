package io.github.lchareln.cex.matching.testkit;

import io.github.lchareln.cex.matching.reference.LinearReferenceModel;
import io.github.lchareln.cex.matching.reference.ReferenceCommand;
import io.github.lchareln.cex.matching.reference.ReferenceMatcher;
import io.github.lchareln.cex.matching.reference.SemanticOutcome;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Find, shrink, persist, parse, and replay all eight required M04 semantic faults. */
final class M04CounterexampleSuite {
  static final String SCHEMA_PATH = "schemas/matching.m04.counterexamples.v1.schema.json";

  Result run(Path root) {
    M04GeneratorProfile profile =
        M04GeneratorProfile.load(
            root.resolve(M04StartCheckRunner.GENERATOR_PATH),
            root.resolve(M04StartCheckRunner.GENERATOR_SCHEMA_PATH));
    List<M04GeneratedHistory> histories = new M04HistoryGenerator().generate(profile);
    M04GeneratedCoverage.Result coverage = new M04GeneratedCoverage().analyze(profile, histories);
    List<M04RequiredMutants.RequiredMutant> mutants = M04RequiredMutants.all();
    List<Counterexample> counterexamples = new ArrayList<>();
    for (M04RequiredMutants.RequiredMutant mutant : mutants) {
      FailureSource failureSource = findGenerated(histories, mutant, coverage);
      M04GeneratedHistory source = failureSource.history();
      M04Shrinker.Result shrunk =
          new M04Shrinker()
              .shrink(
                  "history-" + source.historyIndex(),
                  source.seedHex(),
                  source.commands(),
                  mutant.factory(),
                  mutant.fingerprint());
      require(shrunk.oneMinimal(), "M04 counterexample is not one-minimal: " + mutant.id());
      require(
          shrunk.commands().size() < source.commands().size(),
          "M04 generated counterexample did not become shorter: " + mutant.id());
      require(
          mutant.fingerprint().matches(shrunk.observation()),
          "M04 counterexample fingerprint changed: " + mutant.id());
      counterexamples.add(new Counterexample(mutant, source, failureSource.observation(), shrunk));
    }
    M04PropertyJudge.Observation throwing =
        new M04PropertyJudge()
            .judge(
                "throwing-control",
                histories.getFirst().seedHex(),
                histories.getFirst().commands(),
                M04Mutants.throwingControl());
    require(
        M04PropertyJudge.SYSTEM_ERROR.equals(throwing.classification()),
        "M04 throwing control did not remain SYSTEM_ERROR");

    ObjectNode persisted = persisted(counterexamples);
    String schema = readString(root.resolve(SCHEMA_PATH));
    JsonSupport.validate(persisted, schema, false);
    byte[] persistedBytes = JsonSupport.prettyBytes(persisted);
    JsonNode reparsed = JsonSupport.parse(persistedBytes);
    JsonSupport.validate(reparsed, schema, false);
    M04CounterexampleCanonicalizer.CanonicalCounterexamples canonical =
        new M04CounterexampleCanonicalizer().canonicalize(reparsed);
    M04CounterexampleReplay.ReplayReport replay =
        new M04CounterexampleReplay().replay(reparsed, profile, mutants);
    require(replay.allPassed(), "persisted M04 counterexample replay failed");
    require(replay.scenarios().size() == 8, "M04 replay count changed");
    return new Result(
        counterexamples, persisted, persistedBytes, canonical, replay, throwing.classification());
  }

  private static FailureSource findGenerated(
      List<M04GeneratedHistory> histories,
      M04RequiredMutants.RequiredMutant mutant,
      M04GeneratedCoverage.Result coverage) {
    for (M04GeneratedHistory history : histories) {
      M04PropertyJudge.Observation observation =
          new M04PropertyJudge().judge(history, mutant.factory());
      if (M04PropertyJudge.SYSTEM_ERROR.equals(observation.classification())) {
        throw new IllegalStateException("required M04 mutant raised SYSTEM_ERROR: " + mutant.id());
      }
      if (mutant.fingerprint().matches(observation)
          && coverage.observedAt(
              mutant.generatedCoverageKey(),
              history.historyIndex(),
              observation.failure().commandIndex())) {
        return new FailureSource(history, observation);
      }
    }
    throw new IllegalStateException("required M04 semantic mutant survived: " + mutant.id());
  }

  private static ObjectNode persisted(List<Counterexample> counterexamples) {
    ObjectNode root = JsonSupport.MAPPER.createObjectNode();
    root.put("schemaVersion", "matching.m04.counterexamples.v1");
    root.put("fixtureSha256", M04StartCheckRunner.FIXED_CORPUS_SHA256);
    root.put("profileSha256", M04StartCheckRunner.GENERATOR_SHA256);
    root.put("generatorAlgorithm", M04GeneratorProfile.ALGORITHM);
    root.put("seedDerivation", M04CommandCanonicalizer.SEED_DERIVATION);
    root.put("modelVersion", "linear-scan-reference-v1");
    ArrayNode scenarios = root.putArray("scenarios");
    counterexamples.forEach(counterexample -> scenarios.add(counterexample(counterexample)));
    return root;
  }

  private static ObjectNode counterexample(Counterexample counterexample) {
    M04RequiredMutants.RequiredMutant mutant = counterexample.mutant();
    M04GeneratedHistory source = counterexample.source();
    M04Shrinker.Result shrunk = counterexample.shrunk();
    M04PropertyJudge.Failure failure = shrunk.observation().failure();
    ObjectNode result = JsonSupport.MAPPER.createObjectNode();
    result.put("scenarioId", mutant.scenarioId());
    result.put("mutantId", mutant.id());
    result.put("classification", shrunk.observation().classification());
    result.put("propertyId", failure.propertyId());
    result.put("divergenceKind", failure.divergenceKind());
    result.put("sourceKind", "GENERATED");
    result.put("historyIndex", source.historyIndex());
    result.put("lane", source.laneId());
    result.put("seed", source.seedHex());
    result.put("coverageKey", mutant.generatedCoverageKey());
    result.put(
        "sourceFailingCommandIndex", counterexample.sourceObservation().failure().commandIndex());
    result.put("originalCommandCount", source.commands().size());
    result.put("minimizedCommandCount", shrunk.commands().size());
    result.put("firstFailingCommandIndex", failure.commandIndex());
    result.put("oneMinimal", shrunk.oneMinimal());
    result.put("shrinkTrials", shrunk.trials());
    result.set("originalCommands", M04Json.commands(source.commands()));
    ReferenceMatcher reference = new LinearReferenceModel();
    ArrayNode commands = result.putArray("commands");
    for (int index = 0; index < shrunk.commands().size(); index++) {
      ReferenceCommand command = shrunk.commands().get(index);
      SemanticOutcome expected = reference.apply(command);
      commands.add(M04Json.replayCommand("command-" + (index + 1), command, expected));
    }
    result.set("actualAtFailure", M04Json.outcome(failure.actual()));
    return result;
  }

  private static String readString(Path path) {
    try {
      return Files.readString(path, StandardCharsets.UTF_8);
    } catch (IOException exception) {
      throw new IllegalStateException("cannot read " + path, exception);
    }
  }

  private static void require(boolean condition, String message) {
    if (!condition) {
      throw new IllegalStateException(message);
    }
  }

  record Counterexample(
      M04RequiredMutants.RequiredMutant mutant,
      M04GeneratedHistory source,
      M04PropertyJudge.Observation sourceObservation,
      M04Shrinker.Result shrunk) {}

  private record FailureSource(
      M04GeneratedHistory history, M04PropertyJudge.Observation observation) {}

  record Result(
      List<Counterexample> counterexamples,
      JsonNode persisted,
      byte[] persistedBytes,
      M04CounterexampleCanonicalizer.CanonicalCounterexamples canonical,
      M04CounterexampleReplay.ReplayReport replay,
      String systemErrorControl) {
    Result {
      counterexamples = List.copyOf(counterexamples);
      persistedBytes = persistedBytes.clone();
    }

    @Override
    public byte[] persistedBytes() {
      return persistedBytes.clone();
    }
  }
}
