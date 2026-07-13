# Sub-Agent Delegation Policy Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Permit and encourage useful sub-agent delegation across the repository without making delegation mandatory.

**Architecture:** The root `AGENTS.md` owns the single repo-wide delegation policy. Nested instruction files remain unchanged and may narrow the root policy only when a subtree later develops a concrete coordination constraint.

**Tech Stack:** Markdown repository instructions, Git verification

## Global Constraints

- Delegation is permitted and encouraged when it is useful, but never mandatory.
- Delegate only concrete, bounded work that can proceed independently.
- The primary agent retains responsibility for scope, coordination, integration, review, and final verification.
- Avoid delegated work that would collide in the shared workspace or depend on unfinished shared state.
- Do not change nested `AGENTS.md` files or public documentation.

---

### Task 1: Add the repo-wide delegation policy

**Files:**
- Modify: `AGENTS.md:82-89`
- Verify: `AGENTS.md`

**Interfaces:**
- Consumes: `.agents/superpowers/specs/2026-07-13-sub-agent-delegation-policy-design.md`
- Produces: the root `## Sub-Agent Delegation` instruction section inherited by every repository subtree

- [ ] **Step 1: Verify the policy is absent at baseline**

Run:

```bash
rg -n '^## Sub-Agent Delegation$' AGENTS.md
```

Expected: exit status `1` with no output because the heading does not yet
exist.

- [ ] **Step 2: Add the delegation policy**

Insert the following block immediately before the `<kast>` routing block:

```markdown
## Sub-Agent Delegation

Agents may delegate concrete, bounded tasks to sub-agents when doing so is
useful. Delegation is encouraged for independent investigation,
implementation, testing, or review work that can proceed safely in parallel,
but it is never mandatory.

The primary agent remains responsible for scope, coordination, integration,
reviewing delegated results, and final verification. Account for the shared
workspace: do not delegate parallel work that would edit the same files,
depend on unfinished shared state, or otherwise be tightly coupled.
```

- [ ] **Step 3: Verify the operative policy**

Run:

```bash
rg -n -A10 '^## Sub-Agent Delegation$' AGENTS.md
git diff --check
git diff --name-only HEAD -- ':(glob)**/AGENTS.md'
```

Expected: the search prints the complete approved policy, `git diff --check`
exits `0`, and the changed-file query prints only `AGENTS.md`.

- [ ] **Step 4: Review and commit the instruction change**

Run:

```bash
git diff -- AGENTS.md
git add AGENTS.md
git diff --cached --check
git commit -m "docs: permit useful sub-agent delegation"
```

Expected: the diff contains only the approved root policy, the staged check
exits `0`, and the commit succeeds.

- [ ] **Step 5: Confirm the repository state**

Run:

```bash
git status --short --branch
git log -3 --oneline
```

Expected: the worktree is clean, the branch is ahead of `origin/main` only by
the approved design, plan, and implementation commits, and the newest commit
is `docs: permit useful sub-agent delegation`.
