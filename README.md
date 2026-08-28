# CEX Matching

The matching project for the Signal Grid **High-Availability CEX Trading Core** course.

M00 published the executable limit-order input contract. M01 published a deterministic,
single-writer `BTC-USDT` GTC order book with price-time matching and ordered event batches. M02
completed addressable cancellation and irreversible order terminal states. M03 now freezes the
next proof obligation: compare that production engine command by command with an independently
implemented linear-scan model over 256 deterministic generated histories, then shrink, persist,
and replay every required semantic counterexample. Persistence, networking, performance work, and
Aeron Cluster remain later units.

## Current course boundary

- Profile: `SPOT-CEX-1.0`
- Plan version: `0.5`
- Unit: `M03`
- Declared start ref: `course/m03-start`
- Declared complete ref: `course/m03-complete`
- Product stopping point: `matching-0.1.0`
- Lifecycle at this boundary: `READY / GOAL_NOT_IMPLEMENTED`
- Java toolchain: 25 LTS
- Gradle Wrapper: 9.7.1 with a pinned distribution checksum

The Gradle Daemon JVM criteria and Java toolchain both require an Adoptium JDK 25. If it is absent,
the configured Foojay resolver can provision it locally before the build; `.java-version` also
records the major version for compatible JDK managers. CI uses Temurin 25.

The M03 start boundary keeps the completed M02 matcher green and exposes one intentional,
structured RED check:

```bash
./gradlew clean build --no-daemon
./gradlew m03Check --no-daemon
```

The first command remains a cumulative M02 regression. The second validates the immutable M03
generator contract—SplitMix64 seed `6824`, 256 histories, 64 commands per history, four stratified
coverage lanes, and six negative schema probes—then exits non-zero with
`GOAL_NOT_IMPLEMENTED`. A compiler, schema, parser, filesystem, or runtime failure is not the
educational gap.

Learners branch from annotated `course/m03-start`. Completion will require annotated
`course/m03-complete`, the same peeled commit under annotated `matching-0.1.0`, clean-tree evidence,
and the exact contract in [`docs/specs/m03.md`](docs/specs/m03.md).

## Immutable inherited baselines

M02 remains published at annotated `course/m02-complete`, peeled to
`b54b4dfb51b61a5041d60c50dc1ff3404d73b27d`. Its frozen commands remain:

```bash
./gradlew clean build --no-daemon
./gradlew m02Check --no-daemon
./gradlew m02Evidence -Pm02.unitTag=course/m02-complete --no-daemon
```

The first two continue to work as cumulative regression checks on M03. M02 evidence remains
attached to its immutable tag and must not be rebound to an M03 commit.

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
matching-core       deterministic business semantics; no I/O or runtime dependencies
matching-reference test-only independent model; no core/testkit or external dependency
matching-testkit    generators, differential judge, replay, mutants, and evidence tooling
```

M03 still uses exactly these two modules. It creates no runtime, protocol, cluster, storage,
database, Counter, or Rest module. Reports are written beneath `build/reports/m02/`; the evidence
for the completed M02 baseline remains immutable. The M03 RED report is written beneath
`build/reports/m03/`; completion evidence will require both annotated M03 tags to peel to one clean
`HEAD`.

Course dashboard: <https://lcha-reln.github.io/signal-grid-blog/practice/high-availability-cex/>

## License

Apache License 2.0.
