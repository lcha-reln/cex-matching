# CEX Matching

The matching project for the Signal Grid **High-Availability CEX Trading Core** course.

M00 published the executable limit-order input contract. M01 published a deterministic,
single-writer `BTC-USDT` GTC order book with price-time matching and ordered event batches. M02 now
freezes the next deliberate gap: addressable cancellation and irreversible order terminal states.
Persistence, networking, performance work, and Aeron Cluster remain later units.

## Current course boundary

- Profile: `SPOT-CEX-1.0`
- Plan version: `0.4`
- Unit: `M02`
- Declared start ref: `course/m02-start`
- Lifecycle at this boundary: `READY / GOAL_NOT_IMPLEMENTED`
- Java toolchain: 25 LTS
- Gradle Wrapper: 9.7.1 with a pinned distribution checksum

The Gradle Daemon JVM criteria and Java toolchain both require an Adoptium JDK 25. If it is absent,
the configured Foojay resolver can provision it locally before the build; `.java-version` also
records the major version for compatible JDK managers. CI uses Temurin 25.

The immutable M02 start boundary intentionally has two outcomes:

```bash
./gradlew clean build --no-daemon  # succeeds and reruns the complete M01 judge
./gradlew m02Check --no-daemon     # fails with structured GOAL_NOT_IMPLEMENTED
```

`GOAL_NOT_IMPLEMENTED` is the expected educational gap. The second command first validates the
strict ten-scenario, 34-command M02 oracle, its byte digest, fixed identities, command union,
complete event/book expectations, and eight negative schema probes. A compiler error, failed M01
regression, missing dependency, fixture parse error, or filesystem failure is not an acceptable
starting state.

M02 adds no production cancellation code at its start ref. Its exact contract is in
[`docs/specs/m02.md`](docs/specs/m02.md); learners add the addressable lifecycle registry,
cancellation path, and irreversible `FILLED`/`CANCELED` identity semantics after branching from
the annotated start tag.

## Immutable inherited baselines

M01 remains published at annotated `course/m01-complete`, peeled to
`be2e3b8e5db4959c5639d7aa3e7314dbac45d82b`. Its frozen commands remain:

```bash
./gradlew clean build --no-daemon
./gradlew m01Check --no-daemon
./gradlew m01Evidence -Pm01.unitTag=course/m01-complete --no-daemon
```

The first two continue to work as cumulative regression checks on M02. The third is a tag-scoped
publication command: M01 evidence says that cancellation and an order index do not exist, so it
must never be regenerated or rebound to an M02 commit. The published M01 bundle remains attached to
the immutable completion tag.

M00 remains published at annotated `course/m00-complete`. Its full no-order-book architecture and
mutant evidence are historical by definition after M01 introduced matching. M01 and M02 retain the
M00 input, validation, canonical-history, and digest regression without pretending that the old
architecture limitation still describes current HEAD.

## Repository boundaries

```text
matching-core      deterministic business semantics; no I/O or runtime dependencies
matching-testkit   fixtures, replay, mutants, and evidence tooling for signed course units
```

M02 still uses exactly these two modules. It creates no runtime, protocol, cluster, storage,
database, Counter, or Rest module. The start report is written beneath `build/reports/m02/`; there
is no M02 evidence bundle until the production contract passes and a clean completion commit can be
bound to an annotated `course/m02-complete` tag.

Course dashboard: <https://lcha-reln.github.io/signal-grid-blog/practice/high-availability-cex/>

## License

Apache License 2.0.
