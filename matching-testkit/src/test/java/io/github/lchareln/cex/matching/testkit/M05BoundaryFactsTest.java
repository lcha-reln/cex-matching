package io.github.lchareln.cex.matching.testkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

final class M05BoundaryFactsTest {
  @Test
  void threeImplementationsAgreeOnFourFrozenHashesAndExecutableBoundaries() {
    M05BoundaryFacts.Result result = new M05BoundaryFacts().verify(M05TestPaths.root());

    assertEquals("M05RS1", result.canonicalFormat());
    assertEquals(4, result.hashVectors());
    assertTrue(result.hashMismatchFailsClosed());
    assertTrue(result.sameVersionDifferentHashFailsClosed());
    assertTrue(result.lowerInclusive());
    assertTrue(result.upperInclusive());
    assertTrue(result.longMaximumInclusive());
    assertTrue(result.staleActivationFenceFailsClosed());
    assertTrue(result.stalePlaceFenceFailsClosed());
    assertTrue(result.grandfatherExistingOrders());

    assertEquals(
        List.of(
            "sha256:d9928c52e99b8611cb95fb0d2792b6901cf9336825e19a7f593393b0d2b99c04",
            "sha256:dbb75b3983480a8ece058736766411f80eb5c62e10eb24de72b74853d5377f91",
            "sha256:1e5934c44343fe92741732bc5af56c019fc0e785815ff8848ed810ad52247372",
            "sha256:d7d0a8e3a2d1882012f8ba6d7318ecf02e378f4766c26badff272a97e1e21f7d"),
        M05BoundaryFacts.frozenHashVectors().stream()
            .map(M05BoundaryFacts.HashVector::contentHash)
            .toList());
  }
}
