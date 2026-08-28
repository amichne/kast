# Workspace epoch contract guide

This directory owns detached canonical-root lease evidence and the opaque project-read epoch
contract. It contains no IntelliJ observation implementation.

## Invariants

- `ProjectReadEpoch` retains an adapter-private immutable state and exposes no signal value,
  primitive counter, parser, copier, validity Boolean, or source authority.
- Epochs are `SAME` only when the exact same source instance issued equal states. Different
  Project/runtime sources are `INCOMPARABLE`; changed state from one source is `MOVED`.
- A concrete `ProjectReadEpoch.Source` remains private to the adapter that owns the admitted
  Project/runtime. Callers receive only `ProjectReadEpochObservation`.
- Dumb mode, lifecycle loss, malformed platform observations, exhausted counters, and read
  preemption are finite failures. Dumb mode is never retained as an epoch value.
- Platform cancellation remains platform cancellation; this contract does not turn it into an
  epoch or manufacture a successful observation.
- `VfsPassiveReadCapability` preserves one strong canonical root and current same-source epoch.
  Its constructor and issue transition remain compiler-confined to the friend hosted adapter;
  callers cannot parse, copy, or mint the proof.
- KVP-019 freshness admission distinguishes moved, incomparable, disposed, and dumb states while
  retaining every other epoch observation failure as exact `Unavailable` data. KVP-020 alone owns
  queue admission and `Busy`.

## Focused proof

Run:

```shell
./gradlew :workspace:contract:test --tests '*ProjectReadEpochNegativeTest'
./gradlew :workspace:contract:test --tests '*ProjectReadEpochTest'
```
