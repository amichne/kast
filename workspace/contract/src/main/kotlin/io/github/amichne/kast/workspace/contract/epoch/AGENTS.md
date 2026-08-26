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

## Focused proof

Run:

```shell
./gradlew :workspace:contract:test --tests '*ProjectReadEpochNegativeTest'
./gradlew :workspace:contract:test --tests '*ProjectReadEpochTest'
```
