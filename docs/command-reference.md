---
title: Command reference
description: Look up the public Kast CLI commands, their purpose, and the
  inputs they accept.
icon: lucide/terminal
---

This page is the compact lookup guide for the public `kast` commands. It
stays focused on the supported CLI surface rather than the internal daemon
entrypoint.

## Global help and command syntax

The top-level commands keep the interface predictable, which matters when you
switch between manual use and automation.

| Command | Use it for | Notes |
| --- | --- | --- |
| `kast help` | Print the full command list | Use `kast help <topic>` for a narrower help page |
| `kast --help` | Print the same top-level help page | Useful in scripts that prefer flags |
| `kast --version` | Print the CLI version | `kast version` also works |

Every option uses `--key=value` syntax. Successful commands print JSON on
stdout. Daemon notes, when present, print on stderr after the command.

## Workspace lifecycle commands

Use these commands to inspect, start, reuse, and stop the standalone daemon
for one workspace.

| Command | Purpose | Key options | Notes |
| --- | --- | --- | --- |
| `workspace status` | Inspect registered descriptors, liveness, and readiness | `--workspace-root` | Reports the selected daemon plus any additional descriptors for the same workspace |
| `workspace ensure` | Reuse a ready daemon or start one | `--workspace-root`, `--wait-timeout-ms` | Uses a `60000` millisecond wait timeout unless you override it |
| `daemon start` | Start a detached standalone daemon explicitly | `--workspace-root`, `--wait-timeout-ms` | Useful when you want a direct start instead of an ensure |
| `daemon stop` | Stop the selected standalone daemon | `--workspace-root` | Removes the selected descriptor and reports what stopped |

## Read commands

Use these commands when you want read-only analysis against a ready workspace
runtime.

| Command | Purpose | Input shape | Notes |
| --- | --- | --- | --- |
| `capabilities` | Print the runtime capability set | `--workspace-root`, optional `--wait-timeout-ms` | Use this before relying on an operation in automation |
| `symbol resolve` | Resolve the symbol at a file position | Inline flags or `--request-file` | Inline form needs `--file-path` and `--offset` |
| `references` | Find references for the symbol at a file position | Inline flags or `--request-file` | Inline form also supports `--include-declaration=true` |
| `diagnostics` | Run diagnostics for one or more files | Inline flags or `--request-file` | Inline form uses comma-separated absolute file paths |

## Mutation commands

Use these commands when you want Kast to produce or apply code edits through
the supported mutation flow.

| Command | Purpose | Input shape | Notes |
| --- | --- | --- | --- |
| `rename` | Plan a rename operation | Inline flags or `--request-file` | Inline form needs `--file-path`, `--offset`, and `--new-name` |
| `edits apply` | Apply a prepared edit plan | `--request-file` only | The request file must include edits and expected file hashes |

## Current support boundary

The public command surface is intentionally small. Today, the main remaining
product gap is `callHierarchy`, so you must not assume it is available until
the runtime advertises it in `capabilities`.

## Next steps

If you want fuller examples instead of lookup tables, move to the guided task
pages.

- [Get started](get-started.md)
- [Run analysis commands](run-analysis-commands.md)
- [Use Kast from an LLM agent](use-kast-from-an-llm-agent.md)
