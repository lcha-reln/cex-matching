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
taker-owned self-trade-prevention instruction. M08 completes the first local durability axis: a
caller-serialized, single-shard M08C1/M08W1 runtime with durable bidirectional identity, genesis
recovery, and an ACK that may occur only after append, force, and matching apply. M09 completes the
next local-storage axis: one `M09S1` snapshot, a 64-record / 1-MiB WAL-suffix recovery budget, and
whole-segment retirement after durable snapshot publication. M10 completes the last local-runtime
axis: a configurable bounded non-blocking admission service, measured at capacity 64, plus an honest
machine-specific performance qualification and independently verified raw evidence pipeline.
M11 is now frozen at its start boundary: it will add one real single-member Aeron Cluster adapter,
versioned ingress/response codecs, Cluster snapshot/restart, and direct-versus-Cluster semantic
equivalence. No Aeron module or implementation is present yet, and no high-availability claim is
made.

## Current course boundary

- Profile: `SPOT-CEX-1.0`
- Plan version: `0.14`
- Unit: `M11`
- Declared start ref: `course/m11-start`
- Declared complete ref: `course/m11-complete`
- Product release: `matching-0.5.0` at the M10 completion ref
- Lifecycle at this boundary: `READY / CONTRACT / GOAL_NOT_IMPLEMENTED`
- Java toolchain: 25 LTS
- Gradle Wrapper: 9.7.1 with a pinned distribution checksum

The Gradle Daemon JVM criteria and Java toolchain both require an Adoptium JDK 25. If it is absent,
the configured Foojay resolver can provision it locally before the build; `.java-version` also
records the major version for compatible JDK managers. CI uses Temurin 25.

The default root build deliberately remains the green M10 gate and reruns every inherited boundary:

```bash
./gradlew clean build --no-daemon
```

The explicit M10 command writes a schema-valid completion report:

```bash
./gradlew m10Check --no-daemon
# M10 check status: PASS
```

The explicit M11 command validates the content-addressed contract and six binary goldens, writes
`build/reports/m11/check.json`, then intentionally exits 1 because the Aeron implementation does
not exist at the start ref:

```bash
./gradlew m11Check --no-daemon
# M11 check status: GOAL_NOT_IMPLEMENTED
```

M11 freezes 22 fixed scenarios, SplitMix64 seed `6111`, one continuous 32-segment-by-128-action
corpus (not 32 fresh-state histories), four equal lane-major groups,
and three complete comparison paths: Direct plus two real Cluster runs of 4,096 actions each. The
application sequence and producer cursors continue across segment boundaries. The second Cluster
run performs a controlled snapshot/restart after global action 2,048, for 8,192 actual
Cluster ingress actions in total. `AdminResponseCode.OK` is only request acceptance; shutdown must
wait for the snapshot counter, neutral control toggle, new consensus (`-1`) and service (`0`)
Recording Log entries at one term/position with new recording IDs, and a payload digest that the
restarted service proves it actually loaded. The contract also freezes 28 proof obligations, ten
semantic mutants, three `SYSTEM_ERROR` controls, and five tutorial permalinks. Request, response,
and snapshot application formats have current version 2 with minimum readable version 1. Request
v1 always selects response v1; request v2 may select response v1 or v2, and every valid business
outcome down-encodes to v1. Response v2 echoes only `commandId`, not the full durable identity.
The two-binding snapshot goldens order identity results by strict original application sequence.
S1 already contains the complete identity/original-result table; S2 only adds protocol bounds and integrity
fields, so an N-1 restore may not lose idempotency. The exact stop point and exclusions are in
[`docs/specs/m11.md`](docs/specs/m11.md).

