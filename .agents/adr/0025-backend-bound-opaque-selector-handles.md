# ADR 0025: Backend-bound opaque selector handles

Status: Accepted

Date: 2026-07-16

## Reason this record remains

Selector handles cross the CLI/backend boundary and carry security-sensitive
identity claims. Their trust model is not safely inferred from individual
serializers or call sites.

## Decision

An exact compiler-backed resolution may issue an opaque `ksh1.` selector
handle. Clients may store and return it but must not decode, alter, or derive
one from the readable symbol identity.

The issuing backend binds the handle to the canonical workspace, backend
instance, semantic generation, exact declaration selector, and allowed
operation families. It authenticates those claims before semantic work. A
handle is an identity proof, not an authorization or idempotency key.

Commands accept either one handle or their explicit selector fields, never
both. Explicit selectors remain supported. A rejected handle never falls back
to fuzzy or fully qualified name lookup.

Rejections are closed and actionable:

- `TAMPERED`
- `WRONG_WORKSPACE`
- `WRONG_BACKEND`
- `STALE`
- `FAMILY_NOT_ALLOWED`
- `UNAVAILABLE`

Relationship page tokens and mutation idempotency keys remain separate values
with separate lifetimes.

## Source and proof

- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/contract/selector/`
- `analysis-server/src/main/kotlin/io/github/amichne/kast/server/SkillRpcOrchestrator.kt`
- `backend-idea/src/main/kotlin/io/github/amichne/kast/idea/backend/KastPluginBackend.kt`
- `cli-rs/src/agent/`
- `cli-rs/tests/agent_relationship_navigation_smoke.rs`
- `cli-rs/tests/selector_handle_installed_workflow.rs`

Any change to the handle prefix, authenticated claims, validation order,
selector exclusivity, or fallback behavior must update this record.
