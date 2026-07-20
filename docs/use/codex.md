---
title: Use Kast In Codex
description: Route Kotlin inspection and plan-first edits through the Kast Codex plugin.
icon: lucide/bot
---

# Use Kast In Codex

Use this guide when a Codex task needs compiler-backed Kotlin evidence. The
plugin routes semantic work through the installed Kast CLI and keeps setup,
repair, runtime management, and developer operations outside the normal task
surface.

## Start A Semantic Task

The `kast-codex` skill may activate implicitly for Kotlin and Gradle work. Name
it explicitly when you want the routing contract to be part of the request:

```text
Use $kast-codex to inspect OrderService callers and plan the requested change.
```

Codex should first discover relevant Kotlin files with `workspace-files`, then
resolve an exact symbol identity before requesting relationships, impact, or a
mutation. Broad text search is not a substitute for semantic identity.

## Keep Mutations Plan-First

Kast mutation commands plan by default. Codex should review the selected
declaration, write set, conflicts, and diagnostics before applying a plan.
Every apply uses a stable idempotency key and waits for one terminal result. If
the waiting process disconnects, Codex reruns the exact command with the same
key against the same runtime. A replaced runtime or terminal failure blocks the
task for `abort` followed by `begin`; Codex does not replay the edit under a new
key.

## Finish With Current Diagnostics

After any Kotlin change, Codex runs diagnostics for every affected `.kt` or
`.kts` file. The evidence is tied to the current file hash, so diagnostics from
before a later edit do not satisfy the workflow.

When Kast cannot perform a requested edit, Codex reports the typed blocker. A
generic filesystem fallback is available only after a recorded unsupported or
typed-failure outcome for the same target. The fallback still requires current
diagnostics before the task can finish.

## Understand The Guardrails

The plugin hooks keep workflow evidence across tool calls without introducing
another semantic API.

| Moment | Guardrail |
| --- | --- |
| Task start | Checks the active Kast/plugin release, exact workspace, preparation evidence, and baseline Kotlin hashes |
| Delegation | Carries the exact root and linked-worktree identity into the delegated task |
| Before a tool | Denies a known generic Kotlin mutation until the target-bound typed route has failed |
| After a tool | Records structured Kast outcomes, affected files, in-flight mutation keys, typed failures, and diagnostics evidence |
| Task stop | Continues work when newly changed Kotlin lacks current diagnostics or an explicit typed blocker |

Compaction rehydrates the same session state and preserves the original
baseline. Pre-existing dirty files remain outside the plugin's claim about
what the current task changed.

Hooks may run read-only readiness checks and prepare repair guidance. They do
not apply setup, repair, IDE, installation, or source mutations on their own.

Use the [Codex plugin contract](../reference/codex-plugin.md) for the exact
visible command set and state boundary.
