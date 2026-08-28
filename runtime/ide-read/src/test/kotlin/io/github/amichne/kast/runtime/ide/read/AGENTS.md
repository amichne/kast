# Runtime IDE-read test guide

These tests own executable proof for project-scoped read admission and cancellable execution.

- `SingleFlightNegativeTest` proves missing, excessive, forged, repeated, and retained-authority
  states fail closed.
- `SingleFlightTest` proves the exact state transitions, one active/one queued bounds, promotion,
  cancellation, release, and retirement.
- `SingleFlightTransitionEvidence` executes every report transition in canonical order and records
  a claim only after its state, terminalization, and nonmutation assertions pass.
- Use bounded latches for simultaneous admission proof and no sleeps. KVP-020 proves serialized
  state transitions, not IntelliJ execution or scheduling.
- Product controller construction stays private before hosted ownership. `controller` is the sole
  reflection-based test boundary; never add a production installation factory for test convenience.
- `SingleFlightReportFixture` re-admits the full generated schema and exact KVP-014/KVP-019 receipt
  digests. Both canonical selectors must consume that same report boundary.
- `CancellableReadNegativeTest` proves foreign/repeated authority rejection, fail-fast host states,
  write and platform cancellation propagation, concurrent invalidation, defect cleanup, and the
  live adapter's required/forbidden bytecode surface.
- `CancellableReadTest` proves successful exact-once execution, release, queued promotion, and
  stale-value rejection. Its fake port invokes bounded work deterministically; use latches only for
  race coverage and never sleep.

Run both exact selectors before the module check.
