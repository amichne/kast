# ADR 0028: Exact-root agent workspace leases

Status: Accepted

Date: 2026-07-17

## Reason this record remains

Lease cleanup controls live processes. It must preserve the difference between
an indexer Kast started and one it borrowed.

## Decision

Lease acquisition and release are internal capabilities with no callable CLI
surface. Semantic demand acquires the capability required by the operation and
binds it to the active installation generation, immutable runtime epoch,
process identity, socket identity, and exact workspace. Public mutation
operations consume that internal capability. A caller cannot supply, inspect,
or release a lease identifier.

Only one live lease exists for an exact root and indexer identity. PID alone is
not identity; process-start evidence prevents reuse mistakes. No expiry or
heartbeat is inferred.

Operations performed under a mutation lease acquire authenticated runtime
request capabilities; server-held continuations acquire continuation
capabilities in the same runtime registry. Releasing or recovering the client
mutation lease cannot stop the runtime. Only after every request and
continuation capability ends does the fixed five-minute grace begin. Its
one-shot stop permit is admissible only while the runtime epoch, registration,
descriptor, process, and socket identities still match. New semantic demand
supersedes the permit.

Public apply and recovery also hold one operating-system-backed exclusive lock
for the exact plan. The private `0600` lock file remains durable, while process
exit releases its lock. Concurrent `apply` and `recover` processes cannot both
submit or restore one plan.

For `rename`, `replace`, `add-file`, and `add-declaration`, apply revalidates the
persisted compiler authority under its owned lease. It observes every exact
preimage, captures complete compiler diagnostics for affected existing files,
and then persists a private `0600` exact-root recovery journal before the first
write. The journal contains a path-sorted set of absent or byte-exact present
preimages and byte-exact postimages.

Before any backend write, the journal also declares every scratch role that the
write can use. Each scratch file is in the target parent. Its name binds the
journal owner attempt and the global transition index. Inspection admits one
active mutation attempt, and the backend serializes that admission with the
complete write callback. A stale or queued attempt cannot write after a newer
attempt is admitted.

Recovery starts with a fresh active attempt but keeps the journal owner in the
scratch names. It supplies the exact journal paths and the closed scratch
direction. Only those paths can authorize restoration or finalization. An
unowned internal name, wrong hash, duplicate role state, or foreign target
image blocks recovery before any write.

Apply performs only journal-backed exact compare-and-swap or create operations.
It then observes every postimage, refreshes every affected file, rejects a
positive compiler-error multiset delta, verifies the operation-specific
compiler postcondition, and persists durable verified evidence before it
releases the lease and stores the terminal receipt.

`kast recover <RECOVERY_ID>` acquires a new exact-root lease and classifies the
whole transition set. An exact all-pre state becomes `ROLLED_BACK`. An exact
all-post state is verified again or restored in reverse order. A mixed state is
restored in reverse order. A foreign image remains `RECOVERY_REQUIRED` without
another write. Recovery never resubmits an ambiguous mutation, and it reports
`ROLLED_BACK` only after exact observation proves every preimage.

Stored terminal receipts are the closed set `VERIFIED`, `REJECTED`,
`CONFLICTED`, and `ROLLED_BACK`. `RECOVERY_REQUIRED` is an output-only direction
to run recovery; it cannot be stored as a terminal replay state.

Tampered, wrong-root, stale-generation, abandoned-owner, and replaced-runtime
states remain distinct. No state falls back to a different workspace or
process.

## Source and proof

- `cli-rs/src/execution/runtime/control/lease.rs`
- `cli-rs/src/execution/runtime/backend/workspace_admission.rs`
- `cli-rs/src/agent/core/dispatch/mod.rs`
- `cli-rs/src/agent/plan/`
- `cli-rs/tests/runtime/workspace_lease/`
- `cli-rs/tests/agent/public/kast_public_operations.rs`

Changes to ownership, exact-root binding, liveness, recovery, or stop behavior
must update this record.
