# Sub-Agent Delegation Policy Design

**Status:** Approved

**Date:** 2026-07-13

## Context

The repository instructions do not currently state whether an agent may
delegate work to sub-agents. The repository should make that permission
explicit without requiring delegation or duplicating a repo-wide policy across
scoped `AGENTS.md` files.

## Decision

Add one concise, repo-wide delegation section to the root `AGENTS.md`:

- Agents may delegate concrete, bounded tasks to sub-agents when doing so is
  useful.
- Delegation is encouraged for independent investigation, implementation,
  testing, or review work that can proceed safely in parallel, but it is never
  mandatory.
- The primary agent remains responsible for task scope, coordination,
  integration, review of delegated results, and final verification.
- Agents must account for the shared workspace and avoid parallel delegation
  when tasks would edit the same files, depend on unfinished shared state, or
  are otherwise tightly coupled.

The policy grants repository-level permission to use an available delegation
facility. It does not require every execution environment to provide one.

## Placement

Place the new section in the root `AGENTS.md` under agent-specific guidance.
Do not repeat it in nested instruction files. Scoped guides may narrow the
policy later if a subtree has a concrete coordination constraint.

## Considered alternatives

### Mandate delegation for parallelizable work

Rejected. Delegation overhead can exceed its benefit for small tasks, and
availability differs between agent environments.

### Repeat the policy in every scoped guide

Rejected. Duplication would make the permission harder to maintain and allow
scoped copies to drift.

### Record the policy in an ADR

Rejected. This changes an internal agent workflow rule, not the public product
surface, source ownership, generated outputs, or validation gates.

## Acceptance criteria

1. The root `AGENTS.md` explicitly permits and encourages useful sub-agent
   delegation without making it mandatory.
2. Responsibility for coordination, integration, review, and verification
   remains with the primary agent.
3. Shared-workspace collision and task-coupling constraints are explicit.
4. Nested `AGENTS.md` files and public documentation remain unchanged.
5. `git diff --check` passes.
