# Cancellable project-read executor owner

This package joins KVP-020 permit authority to the admitted IDEA 262 read boundary for KVP-021.

- `CancellableProjectReadExecutor` is the sole project-level owner. Its public surface may bind an
  admitted Project and freshness capability but must expose no IntelliJ platform type.
- Refine Active permits into `ExecutingProjectRead` before invoking the live port. Foreign,
  executing, and terminal permits must reject before work starts.
- Release on completed or typed host rejection. Cancel before rethrowing the exact
  `ProcessCanceledException`. Release before propagating any other defect.
- Discard any value produced after concurrent cancellation or retirement; only an exact successful
  terminalization may produce Completed.
- Keep operation semantics out of this package. The internal Project-bearing computation exists
  only for later operation-specific adapters.
- KVP-022 may retain the admitted Project's epoch-observer capability, but it must observe only
  after permit execution admission and keep the queue barrier until AFTER revalidation completes.

Run the exact `CancellableReadNegativeTest` and `CancellableReadTest` selectors.
