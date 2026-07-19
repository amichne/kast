---
name: kast-codex
description: "Use Kast's generation-bound task lifecycle for compiler-backed Kotlin and Gradle work, typed semantic operations, diagnostics, and build-and-test proof."
---

# Kast for Codex

Use Kast for Kotlin or Gradle work when compiler-backed identity, diagnostics,
safe mutation, or build-and-test proof matters.

1. Start or resume the exact-root task with
   `kast-agent-task begin --workspace-root "$PWD"`.
2. Run `kast agent` for current task/readiness evidence. Discover operations with
   `kast agent --help` and the selected command's scoped `--help`.
3. Preserve typed identities, receipts, and blockers returned by Kast.
4. Finish with `kast-agent-task finish --workspace-root "$PWD"` and claim
   completion only when Kast returns `COMPLETE`.

Use `kast-agent-task status --workspace-root "$PWD"` for read-only evidence and
`kast-agent-task abort --workspace-root "$PWD"` only to release owned resources
without claiming completion.

When Kast returns `BLOCKED`, report the typed blocker and next action exactly.
Do not bypass task ownership, diagnostics, Gradle proof, or lease release.
