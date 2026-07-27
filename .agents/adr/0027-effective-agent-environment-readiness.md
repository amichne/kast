# ADR 0027: Effective agent environment readiness

Status: Accepted

Date: 2026-07-17

## Reason this record remains

Agent readiness composes installation and backend evidence. A reachable
process alone is not sufficient proof that compiler-backed work is safe.

## Decision

Readiness identifies one active CLI installation authority and one managed
semantic backend for the requested workspace. IDEA evidence is trusted only
when the plugin workspace metadata validates against the active installation;
headless evidence is trusted only when the active receipt names an existing
runtime classpath.

Readiness is read-only. Missing or incompatible evidence produces a typed
failure and a direct repair path; it does not silently install, rewrite, or
accept a second authority.

Codex skills, workspace guidance, and external marketplace contents are not
runtime-readiness evidence. The CLI and selected semantic backend are.

## Source and proof

- `cli-rs/src/operations/self_mgmt/agent_readiness.rs`
- `cli-rs/src/execution/runtime/compatibility.rs`
- `cli-rs/tests/agent_readiness_smoke.rs`
- `cli-rs/tests/runtime_compatibility_metadata_smoke.rs`

Changes to readiness authority, evidence admission, or repair behavior must
update this record.
