package io.github.lchareln.cex.matching.testkit;

import io.github.lchareln.cex.matching.PlaceLimitOrderInput;
import io.github.lchareln.cex.matching.PlaceLimitOrderValidator;
import io.github.lchareln.cex.matching.ValidationResult;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Rechecks the inherited M00 input, validation, and canonical contracts inside M01. */
final class M01M00Regression {
  private static final int REPLAYS = 100;
  private static final String EXPECTED_DIGEST =
      "sha256:2d287d677d5f200f2b5bd1dd18dabbd40e865779489ce6da36d0411a3b670669";

  Result verify(Path root, M01Candidate.Factory candidateFactory) {
    Path fixturePath =
        root.resolve("matching-testkit/src/test/resources/m00/fixtures/history-v1.json");
    Path schemaPath = root.resolve("schemas/matching.m00.fixture.v1.schema.json");
    Path goldenPath =
        root.resolve("matching-testkit/src/test/resources/m00/golden/history-v1.canonical.txt");
    Path digestPath =
        root.resolve("matching-testkit/src/test/resources/m00/golden/history-v1.sha256");
    byte[] fixtureBytes = readBytes(fixturePath);
    String schema = readString(schemaPath);
    M00FixtureLoader loader = new M00FixtureLoader();
    M00Fixture fixture = loader.load(fixtureBytes, schema);
    PlaceLimitOrderValidator validator = new PlaceLimitOrderValidator();

    int valid = 0;
    int invalid = 0;
    for (M00Fixture.Record record : fixture.records()) {
      ValidationResult actual = validator.validate(record.input());
      if (!matches(record.expected(), actual)) {
        return new Result(
            false,
            fixture.records().size(),
            valid,
            invalid,
            0,
            0,
            null,
            "M00 case " + record.caseId() + " no longer matches its frozen expectation");
      }
      if (actual instanceof ValidationResult.Valid) {
        valid++;
      } else {
        invalid++;
      }
    }

    String engineRegressionFailure = verifyEngineInvalidBoundary(fixture, candidateFactory);
    if (engineRegressionFailure != null) {
      return new Result(
          false, fixture.records().size(), valid, invalid, 0, 0, null, engineRegressionFailure);
    }

    List<PlaceLimitOrderInput> inputs = inputs(fixture);
    CanonicalHistory canonical = new M00Canonicalizer().canonicalize(inputs);
    if (!Arrays.equals(readBytes(goldenPath), canonical.bytes())
        || !EXPECTED_DIGEST.equals(readString(digestPath).strip())
        || !EXPECTED_DIGEST.equals(canonical.digest())) {
      return new Result(
          false,
          fixture.records().size(),
          valid,
          invalid,
          0,
          0,
          canonical.digest(),
          "M00 canonical history or digest changed");
    }

    Set<String> digests = new LinkedHashSet<>();
    for (int replay = 0; replay < REPLAYS; replay++) {
      M00Fixture fresh = loader.load(fixtureBytes, schema);
      CanonicalHistory replayHistory = new M00Canonicalizer().canonicalize(inputs(fresh));
      if (!Arrays.equals(canonical.bytes(), replayHistory.bytes())) {
        return new Result(
            false,
            fixture.records().size(),
            valid,
            invalid,
            replay,
            digests.size(),
            replayHistory.digest(),
            "M00 fresh replay bytes changed");
      }
      digests.add(replayHistory.digest());
    }
    return new Result(
        digests.size() == 1,
        fixture.records().size(),
        valid,
        invalid,
        REPLAYS,
        digests.size(),
        canonical.digest(),
        digests.size() == 1 ? "M00 input contract preserved" : "M00 replay digest diverged");
  }

  private static boolean matches(M00Fixture.Expected expected, ValidationResult actual) {
    if ("VALID".equals(expected.status())) {
      return actual instanceof ValidationResult.Valid;
    }
    return actual instanceof ValidationResult.Invalid invalid
        && expected.code() == invalid.code()
        && expected.field().equals(invalid.field());
  }

