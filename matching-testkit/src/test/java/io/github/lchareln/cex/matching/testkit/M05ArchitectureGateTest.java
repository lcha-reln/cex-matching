package io.github.lchareln.cex.matching.testkit;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class M05ArchitectureGateTest {
  @Test
  void keepsCoreDeterministicAndReferenceIndependent() {
    M05ArchitectureGate.Report report = new M05ArchitectureGate().verify(M05TestPaths.root());
    assertTrue(report.passed(), () -> "M05 architecture violations: " + report.violations());
    assertTrue(report.coreSources().size() >= 24);
    assertTrue(report.referenceSources().size() >= 7);
  }
}
