package io.github.lchareln.cex.matching.testkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class M04ArchitectureGateTest {
  @Test
  void currentM04SourcesPreserveTheFrozenBoundary() {
    M04ArchitectureGate.Report report = new M04ArchitectureGate().verify(M04TestPaths.root());

    assertTrue(report.passed(), report.violations().toString());
    assertEquals(24, report.coreSourceFiles());
    assertEquals(7, report.referenceSourceFiles());
  }
}
