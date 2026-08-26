# Architecture policy validation

This directory refines raw module policies into validated role, cost, dependency, convention, and
effect boundaries.

## Local scope

- `IDE_READ_ONLY` is bounded-read, uses `kast.role.ide-read-only`, and admits inward and same-role
  dependencies. Its finite role ceiling includes `INTELLIJ_PLATFORM`, `UDS_BIND`, and
  `ENDPOINT_DESCRIPTOR_WRITE`; only the IDE plugin policy selects the two endpoint effects.
- Keep policy failures finite and exhaustive. Do not turn a forbidden edge or effect into a
  warning or convenience exception.