The start contract freezes 20 admission and methodology scenarios, SplitMix64 seed `6010`, 64
histories by 256 actions across four lanes, 28 proof obligations, 12 executable mutant IDs, and five
tutorial permalinks. The release profile uses open-loop scheduled arrivals, three complete sweeps,
p50/p95/p99/p99.9, deterministic knee selection, and a 70%-of-knee candidate narrowed to a
strictly descending list of all-sweep-unsaturated measured rates. Each provisional rate receives a
complete 1,800-second soak until the first full-duration PASS becomes the QOP; saturated higher
attempts remain evidence, while a system error stops the run. JMH `SampleTime` remains diagnostic.
`CI_SMOKE` is short and explicitly
ineligible for release evidence. The exact contract is in [`docs/specs/m10.md`](docs/specs/m10.md).
Every admitted item must pass through exactly one unchanged local-runtime `SubmissionResult` or an
explicit service failure; only the durable result variants are ACKs, and enqueue is never an ACK.
The public completion handle is callback-free and non-cancellable, so caller continuations cannot
capture the single owner worker; configured queue capacity is explicitly bounded from 1 through 256.

The qualification does not pretend to measure the unchanged M09 default of 64 suffix records / 1
MiB. M10Q2 retains the M10Q1 finite 1,000,000-record / 1-GiB suffix budget, a
proactive same-FIFO checkpoint admitted no later than 110 ms into each scheduled phase, strict
producer-lag gates with a bounded closure grace, and an immutable scheduled-window observation cut
that is preserved separately from both late admission decisions and the later zero-pending drain.
The default and qualification settings both remain visible in every eligible report; an `M10Q2`
capacity envelope is not evidence for the M09 default.

Ordinary CI proves only that the method and contracts execute. It does not publish CI-run throughput
as the `matching-0.5.0` capacity claim. Product evidence comes from the full release profile,
raw samples, resource series, environment fingerprint, load-then-recovery equality, clean source,
and both annotated release refs at one commit.

## Inherited M09 completion

The M09 completion judge compares the snapshot runtime with a retained-genesis-WAL runtime and a
separate no-I/O storage ledger; it does not claim a third independent implementation of all
inherited matching semantics.

The frozen contract executes 22 fixed scenarios with 88 declared fixture tokens, seed `5909`, 96
histories by 40 operations across four lanes, 32 obligation witnesses, seven child-process
`Runtime.halt(86)` windows, eight deterministic failure seams, and 12 executable candidates: nine
storage/state mutants plus three
invalid-latest acceptance candidates. Unknown snapshot versions fail closed; M09 has no N-1
fixture or format-evolution claim. `SYSTEM_ERROR` and `INVALID_HISTORY` never count as a kill. The
exact contract and evidence limits are in [`docs/specs/m09.md`](docs/specs/m09.md).

The generated suite uses two fresh directories and byte-exact regeneration. Its `CRASH` operation
is a controlled forced-durable unknown-outcome/reopen path, not child-process crash evidence; only
the seven explicit child JVM histories carry the process-halt claim. The independent ledger checks
the configured suffix budget and exact whole-segment inventory without parsing production WAL or
Snapshot bytes. Both candidate and retained-genesis runtimes still use the production WAL parser
and inherited matching core.

The eight deterministic failures are injected at declared pre-operation hooks; they do not claim
independent observation that the underlying operation was absent. Child halts bind their declared
hooks to the observed namespace and fresh reopen, not to an independently traced JDK call. Actual
file-force, move, directory-force, and delete order is established separately by the fixed suite's
real-JDK `StorageOperations` trace.

The 3,840 declared generated operations do not absorb setup traffic. History zero first executes a
separate 65-operation budget prelude. The independent ledger predicts only fresh append candidates
and their checkpoint-required retries; duplicate, conflict, snapshot, and restart operations are
not padded into the prediction count.

M09 retirement evidence proves runtime-created non-terminal missing-prefix gaps fail closed and
that active or crossing segments are retained. It explicitly does not prove detection of an
externally deleted final active segment. The fixed multi-segment suffix mechanism uses a test-only
4-MiB byte budget to create a crossing suffix; the production default remains 64 records / 1 MiB.

The M09 fixed canonical digest is
`sha256:1636ed177f59347ec11b8e9ffe1fb6d872fd3de5225298381a161a0b7d755f43`. The
generated canonical digest is
`sha256:9551ad7a3026964b57b366e39d6307510789cd83c750bf239098f9ba299354e5`; its
3,840 declared operations and separate 65-operation setup produce 4,225 ledger checks, 1,366 exact
inventory checks, 544 fresh restarts, 822 snapshots, and one automatic checkpoint. The ledger makes
2,703 budget predictions: 2,702 accepts and one reject, with one checkpoint-required witness. The
12 minimized counterexamples contain 64 operations after 152 shrink trials, execute 13 real
mutation actions, and have digest
`sha256:0dd88e0ced4a35dab53f357a657c299484eabeeb6111cd70221603a971f0a3eb`.

