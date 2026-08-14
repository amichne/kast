# Change journal contract guide

This module owns the host-neutral durable add-declaration lifecycle and durable
record types. It carries no JDBC, filesystem, IntelliJ, server, semantic-read,
or source-write authority.

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
- Terminal verification advances only from exact `APPLIED_UNVERIFIED` v4 to `VERIFIED` v5. Its
  receipt retains the full published workspace identity and typed observed declaration identity,
  but the record carries no recovery or physical-apply capability.
- This module defines durable records only. The narrow terminal persistence port and completion
  command belong to `:change:verify:spi`, whose non-publicly issued observation is the authority.

## Verification

Run `./gradlew :change:journal:contract:test`.
