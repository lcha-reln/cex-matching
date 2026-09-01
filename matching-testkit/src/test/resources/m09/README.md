# M09 frozen inputs

This directory is the immutable `course/m09-start` input boundary.

- `fixtures/snapshot-recovery-v1.json` freezes 22 ordered snapshot, suffix-recovery, corruption,
  publication, and whole-segment-retirement scenarios.
- `fixtures/property-suite-v1.json` freezes SplitMix64 seed `5909`, 96 histories by 40 operations,
  four lanes, a 64-record/1-MiB suffix budget, seven process-crash windows, eight deterministic
  failure seams, 32 obligations, and 12 required executable fault candidates: nine storage/state
  mutants plus three executable invalid-latest acceptance candidates.

Only `M09S1` is in scope. There is no N-1 fixture or format-evolution claim. Fault evidence is
code-level injection plus child-process halt/file reopen, not real power loss or physical-media
durability.
