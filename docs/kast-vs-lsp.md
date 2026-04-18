---
title: Kast vs LSP
description: How Kast compares to a Kotlin Language Server Protocol implementation and when to use each.
icon: lucide/git-compare
---

# Why not just use an LSP?

## What they share

| LSP capability | Kast capability |
| --- | --- |
| go-to-definition | resolve |
| find-references | references |
| document-symbol | outline |
| workspace-symbol | workspace-symbol |
| diagnostics | diagnostics |
| rename | rename + apply-edits |
| call-hierarchy | call-hierarchy |
| type-hierarchy | type-hierarchy |

Both approaches rely on Kotlin compiler semantics under the hood.

## What Kast provides that LSP does not

1. **Automation-first protocol**: LSP is editor-managed and bidirectional (`didOpen`/`didChange`). Kast is request/response over local transports with self-contained JSON payloads you can pipe in scripts.
2. **Two-phase rename with hash checks**: Kast separates planning (`rename`) and commit (`apply-edits`) with SHA-256 `fileHashes` for conflict detection.

```json
{
  "edits": [{ "filePath": "/abs/A.kt", "startOffset": 10, "endOffset": 14, "newText": "NewName" }],
  "fileHashes": [{ "filePath": "/abs/A.kt", "hash": "..." }]
}
```

3. **`searchScope` metadata**: Kast returns scope/exhaustiveness counters.

```json
{
  "searchScope": {
    "exhaustive": true,
    "visibility": "PUBLIC",
    "candidateFileCount": 42,
    "searchedFileCount": 42
  }
}
```

4. **Bounded call hierarchy with stats**: one bounded response with traversal controls (`depth`, `maxTotalCalls`, `maxChildrenPerNode`, `timeoutMillis`) and truncation metadata.
5. **Semantic insertion point**: dedicated insertion-offset API with no direct LSP equivalent.
6. **Workspace model introspection**: `workspace/files` exposes modules, roots, and files.
7. **Import optimization**: explicit `optimize-imports` operation.
8. **No editor dependency**: standalone Kast daemon can run in CI and scripts without IDE hosting.

## What LSP provides that Kast does not

| LSP feature | Kast status |
| --- | --- |
| completion | Partial via `completions` query (not interactive editor sync) |
| signature help | Available via enriched `resolve` payload |
| hover | Available via enriched `resolve` payload |
| code actions | Available via `code-actions` query |
| formatting | Not a core Kast command |
| folding ranges | Not exposed |
| semantic tokens | Not exposed |
| inlay hints | Not exposed |
| selection range | Not exposed |
| live document sync | Not editor-synced by design |

These are mostly editor-interactive concerns; Kast is optimized for automation workflows.

## When to use which

| Your situation | Use |
|---|---|
| Building an editor plugin or IDE extension | LSP |
| Running analysis in CI or a shell script | Kast |
| Giving an LLM agent semantic code understanding | Kast |
| Need real-time completion while typing | LSP |
| Need to plan and verify a rename before applying | Kast |
| Need to know if a reference search was exhaustive | Kast |
| Need a bounded call graph in a single request | Kast |

## Kotlin LSP landscape

- `kotlin-language-server` (fwcd): community server built on older K1-era APIs and known multi-module gaps.
- JetBrains experimental IntelliJ-hosted LSP: tied to IntelliJ runtime rather than a standalone daemon.
- Kast: uses K2 Analysis API directly and targets multi-module Gradle workspaces with automation-first JSON contracts.
