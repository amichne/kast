# Architecture policy validation

This directory refines raw module policies into validated role, cost, dependency, convention, and
effect boundaries.

## Local scope

- `IDE_READ_ONLY` is bounded-read, uses `kast.role.ide-read-only`, admits inward and same-role
  dependencies, and permits only `INTELLIJ_PLATFORM` reads.
- Keep policy failures finite and exhaustive. Do not turn a forbidden edge or effect into a
  warning or convenience exception.
