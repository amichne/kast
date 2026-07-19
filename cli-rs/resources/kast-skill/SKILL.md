---
name: kast
description: >
  Kotlin semantic work in Gradle repositories. Use when an agent needs compiler-backed
  Kotlin or Gradle discovery, identity, relationships, diagnostics, mutation, or
  validation evidence.
metadata:
  kast-cli-dialect-revision: "3"
---

# Kast

Use Kast for Kotlin or Gradle work when compiler-backed identity, diagnostics,
safe mutation, or build-and-test proof matters.

## Lifecycle

1. Start or resume the exact-root task with
   `kast-agent-task begin --workspace-root "$PWD"`.
2. Run `kast agent` for the compact task and readiness view. Discover operations
   through `kast agent --help` and the selected command's scoped `--help`.
3. Preserve typed identities, receipts, and blockers returned by Kast while doing
   the work. Do not reconstruct or bypass them with text-only guesses.
4. Finish with `kast-agent-task finish --workspace-root "$PWD"`. Completion is
   valid only when Kast returns `COMPLETE`.

Use `kast-agent-task status --workspace-root "$PWD"` for read-only task evidence.
Use `kast-agent-task abort --workspace-root "$PWD"` only to release owned resources
without claiming completion.

If any lifecycle command returns `BLOCKED`, report its typed blocker and next
action exactly. Do not release the lease, suppress diagnostics, substitute an
unobserved Gradle command, or claim success outside the lifecycle.