  private static String verifyEngineInvalidBoundary(
      M00Fixture fixture, M01Candidate.Factory candidateFactory) {
    M01Candidate candidate = Objects.requireNonNull(candidateFactory.create(), "candidate");
    M01ScenarioPack.Book empty = M01ScenarioPack.Book.empty();
    List<M00Fixture.Record> invalidRecords =
        fixture.records().stream()
            .filter(record -> "INVALID".equals(record.expected().status()))
            .toList();
    for (M00Fixture.Record record : invalidRecords) {
      M01Candidate.Outcome actual =
          Objects.requireNonNull(candidate.place(record.input()), "candidate outcome");
      M01Candidate.Outcome expected = rejected(record, empty);
      if (!expected.equals(actual)) {
        return "M01 engine no longer preserves M00 rejection semantics for " + record.caseId();
      }
    }

    M00Fixture.Record validProbe =
        fixture.records().stream()
            .filter(record -> "VALID".equals(record.expected().status()))
            .findFirst()
            .orElseThrow(
                () -> new IllegalStateException("M00 fixture has no valid sequence probe"));
    M01Candidate.Outcome afterInvalids =
        Objects.requireNonNull(candidate.place(validProbe.input()), "candidate outcome");
    if (afterInvalids.events().isEmpty()
        || !(afterInvalids.events().getFirst() instanceof M01ScenarioPack.Accepted accepted)
        || accepted.sequence() != 1) {
      return "M01 engine consumed acceptance sequence while rejecting an M00-invalid input";
    }

    M01Candidate seededCandidate =
        Objects.requireNonNull(candidateFactory.create(), "seeded candidate");
    M01Candidate.Outcome seed =
        Objects.requireNonNull(seededCandidate.place(validProbe.input()), "seed outcome");
    if (seed.events().isEmpty()
        || !(seed.events().getFirst() instanceof M01ScenarioPack.Accepted seedAccepted)
        || seedAccepted.sequence() != 1
        || seed.bookAfter().equals(empty)) {
      return "M01 engine could not establish the resting-book rejection probe";
    }
    for (M00Fixture.Record record : invalidRecords) {
      M01Candidate.Outcome actual =
          Objects.requireNonNull(seededCandidate.place(record.input()), "candidate outcome");
      if (!rejected(record, seed.bookAfter()).equals(actual)) {
        return "M01 engine changed an existing book while rejecting " + record.caseId();
      }
    }
    M00Fixture.Record secondValidProbe =
        fixture.records().stream()
            .filter(record -> "VALID".equals(record.expected().status()))
            .skip(1)
            .findFirst()
            .orElseThrow(
                () -> new IllegalStateException("M00 fixture has no second valid sequence probe"));
    M01Candidate.Outcome afterSeededInvalids =
        Objects.requireNonNull(
            seededCandidate.place(secondValidProbe.input()), "candidate outcome");
    if (afterSeededInvalids.events().isEmpty()
        || !(afterSeededInvalids.events().getFirst()
            instanceof M01ScenarioPack.Accepted secondAccepted)
        || secondAccepted.sequence() != 2
        || !afterSeededInvalids.bookAfter().bids().equals(seed.bookAfter().bids())) {
      return "M01 engine changed the seeded book or sequence while rejecting an M00-invalid input";
    }
    return null;
  }

  private static M01Candidate.Outcome rejected(
      M00Fixture.Record record, M01ScenarioPack.Book bookAfter) {
    return new M01Candidate.Outcome(
        List.of(
            new M01ScenarioPack.Rejected(
                record.expected().code().name(), record.expected().field())),
        bookAfter);
  }

  private static List<PlaceLimitOrderInput> inputs(M00Fixture fixture) {
    return fixture.records().stream().map(M00Fixture.Record::input).toList();
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
      int records,
      int valid,
      int invalid,
      int completedReplays,
      int distinctDigests,
      String digest,
      String message) {}
}
