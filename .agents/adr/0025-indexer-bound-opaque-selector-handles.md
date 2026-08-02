# ADR 0025: Indexer-bound opaque selector handles

Status: Accepted

Date: 2026-07-16

## Reason this record remains

Selector handles cross the CLI and indexer boundary. They carry
security-sensitive identity claims that are not safely inferred from an
individual serializer or call site.

## Decision

An exact compiler-backed resolution may issue an opaque `ksh1.` selector
handle. Clients may store and return the handle, but they must not decode,
alter, or derive one from the readable symbol identity.

The issuing indexer binds the handle to the canonical workspace, indexer
instance, semantic generation, exact declaration selector, and allowed
operation families. It authenticates those claims before semantic work. A
handle is an identity proof, not an authorization or idempotency key.

Commands accept either one handle or explicit selector fields, never both.
Explicit selectors remain supported. A rejected handle never falls back to
fuzzy or fully qualified name lookup.

Relationship page tokens and mutation idempotency keys remain separate values
with separate lifetimes.

## Source and proof

- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/contract/selector/`
- `analysis-server/src/main/kotlin/io/github/amichne/kast/server/skill/SelectorAuthority.kt`
- `indexer/src/main/kotlin/`
- `cli-rs/src/agent/`
- `cli-rs/tests/agent/selector_handle_installed_workflow.rs`

Any change to the handle prefix, authenticated claims, validation order,
selector exclusivity, or fallback behavior must update this record.
