# Change journal contract guide

This module owns the host-neutral durable add-declaration plan lifecycle and
the journal port. It depends only on the detached change contract and carries
no JDBC, filesystem, IntelliJ, server, or source-write authority.

## Invariants

- Stored plans are complete `PlannedAddDeclaration` values whose canonical
  bytes and PlanId are revalidated at every persistence read boundary.
- Lifecycle is closed data. KIP-032 admits `AwaitingApproval` and `Approved`;
  KIP-033 adds `RecoveryPrepared` only after matching revalidation and an exact
  prior-state/version transition. Later nodes must extend the state machine
  rather than add flags, nullable control state, or string protocols.
- Approval evidence is explicit, canonical, PlanId-bound, and separate from
  transport success. Advancing state requires the exact prior stage and
  version.
- Journal failures are finite typed data. Storage never decides semantic truth
  and callers never submit reconstructed plan fields.
- Port calls retain no transaction, connection, live IDE value, lease, or
  closure after return.
- Recovery preparation retains exact before-image material and explicit
  `NOT_BEGUN`; it exposes no source-write or rollback authority.

## Verification

Run `./gradlew :change:journal:contract:test`.
