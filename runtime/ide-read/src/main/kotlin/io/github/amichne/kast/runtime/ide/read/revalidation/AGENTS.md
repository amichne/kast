# Epoch-revalidation owner

This package owns KVP-022's proof transition from one permit-scoped detached read to a result that
is accepted only after exact retained-source epoch equality.

- Observe once after permit execution admission and once after the detached projection is built.
- Only `SAME` may construct `RevalidatedIdeReadResult.Complete`.
- Preserve `MOVED`, `INCOMPARABLE`, observation phase and cause as closed outcomes.
- `DetachedIdeReadProjection` is the sole nominal boundary for a value leaving the live Project
  read. Its raw value may be extracted only by the later operation adapter.
- Never retry or reuse a prior epoch after rejection or cancellation.

Run the exact `EpochRevalidationNegativeTest` and `EpochRevalidationTest` selectors.
