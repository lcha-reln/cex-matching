# matching-local-runtime (M08 preparation)

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

## M07 integration point

M08C1 already reserves `participantGroupId` and `stpPolicy` on every Place command. The current
source baseline has M06 core types, so the production adapter only applies the legacy `0/NONE`
mapping. Other STP values are rejected before WAL append as `UNSUPPORTED_COMMAND`, preventing a
durable poison record.

After the M07 core types are cherry-picked, update the package-private `StpPlaceExtension` /
`MatchingCoreCommandApplier` seam to construct the real M07 request. The M08C1 bytes, payload hash,
identity rules, and WAL format must not change.

## Honest boundary

The deterministic `FaultInjector` distinguishes injected failures immediately before an operation
from crash windows after a successful operation. A generic injected `IOException` is not evidence
of a specific ENOSPC, read-only-filesystem, or device failure. These seams and child-process checks
do not prove physical durability under real power loss. This module does not implement snapshots, retention, bounded
recovery, replication, Aeron, quorum/failover, database dual writes, multiple shards, group commit,
upgrade compatibility, performance SLOs, or automatic corruption repair. It is not an M08 evidence
bundle and does not by itself establish production high availability.
