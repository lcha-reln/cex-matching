# CEX Matching

The matching project for the Signal Grid **High-Availability CEX Trading Core** course.

M00 published the executable limit-order input contract. M01 published a deterministic,
single-writer `BTC-USDT` GTC order book with price-time matching and ordered event batches. M02
completed addressable cancellation and irreversible order terminal states. M03 completed the next
proof obligation: it compares that production engine command by command with an independently
implemented linear-scan model over 256 deterministic generated histories, then shrinks, persists,
and strictly replays every required semantic counterexample. M04 now freezes the structured RED
boundary for one closed execution-policy axis: GTC, IOC, FOK, and Post-only on the same protected
limit-order semantics. No M04 production policy exists at this start ref. Persistence, networking,
performance work, and Aeron Cluster remain later units.

## Current course boundary

- Profile: `SPOT-CEX-1.0`
- Plan version: `0.6`
- Unit: `M04`
- Declared start ref: `course/m04-start`
- Declared complete ref: `course/m04-complete`
- Latest product stopping point: `matching-0.1.0` at M03; M04 has no product release
- Lifecycle at this boundary: `READY / GOAL_NOT_IMPLEMENTED`
- Java toolchain: 25 LTS
- Gradle Wrapper: 9.7.1 with a pinned distribution checksum

The Gradle Daemon JVM criteria and Java toolchain both require an Adoptium JDK 25. If it is absent,
the configured Foojay resolver can provision it locally before the build; `.java-version` also
records the major version for compatible JDK managers. CI uses Temurin 25.

The M04 start boundary deliberately separates the inherited green build from the new red goal:

```bash
./gradlew clean build --no-daemon
./gradlew m04Check --no-daemon
```

The first command remains green by running the completed M03 proof. The second validates a strict
14-scenario / 48-command fixed corpus plus a SplitMix64 profile of 192 histories by 64 commands in
six lanes, writes `build/reports/m04/check.json` with `matching.m04.check.v1` and
`GOAL_NOT_IMPLEMENTED`, and exits non-zero by design. The contract freezes the unchanged
five-field `PlaceLimitOrderInput`, the future composition type `PlaceLimitOrderRequest`, the closed
`ExecutionPolicy` values, decision priority, IOC `RemainderCanceled(IOC_REMAINDER)`, FOK and
Post-only zero-side-effect rejection, price protection through `priceTicks`, and eight required
semantic mutants. The exact contract is in [`docs/specs/m04.md`](docs/specs/m04.md).

The legacy `place(PlaceLimitOrderInput)` remains an explicit GTC path. M04 uses the distinct
`placeRequest(PlaceLimitOrderRequest)` entrypoint with raw `executionPolicy`; an unknown value is a
field `Rejected(INVALID_EXECUTION_POLICY, "executionPolicy")`, while FOK/Post-only business
admission failures remain `PlaceRejected`.

## Immutable inherited baselines

M03 remains published at annotated `course/m03-complete`, peeled to
`dab4a2a1dccf06d6b9769c979a6ae5af6d1d2bdc`, with the same commit under annotated
`matching-0.1.0`. Its frozen commands remain:

```bash
./gradlew m03Check --no-daemon
./gradlew m03Evidence -Pm03.unitTag=course/m03-complete -Pm03.productRelease=matching-0.1.0 --no-daemon
```

The first remains the cumulative production baseline at the M04 start. The second is a historical
publication command and must only run from the immutable M03 completion commit; M04 never rebinds
that evidence or product release.

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
matching-reference independent model; main/runtime is JDK-only with no project or production dependency
matching-testkit    generators, differential judge, replay, mutants, and evidence tooling
```

M04 keeps exactly these three modules and changes no `matching-core` production source at its RED
boundary. It creates no runtime, protocol, cluster, storage, database, Counter, or Rest module.
Reports are written beneath `build/reports/m04/`; no M04 evidence exists until the production,
reference, property, mutant, replay, and clean-tree completion gates pass. Historical M01–M03
evidence remains attached to its immutable completion tags.

Course dashboard: <https://lcha-reln.github.io/signal-grid-blog/practice/high-availability-cex/>

## License

Apache License 2.0.
