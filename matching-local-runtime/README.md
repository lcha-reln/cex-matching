# matching-local-runtime

This module is the JDK-only, single-process, single-shard durability boundary around
`matching-core`. It is deliberately separate from `matching-core`: the deterministic matcher stays
free of files, locks, clocks, networking, databases, threads, and Aeron.

The public runtime owns a fresh private core. Callers submit strict M08C1 journal-ingress bytes; they
cannot obtain the engine and bypass the WAL. A new command follows this order:

```text
canonical/hash/shard validation
-> commandId/Slot/epoch preflight
-> append complete M08W1 record
-> FileChannel.force(true)
-> apply to matching-core
-> cache canonical result
-> NEW_DURABLY_APPLIED
```

The deployment boundary must pre-provision the configured WAL path as a real (non-symlink)
directory and make that directory entry durable before opening the runtime. The runtime refuses a
missing path and never calls `createDirectories`; this keeps ancestor-directory publication outside
the first-record ACK claim. On an existing WAL, startup validates every frame, repairs only an
incomplete final tail, forces the active segment, forces the WAL directory, and only then replays
commands or becomes OPEN. This conservative startup force also resolves a prior write/rename that
was visible to the same host but whose force outcome was unknown.

An exact duplicate returns `DUPLICATE_REPLAYED` with the original WAL/application position and
canonical result. Any ambiguous append, force, or apply window returns `DURABILITY_UNKNOWN` and
leaves the runtime `FAILED_CLOSED`; recovery from genesis decides the outcome.

`semanticStateDigest` is a cumulative M08T1 genesis-replay transcript commitment combined with the
public control/book projection. Every canonical command and exact result is chained into it, so
terminal order identities remain committed even when the resting book is empty. It is deliberately
not a snapshot, checkpoint, or restart shortcut; recovery recomputes it by replaying the WAL from
genesis.

## M10 bounded owner service

`LocalMatchingService` is the last single-process boundary before an Aeron adapter. It owns exactly
one `LocalMatchingRuntime`, one platform worker, and one FIFO configured from 1 through 256 slots.
The M10 qualification configuration freezes 64 slots. The maximum bounds queued ownership to 256
MiB at the one-MiB envelope limit before the owner/runtime working copies. Construction failure at
the queue, service, thread, or thread-start stage closes any runtime and directory lock already
acquired.

`trySubmit` never waits for capacity or business execution. It either transfers an owned copy of
the envelope and returns an `Enqueued` completion handle, or returns a rejection. Queue-full is
checked before cloning and returns `OVERLOADED_BEFORE_WAL`: the rejected envelope never reaches
copy, decode, WAL, identity, or apply. An
enqueue is not a durable acknowledgement. The completion preserves the exact `SubmissionResult`
returned by the runtime, including structural/preflight rejection, `CheckpointRequired`, unknown,
and fail-closed outcomes, or reports an explicit service failure if the worker could not invoke that
boundary.

`CompletionHandle` is callback-free and exposes only `isDone`, interruptible `get`, and timed
interruptible `await`. It does not implement `Future` or `CompletionStage`, has no cancellation or
caller-completion surface, and cannot run a caller continuation on the owner worker. Callers that
need orchestration wait or poll from their own thread; the M10 qualification coordinator polls an
explicitly bounded registry in owner `workSequence` order.

`CheckpointRequired` is never swallowed. A coordinator can order `tryCheckpoint` through the same
bounded FIFO and owner worker, observe its independent completion, then retry the exact envelope as
a new reconciled attempt. Checkpoint admission and accounting are distinct from business offers and
ACKs. Checkpoint or unexpected worker failure closes admission and explicitly completes every
accepted pending item. Deliberate `close` rejects new work first, drains accepted business and
maintenance work, then closes the owned runtime.

`ServiceMetricsSnapshot` exposes current and maximum queue depth, every admission outcome, every
submission-result variant, durable-ACK count, explicit failure count, maintenance accounting, and
reconciliation predicates. The finite queue is the only handoff; there is no secondary unbounded
executor or control queue.

## M07 integration

M08C1 retains `participantGroupId`, raw `stpPolicy`, and optional expected active RuleSet on every
Place command. `MatchingCoreCommandApplier` maps the exact decoded fields directly to
`StpPlaceLimitOrderRequest` or `GovernedStpPlaceLimitOrderRequest`; legacy `0/NONE` still uses the
historical M04/M05 entrypoints. Structurally canonical but business-invalid STP instructions are
journaled before matching-core emits their deterministic rejection, so restart rebuilds the same
application sequence and result.

## Honest boundary

The deterministic `FaultInjector` distinguishes injected failures immediately before an operation
from crash windows after a successful operation. A generic injected `IOException` is not evidence
of a specific ENOSPC, read-only-filesystem, or device failure. These seams and child-process checks
do not prove physical durability under real power loss. This module does not implement replication,
Aeron, quorum/failover, database dual writes, multiple shards, group commit, upgrade compatibility,
portable performance SLOs, or automatic corruption repair. The bounded local service and finite
machine qualification do not by themselves establish production high availability.