After committing the completed source and creating annotated `course/m09-complete` at that exact
clean HEAD, evidence is generated with:

```bash
./gradlew m09Evidence -Pm09.unitTag=course/m09-complete --no-daemon
```

The writer reruns `m09Check`, rejects a dirty tree, symlinked output components, a non-annotated or
wrong-HEAD unit tag, and a `matching-*` product tag. It repeats that full release-state check before
and after atomic publication. Every copied input and report is bound exactly once by SHA-256 in
`build/lab-evidence/M09/manifest.json`; `productRelease` remains `null`.

## Inherited M08 completion

`m08Check` first reruns the inherited M07 semantic judge. It then executes 20 fixed scenarios and
two byte-exact generations of seed `5808` across 96 histories by 48 boundaries. The frozen weighted
profile contributes 1,152 operations (732 submit, 111 duplicate, 104 conflict, 92 restart, 57
rollover, and 56 fault), while the full corpus includes 192 structurally invalid envelopes and 576
durably journaled business rejections. An independent identity model and third durability ledger
check all 4,608 boundaries; all 24 obligations have concrete witnesses.

The completion gate also executes seven `BEFORE_OPERATION` failure histories, three child JVM
`Runtime.halt(86)` file/reopen smokes, and ten executable runtime/file mutants. All ten mutants are
killed as `STUDENT_FAILURE`, with 13 real mutation actions and 56 one-minimal high-level history
tokens that preserve their required submit/close/restart/retry grammar. The throwing control remains
`SYSTEM_ERROR` and never counts as a kill. The strict `matching.m08.check.v2` report is written under
`build/reports/m08/`; the exact contract is in [`docs/specs/m08.md`](docs/specs/m08.md).

The fixed canonical digest is
`sha256:444e999094bc58aabed7869df60a07a019de9969f8bd39318edc3c0590527472`.
The generated history digest is
`sha256:56a2d7f63df96737c286bab5c96a16aa50e0dd33df58f9cefc9d7abee5aaff41`,
and the M08X2 counterexample digest is
`sha256:9608baeba56ba525e6eeba5c33d9f6368b72c8f68e22b5e7f0c6fdf768d9566a`.

After committing the completed source and creating annotated `course/m08-complete` at that exact
clean HEAD, evidence is generated with:

```bash
./gradlew m08Evidence -Pm08.unitTag=course/m08-complete --no-daemon
```

The writer reruns the complete judge, verifies the annotated tag, clean tree, strict report schemas,
unique artifact bindings and hashes, rejects a `matching-*` product tag at the same HEAD, and
publishes `build/lab-evidence/M08/manifest.json` with `productRelease: null`.

This is finite local-process evidence. The deployment or test harness must pre-provision an existing
real non-symlink WAL directory and durably publish its ancestor directory entry before opening the
runtime. Named ENOSPC/read-only observations are deterministic `FileSystemException` injections
with `actualFilesystem=false`; the child JVM checks are process-crash smokes, not real disk-full,
read-only-mount, physical-media, or power-loss qualification. M08 has no Snapshot, retention,
replication, Aeron, failover, multi-shard routing, group commit, or production-readiness claim.

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
matching-local-runtime caller-serialized M08C1/M08W1 journal plus M09S1 snapshot recovery; JDK + core only
matching-testkit    generators, differential judge, replay, mutants, and evidence tooling
```

M08 introduced the `matching-local-runtime` production module; M09 extends that same module with
snapshot publication, bounded suffix recovery, and whole-segment retirement. The core stays
infrastructure-free, and the runtime has no matching-reference, testkit, network, database, Aeron,
cluster, Counter, or Rest dependency. The public mutation bridge is compiled only in
`matching-testkit` and is absent from matching-local-runtime production sources and dependencies.
Historical M00–M08 evidence stays attached to immutable completion tags.

Course dashboard: <https://lcha-reln.github.io/signal-grid-blog/practice/high-availability-cex/>

## License

Apache License 2.0.
