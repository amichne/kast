# Architecture policy validation

This directory refines raw module policies into validated role, cost, dependency, convention, and
effect boundaries.

## Local scope

- `IDE_READ_ONLY` is bounded-read, uses `kast.role.ide-read-only`, and admits inward, same-role,
  and existing `INTELLIJ_READ_ADAPTER` dependencies. The latter is the production native-index
  composition edge; it grants no write, import, refresh, or process authority. Its finite role
  ceiling includes `INTELLIJ_PLATFORM`, `UDS_BIND`, and `ENDPOINT_DESCRIPTOR_WRITE`; only the IDE
  plugin policy selects the two endpoint effects.
- Keep policy failures finite and exhaustive. Do not turn a forbidden edge or effect into a
  warning or convenience exception.
- `NoDefaultRuntimeFallback.kt` admits the transitive installed-composition class closure and owns
  the five KVP-027 forbidden fallback mappings plus their closed report codec. Its synthetic
  misuse remains in the Gradle adapter; this owner contains no filesystem effects.
