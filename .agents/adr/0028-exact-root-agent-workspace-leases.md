# ADR 0028: Exact-root agent workspace leases

Status: Accepted

Date: 2026-07-17

## Reason this record remains

Lease cleanup controls live processes. It must preserve the difference between
an indexer Kast started and one it borrowed.

## Decision

Private `kastctl agent lease acquire`, `status`, and `release` operate on one
canonical workspace root and indexer. Acquisition requires an exact `READY`
indexer and binds an authenticated lease to the active installation generation,
runtime descriptor, process identity, owner process identity, and workspace.
Public `kast apply` requires the returned opaque lease through `--lease-id`.

Only one live lease exists for an exact root and indexer identity. PID alone is
not identity; process-start evidence prevents reuse mistakes. No expiry or
heartbeat is inferred.

A released or recovered lease stops a process only when the lease started it
and the current descriptor and process identity still match. A borrowed
indexer remains running. Release is idempotent and records its reason.

Tampered, wrong-root, stale-generation, abandoned-owner, and replaced-runtime
states remain distinct. No state falls back to a different workspace or
process.

## Source and proof

- `cli-rs/src/execution/runtime/control/lease.rs`
- `cli-rs/src/execution/runtime/backend/workspace_admission.rs`
- `cli-rs/src/agent/core/dispatch/mod.rs`
- `cli-rs/tests/runtime/workspace_lease/`

Changes to ownership, exact-root binding, liveness, recovery, or stop behavior
must update this record.
