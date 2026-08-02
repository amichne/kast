# ADR 0028: Exact-root agent workspace leases

Status: Accepted

Date: 2026-07-17

## Reason this record remains

Lease cleanup controls live processes and must preserve the difference between
a runtime Kast started and one it merely borrowed.

## Decision

Private `kastctl agent lease acquire`, `status`, and `release` operate on one
canonical workspace root and headless runtime. Acquisition requires an exact
`READY` runtime and binds an authenticated lease to the active installation
generation, runtime descriptor and process identity, owner process identity,
and workspace. Public `kast apply` requires the returned opaque lease through
`--lease-id`.

Only one live lease exists for an exact root/backend. PID alone is not
identity; process-start evidence prevents reuse mistakes. No expiry or
heartbeat is inferred.

A released or recovered lease stops a runtime only when the lease started it
and the current descriptor and process identity still match. Borrowed headless
runtimes remain running. Release is idempotent and records its reason.

Tampered, wrong-root, wrong-backend, stale-generation, abandoned-owner, and
replaced-runtime states remain distinct. No state falls back to a different
workspace or runtime.

## Source and proof

- `cli-rs/src/execution/runtime/control/lease.rs`
- `cli-rs/src/execution/runtime/backend/workspace_admission.rs`
- `cli-rs/src/agent/core/dispatch/mod.rs`
- `cli-rs/tests/workspace_lease_smoke.rs`

Changes to ownership, exact-root binding, liveness, recovery, or stop behavior
must update this record.
