# Change recovery service guide

`:change:recovery` owns the host-neutral recovery state machine for clean-slate mutations. It
binds exact change-plan identity to generic durable evidence and coordinates a narrow rollback
port. It performs no physical mutation or persistence.

## Dependency boundary

- Production depends only on `:change:contract` and `:evidence:contract`.
- Do not import IntelliJ, JDBC, SQLite, filesystem effects, workspace services, verification,
  transport, legacy journal, or callback types.
- A rollback port is a supplied capability; this module never constructs a physical adapter.

## Invariants

- Pre-write evidence contains the exact plan binding, source identity, and hash-verified before
  image before an applied write can be recorded.
- Recovery from an applied write persists exactly `RolledBack` or `RecoveryRequired` before
  returning that outcome.
- Absent or pre-write-only evidence means prior state, never mutation success.
- Corrupt or unavailable evidence fails closed as `RecoveryRequired`.

## Verification

Run `./gradlew :change:recovery:test`.
