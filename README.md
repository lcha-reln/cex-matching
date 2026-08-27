# CEX Matching

The matching project for the Signal Grid **High-Availability CEX Trading Core** course.

M00 published the executable input contract for one `PlaceLimitOrder`. M01 adds the first stateful
business slice: a single-writer, in-memory `BTC-USDT` GTC order book with deterministic
price-time-priority matching and ordered business-event batches. Persistence, networking,
performance work, and Aeron Cluster remain later units.

## Current course boundary

- Profile: `SPOT-CEX-1.0`
- Plan version: `0.3`
- Unit: `M01`
- Declared start ref: `course/m01-start`
- Declared complete ref: `course/m01-complete`
- Lifecycle at this boundary: `CODE_VERIFIED / PASS`
- Java toolchain: 25 LTS
- Gradle Wrapper: 9.7.1 with a pinned distribution checksum

The Gradle Daemon JVM criteria and Java toolchain both require an Adoptium JDK 25. If it is absent,
the configured Foojay resolver can provision it locally before the build; `.java-version` also
records the major version for compatible JDK managers. CI uses Temurin 25.

The immutable M01 start boundary keeps two intentional outcomes:

```bash
./gradlew clean build   # succeeds
./gradlew m01Check      # fails with structured GOAL_NOT_IMPLEMENTED
```

`GOAL_NOT_IMPLEMENTED` is the expected educational gap. A compiler error, missing dependency,
fixture parse error, or infrastructure failure is not an acceptable starting state.

The completed boundary instead requires:

```bash
./gradlew clean build --no-daemon
./gradlew m01Check --no-daemon
./gradlew m01Evidence -Pm01.unitTag=course/m01-complete --no-daemon
```

`m01Check` replays the frozen 8-scenario/22-command oracle 100 times, verifies price-time priority,
maker-price execution, ordered event batches, quantity and book invariants, the inherited M00 input
contract, three required semantic mutants, and the M01 architecture boundary. Full M00 evidence is
not regenerated on M01: its no-order-book architecture claim remains scoped to the immutable
`course/m00-complete` tag.

The M00 implementation and evidence remain immutable at `course/m00-complete`. On that ref, the
published commands are:

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

`course/m00.2-start` supersedes both without rewriting or deleting either tag. M01 starts from the
published M00 completion commit and does not move any M00 ref or regenerate M00 evidence.

## Repository boundaries

```text
matching-core      deterministic business semantics; no I/O or runtime dependencies
matching-testkit   fixtures, replay, mutants, and evidence tooling used by the current unit
```

M01 still uses exactly these two modules. It adds no runtime, protocol, cluster, storage, or database
module. Stable reports are written to `build/reports/m01/`; a clean committed tree can generate
`build/lab-evidence/M01/manifest.json`, bound to the source commit and the annotated completion tag.
The exact contract is in `docs/specs/m01.md`.

Course dashboard: <https://lcha-reln.github.io/signal-grid-blog/practice/high-availability-cex/>

## License

Apache License 2.0.
