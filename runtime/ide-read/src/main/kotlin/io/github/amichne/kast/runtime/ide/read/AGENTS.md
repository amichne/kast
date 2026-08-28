# Single-flight read admission owner

This package owns the KVP-020 project-scoped permit state machine.

- `ProjectReadSingleFlight` is the sole mutable boundary. Its private constructor stays closed until
  the hosted project-level owner is introduced. Do not add a reusable runtime installation factory.
- Its private lock and state belong to one canonical root; the transition surface is finite and
  contains no collection or callback.
- `ProjectReadPermit` and `QueuedProjectReadRequest` are final private-constructor handles that
  expose opaque identity only. They retain freshness and lifecycle inside the lexical controller;
  no caller may mint, terminalize, or extract evidence from either handle.
- Keep this package free of IntelliJ, coroutine, channel, executor, time, random, and I/O APIs.
- KVP-021 must ask the owning controller to validate active authority at the execution boundary; it
  must not weaken or duplicate KVP-020 admission or project freshness.
- `execution/` owns the later hosted join. This parent package adds only the host-neutral
  Active-to-Executing refinement and exact terminalization used by that child.
