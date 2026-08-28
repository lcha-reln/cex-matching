package io.github.lchareln.cex.matching.testkit;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Rechecks the complete M01 judge contract and its inherited M00 facts inside M02. */
final class M02M01Regression {
  Result verify(Path root, M02Candidate.Factory m02Factory) {
    byte[] fixture = readBytes(root.resolve(M01CheckRunner.FIXTURE_PATH));
    if (!M01CheckRunner.FROZEN_FIXTURE_SHA256.equals(Hashing.sha256Hex(fixture))) {
      return failure("M01 frozen fixture changed");
    }
    String schema = readString(root.resolve(M01CheckRunner.FIXTURE_SCHEMA_PATH));
    M01ScenarioPack pack = new M01ScenarioLoader().load(fixture, schema);
    M01Candidate.Factory m01Factory = adapt(m02Factory);
    M01Assertions.Observation observation = new M01Assertions().judge(pack, m01Factory);
    if (!M01Assertions.PASS.equals(observation.classification()) || observation.history() == null) {
      return failure("M01 production semantics regressed: " + observation.message());
    }
    M01CanonicalHistory canonical = new M01Canonicalizer().canonicalize(observation.history());
    if (!M01CheckRunner.EXPECTED_DIGEST.equals(canonical.digest())
        || canonical.lineCount() != M01CheckRunner.EXPECTED_LINES
        || canonical.bytes().length != M01CheckRunner.EXPECTED_BYTES
        || !Arrays.equals(readBytes(root.resolve(M01CheckRunner.GOLDEN_PATH)), canonical.bytes())) {
      return failure("M01 canonical evidence regressed");
    }
    M01M00Regression.Result m00 = new M01M00Regression().verify(root, m01Factory);
    if (!m00.passed()) {
      return new Result(
          false, pack.scenarios().size(), pack.caseCount(), canonical.digest(), m00, m00.message());
    }
    return new Result(
        true,
        pack.scenarios().size(),
        pack.caseCount(),
        canonical.digest(),
        m00,
        "M01 completion check and inherited M00 contract preserved");
  }

  private static M01Candidate.Factory adapt(M02Candidate.Factory source) {
    return () -> {
      M02Candidate candidate = source.create();
      return input -> adapt(candidate.place(input));
    };
  }

  private static M01Candidate.Outcome adapt(M02Candidate.Outcome source) {
    List<M01ScenarioPack.Event> events = new ArrayList<>();
    for (M02ScenarioPack.Event event : source.events()) {
      events.add(
          switch (event) {
            case M02ScenarioPack.Rejected rejected ->
                new M01ScenarioPack.Rejected(rejected.code(), rejected.field());
            case M02ScenarioPack.Accepted accepted ->
                new M01ScenarioPack.Accepted(
                    accepted.sequence(),
                    accepted.orderId(),
                    accepted.side(),
                    accepted.priceTicks(),
                    accepted.quantityLots());
            case M02ScenarioPack.Trade trade ->
                new M01ScenarioPack.Trade(
                    trade.makerSequence(),
                    trade.makerOrderId(),
                    trade.takerSequence(),
                    trade.takerOrderId(),
                    trade.priceTicks(),
                    trade.quantityLots());
            case M02ScenarioPack.Rested rested ->
                new M01ScenarioPack.Rested(
                    rested.sequence(),
                    rested.orderId(),
                    rested.side(),
                    rested.priceTicks(),
                    rested.remainingQuantityLots());
            case M02ScenarioPack.PlaceRejected ignored -> throw unexpectedM02Event();
            case M02ScenarioPack.CancelRejected ignored -> throw unexpectedM02Event();
            case M02ScenarioPack.Canceled ignored -> throw unexpectedM02Event();
          });
    }
    return new M01Candidate.Outcome(events, adapt(source.bookAfter()));
  }

  private static M01ScenarioPack.Book adapt(M02ScenarioPack.Book source) {
    return new M01ScenarioPack.Book(adaptLevels(source.bids()), adaptLevels(source.asks()));
  }

  private static List<M01ScenarioPack.Level> adaptLevels(List<M02ScenarioPack.Level> source) {
    return source.stream()
        .map(
            level ->
                new M01ScenarioPack.Level(
                    level.priceTicks(),
                    level.orders().stream()
                        .map(
                            order ->
                                new M01ScenarioPack.RestingOrder(
                                    order.sequence(),
                                    order.orderId(),
                                    order.remainingQuantityLots()))
                        .toList()))
        .toList();
  }

  private static IllegalStateException unexpectedM02Event() {
    return new IllegalStateException("M01 regression emitted an M02-only lifecycle event");
  }

  private static Result failure(String message) {
    return new Result(false, 0, 0, null, null, message);
  }

  private static byte[] readBytes(Path path) {
    try {
      return Files.readAllBytes(path);
    } catch (IOException exception) {
      throw new IllegalStateException("cannot read " + path, exception);
    }
  }

  private static String readString(Path path) {
    return new String(readBytes(path), StandardCharsets.UTF_8);
  }

  record Result(
      boolean passed,
      int m01Scenarios,
      int m01Commands,
      String m01Digest,
      M01M00Regression.Result m00,
      String message) {}
}
