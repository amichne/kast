---
title: Run analysis commands
description: Use the most common CLI workflows first, then add advanced
  primitives when your use case needs deeper control.
icon: lucide/search
---

This page presents Kast commands in two layers. First, you get the core command
set most teams use every day. Then you get advanced primitives that expose more
granularity for power users and agent workflows.

## Use the core workflow first

For most usage, this sequence is enough:

1. Ensure the workspace runtime is ready.
2. Confirm capabilities if automation depends on specific features.
3. Resolve and inspect symbols with read commands.
4. Plan and apply mutations through the guarded edit path.
5. Stop the daemon when you are done.

```bash
kast workspace ensure --workspace-root=/absolute/path/to/workspace
kast capabilities --workspace-root=/absolute/path/to/workspace
kast resolve --workspace-root=/absolute/path/to/workspace --file-path=/absolute/path/to/src/main/kotlin/com/example/App.kt --offset=123
kast references --workspace-root=/absolute/path/to/workspace --file-path=/absolute/path/to/src/main/kotlin/com/example/App.kt --offset=123
kast diagnostics --workspace-root=/absolute/path/to/workspace --file-paths=/absolute/path/to/src/main/kotlin/com/example/App.kt
```

If you need explicit daemon control, use:

```bash
kast workspace status --workspace-root=/absolute/path/to/workspace
kast workspace stop --workspace-root=/absolute/path/to/workspace
```

## Core commands and when to use them

These commands are the primary operator surface.

| Command | Use it when | Key inputs |
| --- | --- | --- |
| `workspace ensure` | You want explicit prewarm before semantic queries | `--workspace-root` |
| `workspace status` | You need liveness and readiness details | `--workspace-root` |
| `capabilities` | You must verify runtime support before a workflow | `--workspace-root` |
| `resolve` | You need the exact symbol identity at a file position | `--workspace-root`, `--file-path`, `--offset` |
| `references` | You need semantic usages of the resolved symbol | `--workspace-root`, `--file-path`, `--offset` |
| `diagnostics` | You need current analysis diagnostics for one or more files | `--workspace-root`, `--file-paths` |
| `rename` | You want a safe rename plan before writing edits | `--workspace-root`, `--file-path`, `--offset`, `--new-name` |
| `apply-edits` | You are ready to apply a prepared plan with hashes | `--workspace-root`, `--request-file` |
| `workspace stop` | You want to stop a standalone daemon instance | `--workspace-root` |

## Understand startup behavior

Kast can auto-start a standalone daemon for runtime-dependent commands.

- Use `workspace ensure` when you want explicit startup control.
- Add `--accept-indexing=true` to return when the daemon is servable in
  `INDEXING`.
- Add `--no-auto-start=true` to force failure instead of auto-start.

> **Note:** Commands can attach during `INDEXING`, but early semantic results
> can still be partial while background enrichment continues.

## Use mutation through a guarded flow

When changing code, keep this order:

1. Run `rename` to generate a plan.
2. Review the returned edits and expected file hashes.
3. Run `apply-edits` with the generated request payload.
4. Re-run read commands to validate resulting symbol state.

This flow keeps edits conflict-aware and reviewable.

## Add advanced primitives only when needed

These operations are fully supported and important for advanced use cases, but
they are not required in most day-to-day CLI usage.

| Command | Primary use case | Boundary to read |
| --- | --- | --- |
| `call-hierarchy` | Incoming and outgoing call graph slices | `stats` and node `truncation` |
| `outline` | File-level declaration tree | Excludes parameters, anonymous elements, and local declarations |
| `workspace-symbol` | Name-based symbol discovery across workspace | `page.truncated` for result caps |
| `type-hierarchy` | Supertypes and subtypes rooted at a symbol | Capability availability by backend |
| `insertion-point` | Semantic insertion location for new declarations | Best-fit location, not a rewrite plan |
| `workspace refresh` | Manual recovery after missed filesystem updates | `fullRefresh` and refreshed file lists |
| `optimize-imports` | Import cleanup for selected files | Scope is limited to provided files |

If you rely on these commands in automation, check `capabilities` before
execution and report bounded results honestly.

## Choose inline flags or request files

For `resolve`, `references`, `diagnostics`, `call-hierarchy`, `rename`,
`type-hierarchy`, and `insertion-point`, you can use inline flags for ad hoc
work or `--request-file` for repeatable automation payloads.

`apply-edits` is request-file only because it needs a structured edit plan.
`outline` and `workspace-symbol` are inline-only in the current CLI surface.

## Next steps

- [Command reference](command-reference.md)
- [Use Kast from an LLM agent](use-kast-from-an-llm-agent.md)
- [LLM scaffolding reference](llm-scaffolding-reference.md)
