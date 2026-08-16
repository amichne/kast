# SQLite change journal guide

This module implements `AddDeclarationPlanJournal` with SQLite. JDBC is its
only architecture effect; it has no IntelliJ, semantic, source-write, or
workspace-transition authority.

## Invariants

- Open only a normalized absolute database path whose parent already exists.
- Register the SQLite driver explicitly for private plugin classloaders.
- Keep the KIP-032 plan table compatible and add one strict recovery child
  table whose constraints make lifecycle, version, PlanId, exact before-image,
  and mutation-progress mismatches fail closed.
- Decode canonical plan bytes and re-prove PlanId and generation on every read.
- State advancement is one SQL compare-and-set over PlanId, prior stage, and
  prior version. Exactly one concurrent approval or recovery preparation may
  win.
- Every operation owns and closes its connection before return. No approval
  wait holds a connection or transaction.
- SQLite stores evidence and lifecycle facts; it never establishes current
  semantic truth.

## Verification

Run `./gradlew :change:journal:sqlite:test`.
