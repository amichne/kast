---
title: Command reference
description: Look up the public Kast CLI commands, their purpose, and the
  inputs they accept.
icon: lucide/terminal
---

This page is the compact lookup guide for the public `kast` commands. It
stays focused on the supported CLI surface rather than the internal daemon
entrypoint.

Note: The CLI binary is resolved with a small discovery cascade that prefers
the system `PATH` by default. You can override or point at a local build using
`KAST_CLI_PATH` (explicit executable) or `KAST_SOURCE_ROOT` (local build
outputs and optional auto-build when Java 21+ is available). See the Get
started guide for copyable examples.

## Global help and command syntax

The top-level commands keep the interface predictable, which matters when you
switch between manual use and automation.

| Command | Use it for | Notes |
| --- | --- | --- |
| `kast help` | Print the full command list | Use `kast help <topic>` for a narrower help page |
| `kast --help` | Print the same top-level help page | Useful in scripts that prefer flags |
| `kast --version` | Print the CLI version | `kast version` also works |
| `kast completion <bash\|zsh>` | Print an opt-in shell completion script | Supports Bash and Zsh |

Every option uses `--key=value` syntax. Successful commands print JSON on
stdout. Daemon notes, when present, print on stderr after the command.

When Kast detects an interactive terminal, the help pages use ANSI color. If
you need color in another context, set `CLICOLOR_FORCE=1` before you run the
help command.

## Workspace lifecycle commands

Use these commands to inspect, start, reuse, and stop the standalone daemon
for one workspace.

| Command | Purpose | Key options | Notes |
| --- | --- | --- | --- |
| `workspace status` | Inspect registered descriptors, liveness, and readiness | `--workspace-root` | Reports the selected daemon plus any additional descriptors for the same workspace |
| `workspace ensure` | Reuse a ready daemon or start one | `--workspace-root`, `--wait-timeout-ms` | Uses a `60000` millisecond wait timeout unless you override it |
| `workspace refresh` | Force a targeted or full workspace state refresh | `--workspace-root`, optional `--file-paths` | Use this as a manual recovery path; omit `--file-paths` for a full refresh |
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
| `call hierarchy` | Expand a bounded incoming or outgoing call tree | Inline flags or `--request-file` | Inline form needs `--file-path`, `--offset`, and `--direction`; optional bounds control truncation |
| `diagnostics` | Run diagnostics for one or more files | Inline flags or `--request-file` | Inline form uses comma-separated absolute file paths |

## Mutation commands

Use these commands when you want Kast to produce or apply code edits through
the supported mutation flow.

| Command | Purpose | Input shape | Notes |
| --- | --- | --- | --- |
| `rename` | Plan a rename operation | Inline flags or `--request-file` | Inline form needs `--file-path`, `--offset`, and `--new-name` |
| `edits apply` | Apply a prepared edit plan | `--request-file` only | The request file must include edits and expected file hashes |

## Workspace refresh behavior

Kast keeps workspace state fresh automatically after `edits apply` and after
most external `.kt` file changes. `workspace refresh` exists as the manual
recovery path when you need to force the daemon to rescan state.

- Omit `--file-paths` to refresh the full workspace and rebuild the daemon view
  of current Kotlin files.
- Pass `--file-paths=/absolute/A.kt,/absolute/B.kt` to refresh only the listed
  files.
- Read the `refreshedFiles`, `removedFiles`, and `fullRefresh` fields in the
  JSON result when automation needs to know what changed.

## Current support boundary

The public command surface is intentionally small. `call hierarchy` is
available, but it is intentionally bounded. Use `--direction` plus the
optional depth, total-call, child-count, timeout, and cache flags, and read
`stats` or per-node `truncation` fields before you claim the tree is complete.

## Next steps

If you want fuller examples instead of lookup tables, move to the guided task
pages.

- [Get started](get-started.md)
- [Run analysis commands](run-analysis-commands.md)
- [Use Kast from an LLM agent](use-kast-from-an-llm-agent.md)
- [LLM scaffolding reference](llm-scaffolding-reference.md)
