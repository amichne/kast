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

After a successful generic write, the plugin checks exact-root Kast status. If
healthy, it runs one diagnostics request per `.kt` or `.kts` path in that tool
input. The result is advisory context; it never blocks the edit or turn.

Typed Kast mutations still return their own synchronous diagnostics result.

## Understand The Guardrails

The plugin skill routes requests to typed Kast commands, while two hooks add
best-effort local context.

| Moment | Guardrail |
| --- | --- |
| Session startup | Opens the exact worktree through Kast's existing IDEA or Android Studio launch fallback and reports readiness problems |
| After a successful Kotlin write | Runs diagnostics separately for each path when exact-root IDEA status is healthy |

The hooks keep no session state and add no pre-write or turn-stop gate.
Use the IntelliJ Kast settings page to disable all Codex hooks globally or disable either event independently.

Use the [Codex plugin contract](../reference/codex-plugin.md) for the exact
visible command set and state boundary.
