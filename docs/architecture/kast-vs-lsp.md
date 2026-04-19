---
title: Kast vs LSP
description: Why Kast exists alongside the Language Server Protocol and
  where each tool fits best.
icon: lucide/git-compare
---

# Kast vs LSP

The Language Server Protocol is a mature standard for editor
intelligence. If it already exists, why build Kast? This page explains
the gap Kast fills and when each tool is the right choice.

## The short answer

LSP was designed for editors. Kast was designed for automation and
agents. The protocol goals are different, and those differences shape
what each tool can and can't do.

| Concern | LSP | Kast |
|---------|-----|------|
| **Primary audience** | Human in an editor | Script, agent, or pipeline |
| **Session model** | Editor-managed, event-driven | CLI-managed or IDE-hosted daemon |
| **Output format** | Editor-specific rendering | Structured JSON with metadata |
| **Completeness proof** | Not part of the protocol | `searchScope.exhaustive` on every reference result |
| **Traversal bounds** | Implementation-defined | Explicit depth, fan-out, timeout in every request |
| **Mutation model** | `WorkspaceEdit` (fire-and-forget) | Plan → review → apply with SHA-256 conflict detection |
| **Lifecycle** | Editor starts/stops the server | `workspace ensure` / `workspace stop` |

## Where LSP fits

LSP is the right tool when:

- A human is editing code in a supported editor
- The editor needs real-time feedback (completions, hover, diagnostics)
- The symbol context is already on screen
- The interaction is keystroke-driven

LSP servers are optimized for low-latency, incremental updates tied to
the editing session. They don't need to prove completeness because the
human can see the code and judge the results.

## Where Kast fits

Kast is the right tool when:

- A script or agent needs a machine-readable, parseable result
- The caller needs proof that a reference search was complete
- The caller needs bounded traversals with explicit truncation metadata
- The caller needs a safe mutation flow with conflict detection
- No editor is running

Kast's structured JSON output, completeness proofs, and plan-and-apply
mutation model are designed for callers that can't look at the code and
judge for themselves.

## Specific differences

### Completeness metadata

When you ask an LSP server for references, you get a list of locations.
There's no standard way to know whether the list is complete. The
server might have searched every file, or it might have stopped early.

When you ask Kast for references, the result includes
`searchScope.exhaustive` — a boolean proving whether every candidate
file was searched. When it's `true`, the list is provably complete.
When it's `false`, the result tells you exactly how many files were
candidates versus searched.

### Traversal bounds

LSP doesn't define a standard call hierarchy traversal model. Server
implementations vary in how deep they go and whether they report when
they stopped early.

Kast accepts explicit bounds on every call hierarchy request — depth,
fan-out, total edges, and timeout. Every node in the result tree
includes truncation metadata explaining why expansion stopped.

### Mutation model

LSP uses `WorkspaceEdit` to describe changes the editor should apply.
The edit goes straight to the editor's undo buffer. There's no built-in
conflict detection — the editor trusts the server.

Kast uses a two-phase plan-and-apply model. Rename returns an edit
plan with SHA-256 file hashes. You review the plan, then apply it. If
any file changed between planning and applying, Kast rejects the apply
with a clear conflict error.

### Session lifecycle

LSP servers are started and stopped by the editor. The lifecycle is
implicit — when the editor opens a project, the server starts. When
the editor closes, the server stops.

Kast's lifecycle is explicit. You run `workspace ensure` to start the
daemon and `workspace stop` to shut it down. This maps cleanly to
CI pipelines, script workflows, and agent sessions where the caller
controls when analysis starts and ends.

## Can I use both?

Yes. Many teams use an LSP-based Kotlin server in their editors for
real-time editing, and Kast in their CI pipelines and agent workflows
for automated analysis. They don't conflict — they solve different
problems.

## Next steps

- [How Kast works](how-it-works.md) — the full architecture story
- [Kast for agents](../for-agents/index.md) — what Kast gives your
  agent that LSP can't
