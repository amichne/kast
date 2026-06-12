---
title: Kast vs LSP
description: Why Kast exists alongside the Language Server Protocol and
  where each tool fits best.
icon: lucide/git-compare
---

# Kast vs LSP

LSP is the mature standard for editor intelligence. So why build
`kast`? Because LSP was designed for humans typing in editors, and
`kast` was designed for everything else: scripts, agents,
pipelines, anything that can't look at the code and decide whether
the answer makes sense.

## The short answer

LSP optimizes for the human at the keyboard. `kast` optimizes for
the caller that needs proof. Different audience, different
guarantees.

Kast now also exposes an LSP adapter with
`kast lsp --stdio`. That adapter does not replace the native Kast
contract; it translates standard LSP navigation and prepared rename
requests into the existing compiler-backed `raw/*` RPC methods.

| Concern | LSP | Kast |
|---------|-----|------|
| **Primary audience** | Human in an editor | Script, agent, or pipeline |
| **Session model** | Editor-managed, event-driven | CLI-managed or IDE-hosted daemon |
| **Output format** | Editor-specific rendering | Structured JSON with metadata |
| **Completeness proof** | Not in the protocol | `searchScope.exhaustive` on every reference result |
| **Traversal bounds** | Implementation-defined | Explicit depth, fan-out, timeout in every request |
| **Mutation model** | `WorkspaceEdit` (fire-and-forget) | Plan → review → apply with SHA-256 conflict detection |
| **Lifecycle** | Editor starts/stops the server | `up` / `stop` |

## Where LSP fits

Reach for LSP when:

- A human is editing in a supported editor
- The editor needs real-time feedback — completions, hover, diagnostics
- The symbol context is already on screen
- Interaction is keystroke-driven

LSP servers tune for low latency and incremental updates tied to
the editing session. They don't prove completeness because the
human can see the code and judge.

Use `kast lsp --stdio` when a standard LSP client needs Kotlin
navigation from Kast: definition, references, hover, document symbols,
workspace symbols, implementations, call hierarchy, type hierarchy, and
prepared rename. The adapter advertises only backend-supported
capabilities; rename is planned through Kast's `raw/rename` dry-run
flow before the LSP client applies the returned `WorkspaceEdit`.

## Where kast fits

Reach for `kast` when:

- A script or agent needs a parseable result
- The caller needs proof a reference search was complete
- Bounded traversals with explicit truncation metadata are required
- Mutations need conflict detection
- No editor is running

Structured JSON, completeness proofs, and plan-and-apply are built
for callers that can't read code and judge for themselves.

## Specific differences

### Completeness metadata

Ask an LSP server for references and you get a list of locations.
No standard way to know whether the list is whole. Maybe the server
searched every file. Maybe it stopped early.

Ask `kast` for references and the result carries
`searchScope.exhaustive` — a boolean proving every candidate file
was searched. `true` means provably complete. `false` tells you
exactly how many files were candidates versus searched.

### Traversal bounds

LSP doesn't define a standard call-hierarchy traversal model.
Implementations vary in depth and in whether they tell you they
stopped early.

`kast` accepts explicit bounds on every call-hierarchy request:
depth, fan-out, total edges, timeout. Every node in the result tree
carries truncation metadata explaining why expansion stopped.

### Mutation model

LSP's `WorkspaceEdit` describes changes the editor should apply.
The edit lands in the editor's undo buffer. No built-in conflict
detection — the editor trusts the server.

`kast` uses a two-phase plan-and-apply. Native RPC rename returns an
edit plan with SHA-256 file hashes. The LSP adapter uses the same
compiler-backed rename planner and exposes the planned edits as a
`WorkspaceEdit` for LSP clients.

### Session lifecycle

LSP servers start and stop with the editor. Implicit lifecycle —
open project, server starts; close project, server stops.

`kast` is explicit. `up` brings it up; `stop` shuts it down. Maps cleanly onto CI pipelines, scripts, and
agent sessions where the caller controls timing.

## Can I use both?

Yes. Plenty of teams run an LSP-based Kotlin server in their
editors for real-time editing, and `kast` in CI and agent workflows
for automated analysis. They don't conflict — they solve different
problems. `kast lsp --stdio` is the bridge for clients that can
consume LSP but should still receive Kast's compiler-grounded facts and
rename plans.

## Next steps

- [How Kast works](how-it-works.md) — the architecture in full
- [Kast for agents](../for-agents/index.md) — what `kast` gives
  your agent that LSP can't
