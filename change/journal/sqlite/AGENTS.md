# SQLite change journal guide

This module implements `AddDeclarationPlanJournal` with SQLite. JDBC is its
only architecture effect; it has no IntelliJ, semantic, source-write, or
workspace-transition authority.

## Invariants

- Open only a normalized absolute database path whose parent already exists.
- Register the SQLite driver explicitly for private plugin classloaders.
- Use one strict plan table and one exact recovery child table whose constraints
  make lifecycle, version, approval, PlanId, generation, recovery-image, and
  mutation-progress mismatches fail closed.
- Decode canonical plan bytes and re-prove PlanId and generation on every read.
- State advancement is one SQL compare-and-set over PlanId, prior stage, and
  prior version. Exactly one concurrent approval may win.
- Recovery preparation is one insert-select compare-and-set from the exact
  approved parent. Exactly one concurrent preparation may win, and reopen must
  replay both transitions before returning `RecoveryPrepared`.
- Every operation owns and closes its connection before return. No approval
  wait holds a connection or transaction.
- SQLite stores evidence and lifecycle facts; it never establishes current
  semantic truth.

## Verification

Run `./gradlew :change:journal:sqlite:test`.
