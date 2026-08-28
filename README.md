# CEX Matching

The matching project for the Signal Grid **High-Availability CEX Trading Core** course.

M00 published the executable limit-order input contract. M01 published a deterministic,
single-writer `BTC-USDT` GTC order book with price-time matching and ordered event batches. M02 now
completes addressable cancellation and irreversible order terminal states while retaining every
accepted order identity for the lifetime of the engine process.
Persistence, networking, performance work, and Aeron Cluster remain later units.

## Current course boundary

- Profile: `SPOT-CEX-1.0`
- Plan version: `0.4`
- Unit: `M02`
- Declared start ref: `course/m02-start`
- Declared complete ref: `course/m02-complete`
- Lifecycle at this boundary: `CODE_VERIFIED / PASS`
- Java toolchain: 25 LTS
- Gradle Wrapper: 9.7.1 with a pinned distribution checksum

The Gradle Daemon JVM criteria and Java toolchain both require an Adoptium JDK 25. If it is absent,
the configured Foojay resolver can provision it locally before the build; `.java-version` also
records the major version for compatible JDK managers. CI uses Temurin 25.

The completed M02 boundary has one cumulative verification path:

```bash
./gradlew clean build --no-daemon
./gradlew m02Check --no-daemon
./gradlew m02Evidence -Pm02.unitTag=course/m02-complete --no-daemon
```

The check validates the strict ten-scenario, 34-command M02 oracle, eight negative schema probes,
all command event batches, lifecycle/registry invariants, 100 fresh `M02H1` replays, and four
required semantic mutants. The inherited M00 input contract and complete M01 price-time corpus are
regressions inside the same fail-closed run. The canonical history is 181 lines / 17,160 UTF-8
bytes with digest
`sha256:32054d63accba99b19db823c41f74bda73dc3b8a009b528f2834d2bc70839d16`.

The immutable educational RED boundary remains available at annotated `course/m02-start`.
Learners branch from that tag; the completed implementation and evidence are bound to annotated
`course/m02-complete`. The exact contract is in [`docs/specs/m02.md`](docs/specs/m02.md).

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
database, Counter, or Rest module. Reports are written beneath `build/reports/m02/`; the evidence
task requires a clean tree and an annotated `course/m02-complete` tag that peels to `HEAD`.

Course dashboard: <https://lcha-reln.github.io/signal-grid-blog/practice/high-availability-cex/>

## License

Apache License 2.0.
