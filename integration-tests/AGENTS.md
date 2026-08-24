# Installed integration-test guide

This directory owns installed-product acceptance against staged distributions and real external
process boundaries. Tests here may create isolated temporary workspaces and runtime stores; they
must not depend on developer checkout state or control a foreground IDE.

## Invariants

- Exercise only the staged public executable and its documented environment boundaries.
- Use the public lifecycle command for behavior under test. Direct process termination is reserved
  for bounded `finally` cleanup of a test-owned workspace.
- Prove restart reuse after initial publication and before mutation in the enterprise workspace.
  This ordering keeps restart evidence uncontaminated without paying for a redundant cold indexer.
- Enterprise topology acceptance must cover real K2 publication, SQLite-backed graph reads, clean
  stop, restart reuse, bounded output, and the single measured elapsed-time budget.
- Expected failure is asserted from structured JSON outcomes and exact exit codes.

## Verification

1. Run `./gradlew enterpriseAcceptance` after enterprise fixture or topology lifecycle changes.
2. Run the owning installed-product shell contract after CLI or distribution changes.
3. Run `./gradlew build` after packaging changes.
