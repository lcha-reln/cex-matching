# CEX Matching

The matching project for the Signal Grid **High-Availability CEX Trading Core** course.

M00 published the executable limit-order input contract. M01 published a deterministic,
single-writer `BTC-USDT` GTC order book with price-time matching and ordered event batches. M02
completed addressable cancellation and irreversible order terminal states. M03 completed the next
proof obligation: it compares that production engine command by command with an independently
implemented linear-scan model over 256 deterministic generated histories, then shrinks, persists,
and strictly replays every required semantic counterexample. M04 completes one closed
execution-policy axis: GTC, IOC, FOK, and Post-only on the same protected limit-order semantics. It
adds independent reference behavior, an event-derived lifecycle ledger, deterministic fixed and
generated histories, eight semantic mutants, strict counterexample replay, and clean-tree evidence.
Persistence, networking, performance work, and Aeron Cluster remain later units.

## Current course boundary

- Profile: `SPOT-CEX-1.0`
- Plan version: `0.6`
- Unit: `M04`
- Declared start ref: `course/m04-start`
- Declared complete ref: `course/m04-complete`
- Latest product stopping point: `matching-0.1.0` at M03; M04 has no product release
- Lifecycle at this boundary: `CODE_VERIFIED / PASS`
- Java toolchain: 25 LTS
- Gradle Wrapper: 9.7.1 with a pinned distribution checksum

The Gradle Daemon JVM criteria and Java toolchain both require an Adoptium JDK 25. If it is absent,
the configured Foojay resolver can provision it locally before the build; `.java-version` also
records the major version for compatible JDK managers. CI uses Temurin 25.

The completed unit is verified with:

```bash
./gradlew clean build --no-daemon
./gradlew m04Check --no-daemon
```

Both commands pass. The root build is gated by the completed `m04Check`, which writes the strict
`matching.m04.check.v2` report beneath `build/reports/m04/`. It executes a 14-scenario / 48-command
fixed corpus and 192 SplitMix64 histories of 64 commands across six lanes, compares production,
the independent reference, and an event-derived ledger at every boundary, and kills and replays all
eight required semantic mutants. The exact contract is in
[`docs/specs/m04.md`](docs/specs/m04.md).

The fixed M04F1 golden is 47,104 bytes / 63 lines with digest
`sha256:68de35e41358ea72c9852fdf3fd652db116774964360f0b526f43612576bfa77`. The
generated M04H1 history is 1,496,773 bytes / 12,481 lines with digest
`sha256:6005c674d0c42927989f1c8c4d1ddce224d06ceff0b95bf58615d23c4496ba51`.
Reports also include a replayable scenario pack, its exact event-batch projection, semantic
coverage, boundary facts, M04X1 counterexamples, mutants, and architecture results.

The legacy `place(PlaceLimitOrderInput)` remains an explicit GTC path. M04 uses the distinct
`placeRequest(PlaceLimitOrderRequest)` entrypoint with raw `executionPolicy`; an unknown value is a
field `Rejected(INVALID_EXECUTION_POLICY, "executionPolicy")`, while FOK/Post-only business
admission failures remain `PlaceRejected`.

Compatibility is intentionally precise. The old five-field `PlaceLimitOrderInput`,
`place(PlaceLimitOrderInput)`, and five-argument `MatchingEvent.Accepted` constructor remain
callable; legacy GTC neutral business events, book state, and lifecycle semantics remain
equivalent; and the frozen M03G1 command bytes and digest remain unchanged. M04 adds a component to
the `Accepted` record and a subtype to the sealed event hierarchy, so reflection, record shape,
`toString`, default serializer output, and exhaustive switches can change. No external wire codec
has been frozen yet.

After committing the completed source and creating annotated `course/m04-complete` at that exact
clean HEAD, evidence is generated with:

```bash
./gradlew m04Evidence -Pm04.unitTag=course/m04-complete --no-daemon
```

The evidence writer reruns `m04Check`, verifies hashes and a clean tree, rejects lightweight unit
tags and any `matching-*` tag at the M04 HEAD, and publishes
`build/lab-evidence/M04/manifest.json`. M04 deliberately has `productRelease: null`.

## Immutable inherited baselines

M03 remains published at annotated `course/m03-complete`, peeled to
`dab4a2a1dccf06d6b9769c979a6ae5af6d1d2bdc`, with the same commit under annotated
`matching-0.1.0`. Its frozen commands remain:

```bash
./gradlew m03Check --no-daemon
./gradlew m03Evidence -Pm03.unitTag=course/m03-complete -Pm03.productRelease=matching-0.1.0 --no-daemon
```

Both are historical commands and must run only from the immutable M03 completion commit. M04 adds
four core source files, so it does not execute or rebind M03's frozen 20-source architecture
identity. Instead, `M04LegacyRegression` reruns the M03 semantic suite, exact 16,384-command M03G1
digest, six mutants, and six one-minimal counterexamples, then the new M04 architecture gate checks
the current 24 core and seven reference sources.

M02 remains published at annotated `course/m02-complete`, peeled to
`b54b4dfb51b61a5041d60c50dc1ff3404d73b27d`. Its frozen commands remain:

```bash
./gradlew clean build --no-daemon
./gradlew m02Check --no-daemon
./gradlew m02Evidence -Pm02.unitTag=course/m02-complete --no-daemon
```

These are historical tag-scoped commands. M02 evidence remains attached to its immutable tag and
must not be rebound to a later commit.

M01 remains published at annotated `course/m01-complete`, peeled to
`be2e3b8e5db4959c5639d7aa3e7314dbac45d82b`. Its frozen commands remain:

```bash
./gradlew clean build --no-daemon
./gradlew m01Check --no-daemon
./gradlew m01Evidence -Pm01.unitTag=course/m01-complete --no-daemon
```

These are historical tag-scoped commands. M01 evidence says that cancellation and an order index do
not exist, so it must never be regenerated or rebound to a later commit.

M00 remains published at annotated `course/m00-complete`. Its full no-order-book architecture and
mutant evidence are historical by definition after M01 introduced matching. M01 and M02 retain the
M00 input, validation, canonical-history, and digest regression without pretending that the old
architecture limitation still describes current HEAD.

## Repository boundaries

```text
matching-core       deterministic business semantics; no I/O or runtime dependencies
matching-reference independent model; main/runtime is JDK-only with no project or production dependency
matching-testkit    generators, differential judge, replay, mutants, and evidence tooling
```

M04 keeps exactly these three modules. It creates no runtime, protocol, cluster, storage, database,
Counter, or Rest module. Reports are written beneath `build/reports/m04/`; evidence is published
only from the clean annotated completion commit after production, reference, property, mutant,
replay, architecture, and hash gates pass. Historical M01–M03 evidence remains attached to its
immutable completion tags.

Course dashboard: <https://lcha-reln.github.io/signal-grid-blog/practice/high-availability-cex/>

## License

Apache License 2.0.
