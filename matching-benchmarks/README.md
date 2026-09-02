# M10 benchmark and load method

This module is intentionally outside every production dependency path. It owns two different kinds
of measurements and never combines them:

- `jmhCore` runs exactly two JMH 1.37 `SampleTime` diagnostics: the core matching pair and
  canonical M08 envelope decode. These results never gate the capacity envelope.
- `M10LoadMain` runs scheduled-arrival, open-loop traffic through `LocalMatchingService`, its real
  M08W1 WAL, and M09S1 snapshots. It persists raw offers, completions, latency, queue, and resource
observations before publishing derived percentiles or a knee.

Both qualification profiles use the explicit `M10Q1` recovery policy: 1,000,000 suffix records,
1 GiB of encoded suffix bytes, a 1,024-byte planning ceiling for this workload, and one checkpoint
scheduled 100 ms into every warmup, measurement, and soak phase through the same bounded FIFO. This
is deliberately not the unchanged M09 default of 64 records / 1 MiB. Prefix and post-checkpoint
suffix plans are checked separately before a phase, every new durable record is checked against the
1,024-byte ceiling, and fresh reopen publishes exact suffix records, bytes, and elapsed time. A
30-minute QOP whose planned post-checkpoint suffix exceeds this finite policy fails before the soak;
the runner does not silently add periodic checkpoints or claim the result for M09 defaults.

`CI_SMOKE` is a short check that the method executes. It is always labelled `METHOD_SMOKE_ONLY` and
is ineligible for release evidence. `RELEASE_QUALIFICATION` uses the frozen 20-second calibration,
three complete sweeps, ten-second warmups, thirty-second measurements, and 1,800-second QOP soak.
It requires a complete environment description supplied on the command line; a missing collector
or dimension is an error, never a zero.

The frozen percentile rule is nearest-rank: for sorted `n` samples and quantile `q`, select index
`ceil(q * n) - 1`, clamped to `[0, n - 1]`. End-to-end latency always starts at the planned arrival,
so scheduler delay is retained. Enqueue is not an acknowledgement, and overload stays in offer
accounting instead of becoming a latency sample or a business result.

## Commands and artifacts

`m10CiSmokeLoad` and `m10ReleaseQualification` require the same Gradle properties:

```text
-Pm10.sourceCommit=<full 40-character commit>
-Pm10.walRoot=<new non-evidence directory>
-Pm10.output=<new evidence directory>
-Pm10.cpuModel=<exact model>
-Pm10.storageDevice=<exact device>
-Pm10.filesystem=<exact filesystem>
-Pm10.powerPolicy=<exact policy>
-Pm10.runId=<stable run id>
```

The application can also be called through `gradlew :matching-benchmarks:run --args='...'`.
`qualification.json` is written only after all compressed raw shards have been closed, hashed,
decompressed again, and independently reconciled. Complete records are split at 25,000 records into
`part-NNNNN.jsonl.gz` files under `raw-arrivals`, `raw-completions`, `raw-queue`, `resources`,
`raw-maintenance`, `raw-phase-cuts`, and `accepted-trace`; there is no sampling. Each compressed shard
is inventoried with its record count, byte size, and SHA-256 digest. Arrival generation has its own
thread and never waits for completions, checkpoints, resource collection, or artifact I/O. Raw phase
cuts preserve the scheduled-window observation separately from the later zero-pending drain, while
producer lag and gate-frozen admission queue depth remain independently recomputable. The live
accepted trace is checked three ways:
the live results, duplicate replay from the recovered snapshot plus WAL suffix, and ordered fresh
direct application in a separate directory must all produce the same result transcript and final
semantic digest.

Each phase uses one bounded producer identity. Its sequence advances only when `trySubmit` admits an
offer; `OVERLOADED_BEFORE_WAL` consumes no producer slot, and a checkpoint retry reuses the byte-exact
original envelope. Durable command identities still grow with the finite workload, but the producer
cursor no longer creates one producer map entry per logical operation. Arrival, completion, queue,
and accepted-trace records share `(pointId, logicalOperationId, attempt)`; every arrival/completion
also carries the canonical-envelope SHA-256, every new durable completion carries `walRecordLength`,
and accepted-trace v2 reconstructs the exact binary recovery-trace hash from gzip shards.
