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
delivered through Prepare/Activate and an in-memory application-sequence fence. M06 completes the
next closed axis: `OPEN/CANCEL_ONLY/HALTED`, a safe serialized transition graph, and deterministic
HALTED-only Mass Cancel in global acceptance order.
M07 completes the next deliberately narrow axis: an upstream-resolved opaque participant group and
taker-owned self-trade-prevention instruction. M08 now freezes the next structured RED boundary:
a caller-serialized, single-shard local WAL, durable command identity, restart recovery, and an ACK
that may occur only after append, force, and matching apply. Networking, replication, failover,
performance work, and Aeron Cluster remain later units.

## Current course boundary

- Profile: `SPOT-CEX-1.0`
- Plan version: `0.10`
- Unit: `M08`
- Declared start ref: `course/m08-start`
- Declared complete ref: `course/m08-complete`
- Latest product stopping point: `matching-0.1.0` at M03; M04–M08 have no product release
- Lifecycle at this boundary: `READY / GOAL_NOT_IMPLEMENTED`
- Java toolchain: 25 LTS
- Gradle Wrapper: 9.7.1 with a pinned distribution checksum

The Gradle Daemon JVM criteria and Java toolchain both require an Adoptium JDK 25. If it is absent,
the configured Foojay resolver can provision it locally before the build; `.java-version` also
records the major version for compatible JDK managers. CI uses Temurin 25.

The inherited completion and intentional M08 RED boundary are verified separately:

```bash
./gradlew clean build --no-daemon
./gradlew m08Check --no-daemon # expected to exit non-zero at course/m08-start
```

The root build remains gated by the completed `m07Check`. At the M08 start ref, `m08Check` validates
only the frozen 20-scenario input, seed `5808`, 96 histories by 48 operations across four lanes, 24
coverage identities, ten mutant identities, five tutorial coordinates, strict schemas, and exact
fixture hashes. It then writes `matching.m08.check.v1 / GOAL_NOT_IMPLEMENTED` and exits non-zero.
The start ref publishes no runtime implementation, generated result digest, fault outcome, mutant
kill, counterexample, or completion evidence. The exact contract is in
[`docs/specs/m08.md`](docs/specs/m08.md).

## Inherited M07 completion

The root build is gated by `m07Check`. The completion judge executes the exact 16-scenario /
72-command fixed corpus and regenerates seed `5707` into 160 SplitMix64 histories of 64 commands
across five lanes. Production, the independent linear reference, and an event-derived ledger agree
at all 10,240 generated command boundaries; all 24 obligations have concrete witnesses. Eight
required semantic mutants are killed as `STUDENT_FAILURE`, their persisted one-minimal
counterexamples strictly replay, and the throwing `SYSTEM_ERROR` control is excluded from kills.
The strict `matching.m07.check.v2` report is written beneath `build/reports/m07/`. This is bounded
deterministic evidence, not exhaustive exploration, formal verification, replication evidence, or
a production-readiness claim. The exact contract is in [`docs/specs/m07.md`](docs/specs/m07.md).

M07 treats a positive participant group as an opaque equality key supplied by an upstream policy
boundary. Group `0/NONE` preserves every legacy entrypoint. For a real same-positive-group encounter,
only the incoming taker's `CANCEL_TAKER`, `CANCEL_MAKER`, or `CANCEL_BOTH` instruction is operative;
FOK preflight is STP-aware, while Post-only observes the raw book before any STP action. None of
the upstream account-resolution or authorization logic belongs to matching-core.

The M07F1 fixed history is 10,128 bytes / 73 lines with digest
`sha256:4c0675ee77458fb10b28e3c13d48767a653a41e922f42264f8d0f76aa5644176`. The M07H1
generated history is 1,709,692 bytes / 10,241 lines with digest
`sha256:c2576f10a77c320ec4a9ad75e3dc3c03494f636feabdcc7157ee10e74812718f`. The eight
M07X1 counterexamples contain 18 minimized commands and occupy 2,778 bytes / 19 lines with digest
`sha256:97504762c7f6349ac6bb02c26457d608dae6e0ad0231a19b10cf5c998a9c69ee`. Every proper
prefix remains PASS, so no mutant is killed by exposing rewritten metadata on its first maker order.

After committing the completed source and creating annotated `course/m07-complete` at that exact
clean HEAD, evidence is generated with:

```bash
./gradlew m07Evidence -Pm07.unitTag=course/m07-complete --no-daemon
```

The writer reruns `m07Check`, verifies the annotated tag, clean tree, report schemas, unique artifact
bindings and hashes, rejects a `matching-*` product tag at the same HEAD, and publishes
`build/lab-evidence/M07/manifest.json` with `productRelease: null`.

## Inherited M06 completion

The M06F1 fixed command history is 8,113 bytes / 65 lines with digest
`sha256:2f9126e7100581020d2a56dd7da4736ab026a7f9533b051bde4490cda210855b`. The M06H1
generated history is 1,670,049 bytes / 10,241 lines with digest
`sha256:b74dd3a6bad6048dcaaceaaeb8fe0c81d1e8d2272d352fe15ea921738f73e6c4`. The ten
M06X1 counterexamples total 22 minimized commands and occupy 3,210 bytes / 23 lines with digest
`sha256:f55d1d7feabe527706a9974dbaf1a894c1420ea6b09bc9e1f7b9563032fca93b`.

M06 separates state transition from book termination. A mode change never clears orders;
`HALTED -> OPEN` is forbidden; and Mass Cancel is accepted only in `HALTED`, where it terminates all
resting orders atomically in global ascending acceptance-sequence order. `OperatorId` is audit
attribution for an upstream-authorized control request, not authorization performed by the matcher.

After committing the completed source and creating annotated `course/m06-complete` at that exact
clean HEAD, evidence is generated with:

```bash
./gradlew m06Evidence -Pm06.unitTag=course/m06-complete --no-daemon
```

The writer reruns `m06Check`, verifies the annotated tag, clean tree, report schemas, and artifact
hashes, rejects a `matching-*` product tag at the same HEAD, and publishes
`build/lab-evidence/M06/manifest.json` with `productRelease: null`.

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

The M08 start ref still keeps exactly these three modules. It creates no runtime, protocol, cluster,
storage, database, Counter, or Rest module; it writes only a structured RED report beneath
`build/reports/m08/`. The later completion may add one `matching-local-runtime` module only after the
start tag is frozen, while `matching-core` remains infrastructure-free. Historical M00–M07 evidence
stays attached to immutable completion tags.

Course dashboard: <https://lcha-reln.github.io/signal-grid-blog/practice/high-availability-cex/>

## License

Apache License 2.0.
