# ADR 0027: Effective agent environment readiness

Status: Accepted

Date: 2026-07-17

## Reason this record remains

Agent readiness composes installation, exact-root process, and compiler
evidence. A reachable process alone is not sufficient proof that semantic work
is safe.

## Decision

Readiness identifies one active CLI installation authority and one managed
Kast indexer for the requested workspace. An eligible healthy indexer is
reused only when its receipt, canonical root, runtime identity, and capabilities
match. Otherwise Kast creates one isolated indexer for that root.

Readiness is read-only. Missing or incompatible evidence produces a typed
failure and a direct repair path. It does not silently install, rewrite, or
accept a second authority.

Agent skills, workspace guidance, and marketplace contents are not readiness
evidence. The active receipt and admitted indexer are.

## Source and proof

- `cli-rs/src/operations/self_mgmt/agent_readiness.rs`
- `cli-rs/src/execution/runtime/backend/workspace_admission.rs`
- `cli-rs/tests/agent/readiness.rs`
- `cli-rs/tests/runtime/semantic_workspace_admission/`

Changes to readiness authority, evidence admission, or repair behavior must
update this record.
