# ADR 0031: Shared path-coordinated agent tasks

## Status

Accepted. This decision supersedes ADR 0030's session-owned task lease and
immutable completion-proof model. It also narrows ADR 0015's mutation
scheduling contract by defining conflict-FIFO path admission.

Date: 2026-07-19.

## Context

Session ownership made recovery depend on provider identity even though
multiple agents can legitimately contribute to one exact worktree. Persisting
per-file baselines, diagnostic hashes, Gradle evidence, test-report digests,
and immutable completion archives made routine status output and repair harder
without improving the edit workflow.

The launcher, workspace task, semantic mutations, and IDEA runtime have
different concurrency needs. Treating any of them as one global singleton
unnecessarily prevents independent worktrees and disjoint edits from making
progress.

## Decision

`kast-agent-task` is a policy-free launcher and owns no lock. Any number of
launchers, sessions, installations, and exact workspaces may run concurrently.

One compact task record exists for an exact workspace root. It is shared by
all sessions and contains only the current task ID, installed generation,
baseline and current aggregate digests, current blockers, lifecycle state,
timestamps, and a temporary finish-executor claim while finishing. It does not
retain per-file hashes, diagnostic output, Gradle results, test-report hashes,
or immutable audit history.

The states are `ACTIVE`, `DRAINING`, `VALIDATING`, `BLOCKED`, `COMPLETE`, and
`ABORTED`:

- `begin` joins `ACTIVE` or `BLOCKED`, reports a task already finishing, and
  replaces a terminal task with a fresh task and aggregate baseline.
- `finish` installs one short-lived executor claim, closes semantic mutation
  admission, drains earlier operations, validates the pinned workspace, and
  records only `COMPLETE` or a retryable `BLOCKED` state.
- `repair` is idempotent. It requests cooperative cancellation from a live
  finisher. For a dead finisher, it releases only the matching barrier token,
  discards the interrupted attempt, re-snapshots the current workspace, and
  reopens the same task as `BLOCKED`.
- `abort` closes the current task without reverting or deleting source files.

Every applied semantic mutation carries the active workspace-task ID injected
by the Rust CLI. The Kotlin in-memory registry admits each operation after its
complete normalized path set is known. An operation waits behind every earlier
nonterminal operation whose scope is unresolved or overlapping. Disjoint known
scopes may run concurrently. Multi-path operations acquire their full scope at
once and retain it through validation and actual worker termination.

Finish is a generation-coherent barrier in that same registry. It rejects new
idempotency keys before reservation, drains older operations, and either marks
the task closed or reopens admission after failed validation. Registry restart
forgets speculative queue state; callers re-plan from the filesystem.

The exact-root receipt lock is used only for short state transitions. It is
never held across queue waits, diagnostics, Gradle, or tests. The existing IDEA
runtime lease remains separate and unchanged.

Provider hooks are adapters:

- Session start joins the shared task.
- Codex pre-tool use gates only generic Kotlin writes; reads, unrelated tools,
  and lifecycle recovery remain available.
- Post-tool use records session-local typed-attempt context and reports status.
- Codex Stop performs a fast status check and requires an already completed
  explicit finish.
- Copilot session end reports status for audit only.

## Consequences

Recovery preserves source files and has one explicit full reset: `abort`
followed by `begin`. Completed or aborted state is replaced on the next begin;
it is not inherited as validation authority. External shell or editor writes
remain outside the semantic queue and are detected by final aggregate snapshot
comparison.

This contract favors predictable operation and cheap recovery over durable
reconstruction of speculative runtime state.
