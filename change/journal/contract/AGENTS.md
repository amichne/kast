# Change journal contract guide

This module owns the host-neutral durable add-declaration plan lifecycle and
the journal port. It depends only on the detached change contract and carries
no JDBC, filesystem, IntelliJ, server, or source-write authority.

## Invariants

- Stored plans are complete `PlannedAddDeclaration` values whose canonical
  bytes and PlanId are revalidated at every persistence read boundary.
- Lifecycle is closed data. KIP-033 admits `AwaitingApproval`, `Approved`,
  and `RecoveryPreparedAddDeclaration`; later nodes must extend the state
  machine rather than add flags, nullable control state, or string protocols.
- Approval evidence is explicit, canonical, PlanId-bound, and separate from
  transport success. Advancing state requires the exact prior stage and
  version.
- Recovery preparation carries the exact target and byte-for-byte before image,
  advances only from the revalidated approved record, and proves mutation has
  not begun. It grants no source-write capability.
- Journal failures are finite typed data. Storage never decides semantic truth
  and callers never submit reconstructed plan fields.
- Port calls retain no transaction, connection, live IDE value, lease, or
  closure after return.

## Verification

Run `./gradlew :change:journal:contract:test`.
