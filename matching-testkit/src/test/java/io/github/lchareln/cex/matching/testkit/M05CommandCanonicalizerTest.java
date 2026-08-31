package io.github.lchareln.cex.matching.testkit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class M05CommandCanonicalizerTest {
  private static final Path ROOT = Path.of(System.getProperty("matching.repositoryRoot"));

  @Test
  void fixedAndGeneratedEncodingsAreDeterministicAndComplete() {
    M05ScenarioCorpus.Corpus corpus = M05ScenarioCorpus.load(ROOT);
    M05GeneratorProfile profile = M05GeneratorProfile.load(ROOT);
    M05HistoryGenerator generator = new M05HistoryGenerator();
    List<M05GeneratedHistory> first = generator.generate(profile, corpus);
    List<M05GeneratedHistory> second = generator.generate(profile, corpus);
    M05CommandCanonicalizer canonicalizer = new M05CommandCanonicalizer();
    M05CommandCanonicalizer.CanonicalCommands fixed = canonicalizer.fixed(corpus);
    M05CommandCanonicalizer.CanonicalCommands generated = canonicalizer.generated(profile, first);
    M05CommandCanonicalizer.CanonicalCommands regenerated =
        canonicalizer.generated(profile, second);

    assertEquals(54, fixed.commandCount());
    assertEquals(10_240, generated.commandCount());
    assertArrayEquals(generated.bytes(), regenerated.bytes());
    assertEquals(generated.digest(), regenerated.digest());
  }
}
