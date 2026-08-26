# CEX Matching

The matching project for the Signal Grid **High-Availability CEX Trading Core** course.

M00 starts with an executable input contract for one `PlaceLimitOrder`. It does **not** contain an
order book, matching algorithm, trades, persistence, networking, or Aeron Cluster. Those capabilities
are introduced only after the preceding unit has its own correctness evidence.

## Current course boundary

- Profile: `SPOT-CEX-1.0`
- Plan version: `0.1`
- Unit: `M00`
- Canonical start ref: `course/m00.2-start`
- Lifecycle on the canonical start ref: `READY`
- Lifecycle on the completed implementation commit: `CODE_VERIFIED`
- Java toolchain: 25 LTS
- Gradle Wrapper: 9.7.1 with a pinned distribution checksum

The Gradle Daemon JVM criteria and Java toolchain both require an Adoptium JDK 25. If it is absent,
the configured Foojay resolver can provision it locally before the build; `.java-version` also
records the major version for compatible JDK managers. CI uses Temurin 25.

The canonical start ref keeps two intentional outcomes:

```bash
./gradlew clean build   # succeeds
./gradlew m00Check      # fails with structured GOAL_NOT_IMPLEMENTED
```

`GOAL_NOT_IMPLEMENTED` is the expected educational gap. A compiler error, missing dependency,
fixture parse error, or infrastructure failure is not an acceptable starting state.

The completed implementation keeps that start ref immutable and turns the current branch into a
green, fail-closed contract:

```bash
./gradlew clean build --no-daemon
./gradlew m00Check --no-daemon
./gradlew m00Evidence -Pm00.unitTag=course/m00-complete --no-daemon
```

`m00Check` validates the strict fixture boundary, all 17 business cases, the checked-in canonical
golden, 100 fresh replays, the core architecture boundary, and the required
`M00-QTY-ZERO-ACCEPTED` semantic mutant. A mutant only counts as killed when the shared oracle
reports `STUDENT_FAILURE`; parser, runner, or environment failures remain `SYSTEM_ERROR` and fail
closed.

The stable check artifacts are written to `build/reports/m00/`. A clean committed tree can then
produce `build/lab-evidence/M00/manifest.json`; the manifest validates against
`cex.lab-evidence.v1`, verifies every artifact hash, and records the exact source commit. M00 is not
a product release and therefore creates no `matching-*` tag.

Two earlier start refs remain immutable for audit history and must not be used as the teaching
baseline:

- `course/m00-start` is a known failed bootstrap because the custom Gradle task source was not
  tracked by Git;
- `course/m00.1-start` fixes that source boundary, but its own documentation still points readers to
  the failed original ref.

`course/m00.2-start` supersedes both without rewriting or deleting either tag.

## Repository boundaries

```text
matching-core      deterministic business semantics; no I/O or runtime dependencies
matching-testkit   fixtures, replay, mutants, and evidence tooling used by the current unit
```

M00 must not add runtime, protocol, cluster, storage, database, or order-book modules. The bootstrap
`buildSrc` task remains preserved in the immutable start refs; the completed branch removes it and
delegates the real judge to `matching-testkit`.

Course dashboard: <https://lcha-reln.github.io/signal-grid-blog/practice/high-availability-cex/>

## License

Apache License 2.0.
