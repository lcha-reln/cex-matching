package io.github.lchareln.cex.matching.testkit;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Finds, shrinks, persists, parses, and replays all eight required M05 semantic faults. */
final class M05CounterexampleSuite {
  static final String SCHEMA_PATH = "schemas/matching.m05.counterexamples.v1.schema.json";
  static final String MODEL_VERSION = "linear-scan-reference-m05-v1";

  Result run(Path repositoryRoot) {
    Path root = repositoryRoot.toAbsolutePath().normalize();
    M05ScenarioCorpus.Corpus corpus = M05ScenarioCorpus.load(root);
    M05GeneratorProfile profile = M05GeneratorProfile.load(root);
    List<M05GeneratedHistory> histories = new M05HistoryGenerator().generate(profile, corpus);
    M05GeneratedCoverage.Result coverage = new M05GeneratedCoverage().analyze(profile, histories);
    coverage.assertRequired();
    List<M05RequiredMutants.RequiredMutant> mutants = M05RequiredMutants.all();
    require(
        profile.requiredMutants().equals(mutants.stream().map(mutant -> mutant.id()).toList()),
        "M05 required-mutant registry differs from the frozen profile");
    require(
        profile
            .coverageRequirements()
            .containsAll(mutants.stream().map(mutant -> mutant.generatedCoverageKey()).toList()),
        "M05 mutant coverage keys are outside the frozen profile");

    List<Counterexample> counterexamples = new ArrayList<>();
    for (M05RequiredMutants.RequiredMutant mutant : mutants) {
      FailureSource failureSource = findGenerated(histories, mutant, coverage);
      M05GeneratedHistory source = failureSource.history();
      M05Shrinker.Result shrunk =
          new M05Shrinker()
              .shrink(
                  "history-" + source.historyIndex(),
                  source.seedHex(),
                  source.commands(),
                  mutant.factory(),
                  mutant.fingerprint());
      require(shrunk.oneMinimal(), "M05 counterexample is not one-minimal: " + mutant.id());
      require(
          shrunk.commands().size() < source.commands().size(),
          "M05 generated counterexample did not become shorter: " + mutant.id());
      require(
          mutant.fingerprint().matches(shrunk.observation()),
          "M05 counterexample fingerprint changed: " + mutant.id());
      counterexamples.add(new Counterexample(mutant, source, failureSource.observation(), shrunk));
    }

    M05PropertyJudge.Observation throwing =
        new M05PropertyJudge()
            .judge(
                "throwing-control",
                histories.getFirst().seedHex(),
                List.of(new M05Command.Cancel("BTC-USDT", BigInteger.ONE)),
                M05Mutants.throwingControl());
    require(
        M05PropertyJudge.SYSTEM_ERROR.equals(throwing.classification()),
        "M05 throwing control did not remain SYSTEM_ERROR");
    require(throwing.failure() == null, "M05 SYSTEM_ERROR control was misclassified as a kill");

    ObjectNode persisted = persisted(counterexamples);
    String schema = readString(root.resolve(SCHEMA_PATH));
    JsonSupport.validate(persisted, schema, false);
    byte[] persistedBytes = JsonSupport.prettyBytes(persisted);
    JsonNode reparsed = JsonSupport.parse(persistedBytes);
    JsonSupport.validate(reparsed, schema, false);
    M05CounterexampleCanonicalizer.CanonicalCounterexamples canonical =
        new M05CounterexampleCanonicalizer().canonicalize(reparsed);
    M05CounterexampleReplay.ReplayReport replay =
        new M05CounterexampleReplay().replay(reparsed, profile, corpus, mutants);
    require(
        replay.allPassed(),
        "persisted M05 counterexample replay failed: "
            + replay.scenarios().stream().filter(item -> !item.passed()).toList());
    require(replay.scenarios().size() == 8, "M05 replay count changed");
    return new Result(
        counterexamples, persisted, persistedBytes, canonical, replay, throwing.classification());
  }

  private static FailureSource findGenerated(
      List<M05GeneratedHistory> histories,
      M05RequiredMutants.RequiredMutant mutant,
      M05GeneratedCoverage.Result coverage) {
    for (M05GeneratedHistory history : histories) {
      M05PropertyJudge.Observation observation =
          new M05PropertyJudge().judge(history, mutant.factory());
      if (M05PropertyJudge.SYSTEM_ERROR.equals(observation.classification())) {
        throw new IllegalStateException(
            "required M05 mutant raised SYSTEM_ERROR: "
                + mutant.id()
                + ": "
                + observation.message());
      }
      if (mutant.fingerprint().matches(observation)
          && coverage.observedAt(
              mutant.generatedCoverageKey(),
              history.historyIndex(),
              observation.failure().commandIndex())) {
        return new FailureSource(history, observation);
      }
    }
    throw new IllegalStateException("required M05 semantic mutant survived: " + mutant.id());
  }

  private static ObjectNode persisted(List<Counterexample> counterexamples) {
    ObjectNode root = JsonSupport.MAPPER.createObjectNode();
    root.put("schemaVersion", "matching.m05.counterexamples.v1");
    root.put("fixtureSha256", M05StartCheckRunner.FIXED_CORPUS_SHA256);
    root.put("profileSha256", M05StartCheckRunner.GENERATOR_SHA256);
    root.put("generatorAlgorithm", M05GeneratorProfile.ALGORITHM);
    root.put("seedDerivation", M05CommandCanonicalizer.SEED_DERIVATION);
    root.put("modelVersion", MODEL_VERSION);
    ArrayNode scenarios = root.putArray("scenarios");
    counterexamples.forEach(counterexample -> scenarios.add(counterexample(counterexample)));
    return root;
  }

  private static ObjectNode counterexample(Counterexample counterexample) {
    M05RequiredMutants.RequiredMutant mutant = counterexample.mutant();
    M05GeneratedHistory source = counterexample.source();
    M05Shrinker.Result shrunk = counterexample.shrunk();
    M05PropertyJudge.Failure failure = shrunk.observation().failure();
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
    ArrayNode original = result.putArray("originalCommands");
    source.commands().forEach(command -> original.add(M05Json.command(command)));
    M05Candidate reference = new M05ReferenceCandidate();
    ArrayNode commands = result.putArray("commands");
    for (int index = 0; index < shrunk.commands().size(); index++) {
      M05Command command = shrunk.commands().get(index);
      commands.add(
          M05Json.replayCommand("command-" + (index + 1), command, reference.apply(command)));
    }
    result.set("actualAtFailure", M05Json.outcome(failure.actual()));
    return result;
  }

  private static String readString(Path path) {
    try {
      return Files.readString(path, StandardCharsets.UTF_8);
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
      M05RequiredMutants.RequiredMutant mutant,
      M05GeneratedHistory source,
      M05PropertyJudge.Observation sourceObservation,
      M05Shrinker.Result shrunk) {}

  private record FailureSource(
      M05GeneratedHistory history, M05PropertyJudge.Observation observation) {}

  record Result(
      List<Counterexample> counterexamples,
      JsonNode persisted,
      byte[] persistedBytes,
      M05CounterexampleCanonicalizer.CanonicalCounterexamples canonical,
      M05CounterexampleReplay.ReplayReport replay,
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
