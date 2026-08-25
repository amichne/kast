# Installed integration-test guide

This directory owns installed-product acceptance against staged distributions and real external
process boundaries. Tests here may create isolated temporary workspaces and runtime stores; they
must not depend on developer checkout state or control a foreground IDE.

`codex-app-server-evaluation/` separately owns the operator-invoked, pre-production enterprise
evaluation for Codex dynamic tools. It may install the current checkout and target an explicitly
approved external repository, but it must not become part of deterministic `enterpriseAcceptance`
or CI because it requires Codex authentication and a live model turn.

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
4. Run the focused Python tests and one retained dynamic-only evaluation after changing the Codex
   App Server evaluation path.
