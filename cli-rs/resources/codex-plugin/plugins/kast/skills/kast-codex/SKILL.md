---
name: kast-codex
description: "Use Kast's shared generation-bound task lifecycle for compiler-backed Kotlin and Gradle work, typed semantic operations, diagnostics, and build-and-test validation."
---

# Kast for Codex

Use Kast for Kotlin or Gradle work when compiler-backed identity, diagnostics,
safe mutation, or build-and-test proof matters.

1. Start or resume the exact-root task with
   `kast-agent-task begin --workspace-root "$PWD"`.
2. Run `kast agent` for the current task and readiness. Discover operations with
   `kast agent --help` and the selected command's scoped `--help`.
3. Preserve typed identities, receipts, and blockers returned by Kast.
4. Finish with `kast-agent-task finish --workspace-root "$PWD"` and claim
   completion only when Kast returns `COMPLETE`.

The exact-root task is shared across sessions. Use `status` for a read-only view,
`repair` for an interrupted finish, and `abort` followed by `begin` for an explicit
reset that preserves source files.

When Kast returns `BLOCKED`, report the typed blocker and next action exactly.
Do not bypass the finish barrier, diagnostics, or Gradle validation.
