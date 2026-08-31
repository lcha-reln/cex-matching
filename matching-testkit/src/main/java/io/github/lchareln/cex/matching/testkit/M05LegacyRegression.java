package io.github.lchareln.cex.matching.testkit;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

/** Re-executes the completed M04 semantic proof without rebinding its historical evidence. */
final class M05LegacyRegression {
  private static final String FIXED_DIGEST =
      "sha256:68de35e41358ea72c9852fdf3fd652db116774964360f0b526f43612576bfa77";
  private static final String GENERATED_DIGEST =
      "sha256:6005c674d0c42927989f1c8c4d1ddce224d06ceff0b95bf58615d23c4496ba51";

  Result run(Path repositoryRoot, Path temporaryOutput, Path trustedOutputRoot) {
    Path root = repositoryRoot.toAbsolutePath().normalize();
    // Historical source-identity gates belong to course/m04-complete. M05 deliberately adds
    // sources, so only the complete frozen M04 semantic proof is re-executed here.
    new M04FrozenInputs().verify(root);
    new M04BoundaryFacts().verify(root);
    M04FixedScenarioRunner.Result fixed =
        new M04FixedScenarioRunner().run(root, M04ProductionCandidate::new);
    require(fixed.canonicalDigest().equals(FIXED_DIGEST), "M04F1 digest changed");

    M04GeneratorProfile profile =
        M04GeneratorProfile.load(
            root.resolve(M04StartCheckRunner.GENERATOR_PATH),
            root.resolve(M04StartCheckRunner.GENERATOR_SCHEMA_PATH));
    M04HistoryGenerator generator = new M04HistoryGenerator();
    List<M04GeneratedHistory> histories = generator.generate(profile);
    M04CommandCanonicalizer canonicalizer = new M04CommandCanonicalizer();
    M04CommandCanonicalizer.CanonicalCommands first =
        canonicalizer.canonicalize(profile, histories);
    M04CommandCanonicalizer.CanonicalCommands second =
        canonicalizer.canonicalize(profile, generator.generate(profile));
    require(Arrays.equals(first.bytes(), second.bytes()), "M04H1 regeneration changed");
    require(first.digest().equals(GENERATED_DIGEST), "M04H1 digest changed");
    require(first.commandCount() == 12_288, "M04 generated command count changed");

    M04PropertyJudge judge = new M04PropertyJudge();
    int commands = 0;
    for (M04GeneratedHistory history : histories) {
      M04PropertyJudge.Observation observation = judge.judge(history, M04ProductionCandidate::new);
      require(
          M04PropertyJudge.PASS.equals(observation.classification()),
          "M04 production regression failed: " + observation.message());
      commands += observation.completedCommands();
    }
    require(commands == 12_288, "M04 differential command count changed");
    M04GeneratedCoverage.Result coverage = new M04GeneratedCoverage().analyze(profile, histories);
    coverage.assertRequired();
    M04CounterexampleSuite.Result counterexamples = new M04CounterexampleSuite().run(root);
    require(counterexamples.counterexamples().size() == 8, "M04 counterexamples changed");
    require(counterexamples.replay().allPassed(), "M04 counterexample replay changed");
    require(
        M04PropertyJudge.SYSTEM_ERROR.equals(counterexamples.systemErrorControl()),
        "M04 SYSTEM_ERROR control changed");
    M04LegacyRegression.Result inherited = new M04LegacyRegression().run(root);
    require(inherited.mutants().size() == 6, "M03 regression mutant count changed");
    return new Result(14, 48, FIXED_DIGEST, 192, 12_288, GENERATED_DIGEST, 23, 8, 8);
  }

  private static void require(boolean condition, String message) {
    if (!condition) {
      throw new IllegalStateException(message);
    }
  }

  record Result(
      int fixedScenarios,
      int fixedCommands,
      String fixedDigest,
      int generatedHistories,
      int generatedCommands,
      String generatedDigest,
      int coverageObligations,
      int counterexamples,
      int mutantsKilled) {}
}
