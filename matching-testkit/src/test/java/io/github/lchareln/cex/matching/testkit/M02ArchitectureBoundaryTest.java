package io.github.lchareln.cex.matching.testkit;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class M02ArchitectureBoundaryTest {
  @Test
  void keepsTheDeterministicCoreBoundaryAndRequiresTheLifecycleSurface() {
    M02ArchitectureGate.Report report = new M02ArchitectureGate().verify(M02TestPaths.root());

    assertTrue(report.passed(), report.violations().toString());
    assertTrue(report.sourceFiles() > 0);
  }
}
