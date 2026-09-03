package io.github.lchareln.cex.matching.testkit;

import java.nio.file.Path;

/** Facade used by M12CheckRunner to compose pure semantic artifacts with a real cluster trace. */
final class M12SemanticJudge {
  Prepared prepare(Path repositoryRoot) {
    M12WorkloadLoader.Workload workload = M12WorkloadLoader.load(repositoryRoot);
    M12DeterministicCorpus.Corpus corpus = M12DeterministicCorpus.generate(workload);
    M12MutantSuite.Result mutants = new M12MutantSuite().run(workload, corpus);
    return new Prepared(workload, corpus, mutants);
  }

  Judgement judge(Prepared prepared, M12ExecutionTrace trace) {
    M12CoverageLedger.Result coverage =
        new M12CoverageLedger().run(prepared.workload(), trace, prepared.mutants().controls());
    return new Judgement(prepared, trace, coverage);
  }

  Judgement runDeterministicModelControl(Path repositoryRoot) {
    Prepared prepared = prepare(repositoryRoot);
    return judge(prepared, M12ExecutionTrace.deterministicModelControl(prepared.corpus()));
  }

  record Prepared(
      M12WorkloadLoader.Workload workload,
      M12DeterministicCorpus.Corpus corpus,
      M12MutantSuite.Result mutants) {}

  record Judgement(Prepared prepared, M12ExecutionTrace trace, M12CoverageLedger.Result coverage) {}
}
