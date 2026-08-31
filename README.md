# CEX Matching

The matching project for the Signal Grid **High-Availability CEX Trading Core** course.

M00 published the executable limit-order input contract. M01 published a deterministic,
single-writer `BTC-USDT` GTC order book with price-time matching and ordered event batches. M02
completed addressable cancellation and irreversible order terminal states. M03 completed the next
proof obligation: it compares that production engine command by command with an independently
implemented linear-scan model over 256 deterministic generated histories, then shrinks, persists,
and strictly replays every required semantic counterexample. M04 completes one closed
execution-policy axis: GTC, IOC, FOK, and Post-only on the same protected limit-order semantics.
M05 completes one more closed axis: a content-addressed, versioned absolute order-entry price band
delivered through Prepare/Activate and an in-memory application-sequence fence. M06 is now the
single active implementation window. Its frozen RED boundary adds `OPEN/CANCEL_ONLY/HALTED`, a safe
serialized transition graph, and deterministic HALTED-only Mass Cancel in global acceptance order.
STP, persistence, networking, performance work, and Aeron Cluster remain later units.

## Current course boundary

- Profile: `SPOT-CEX-1.0`
- Plan version: `0.8`
- Unit: `M06`
- Declared start ref: `course/m06-start`
- Declared complete ref: `course/m06-complete`
- Latest product stopping point: `matching-0.1.0` at M03; M04–M06 have no product release
- Lifecycle at this boundary: `READY / GOAL_NOT_IMPLEMENTED`
- Java toolchain: 25 LTS
- Gradle Wrapper: 9.7.1 with a pinned distribution checksum

The Gradle Daemon JVM criteria and Java toolchain both require an Adoptium JDK 25. If it is absent,
the configured Foojay resolver can provision it locally before the build; `.java-version` also
records the major version for compatible JDK managers. CI uses Temurin 25.

The structured start boundary is verified with:

```bash
./gradlew clean build --no-daemon
./gradlew m06Check --no-daemon
```

The clean build remains green because the root build is still gated by completed M05. `m06Check`
validates the exact course declaration, a 15-scenario / 64-command fixed input corpus, seed `6606`,
160 SplitMix64 histories of 64 commands across five lanes, 26 coverage IDs, ten required mutant
IDs, and five tutorial permalinks. It writes strict `matching.m06.check.v1` beneath
`build/reports/m06/`, reports `GOAL_NOT_IMPLEMENTED`, and exits non-zero intentionally. It does not
invent completion histories, output hashes, mutant kills, or evidence. The exact contract is in
[`docs/specs/m06.md`](docs/specs/m06.md).

M06 separates state transition from book termination. A mode change never clears orders;
`HALTED -> OPEN` is forbidden; and Mass Cancel is accepted only in `HALTED`, where it terminates all
resting orders atomically in global ascending acceptance-sequence order. `OperatorId` is audit
attribution for an upstream-authorized control request, not authorization performed by the matcher.

## Inherited M05 completion

The fixed M05F1 result history is 109,974 bytes / 67 lines with digest
`sha256:45be63337da83103a45040f5f73e9b996018d76f6d91f77e27cd5b2d9dbb8f7b`. The
generated M05H1 command history is 2,553,580 bytes / 10,401 lines with digest
`sha256:e742e53e1846730a0f242447b3065e23e352059807d8593dcc3e489498d453f5`. The
eight replayed M05X1 counterexamples total 57 minimized commands and have digest
`sha256:ea4aa501053d8bf11d8c31a4ba2f2b590b7b69d2c68d7c06cfaa7bf2c7c85a25`.
Reports also publish the four exact M05RS1 hash vectors, complete fixed event batches, coverage
witnesses, boundary facts, invariants, mutants, replay, and the current architecture result.

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

After committing the completed source and creating annotated `course/m05-complete` at that exact
clean HEAD, evidence is generated with:

```bash
./gradlew m05Evidence -Pm05.unitTag=course/m05-complete --no-daemon
```

The evidence writer reruns `m05Check`, verifies every artifact hash and a clean tree, rejects a
lightweight completion ref or any `matching-*` tag at the M05 HEAD, and publishes
`build/lab-evidence/M05/manifest.json`. M05 deliberately has `productRelease: null`.

## Immutable inherited baselines

M04 remains published at annotated `course/m04-complete`, peeled to
`9d1bca13da6b13aa97a8002baff37fbc2393abe4`. Its 14/48 fixed corpus, 192-by-64
generated history, 23 coverage obligations, eight mutants, and eight one-minimal counterexamples
are re-executed as a semantic regression. M05 does not pretend that the historical M04 source-count
and import gate describes the expanded current tree.

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

M06 keeps exactly these three modules. It creates no runtime, protocol, cluster, storage, database,
Counter, or Rest module. Start reports are written beneath `build/reports/m06/`; completion evidence
will be published only from the clean annotated completion commit after production, reference,
property, mutant, replay, architecture, and hash gates pass. Historical M00–M05 evidence remains attached to its
immutable completion tags.

Course dashboard: <https://lcha-reln.github.io/signal-grid-blog/practice/high-availability-cex/>

## License

Apache License 2.0.
