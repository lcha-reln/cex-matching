# M08 frozen inputs

This directory is the immutable `course/m08-start` input boundary.

- `local-wal-durability-v1.json` freezes twenty ordered local-runtime scenarios and twenty-four
  proof obligations.
- `property-suite-v1.json` freezes repository-owned SplitMix64 seed `5808`, 96 histories by 48
  operations, four lanes, ten semantic mutants, and the same twenty-four obligations.

The declared fault model is `CODE_LEVEL_DETERMINISTIC_INJECTION`. The fixtures do not claim real
power-loss, disk-full, read-only-mount, filesystem, replication, Aeron, or high-availability proof.
`PRODUCER_SEQUENCE_STALE` remains a reserved result code without a fabricated direct witness:
strict continuity and no eviction mean a prior active-epoch slot resolves as an exact duplicate or
`SLOT_IDENTITY_CONFLICT` before cursor classification.
