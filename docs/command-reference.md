---
title: Command reference
description: Look up the public Kast CLI commands, their purpose, and the
  inputs they accept.
icon: lucide/terminal
---

This page is the compact lookup guide for the public `kast` commands. It
stays focused on the supported CLI surface rather than the internal daemon
entrypoint.

Note: If you use the packaged `kast` skill or the repo-local launcher resolver,
it prefers the system `PATH` by default. You can override that lookup with
`KAST_CLI_PATH` (explicit executable) or `KAST_SOURCE_ROOT` (local build
outputs plus the minimal `:kast:writeWrapperScript` auto-build path when Java
21+ is available). See the Get started guide for copyable examples.

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
`kast smoke` follows the same default and can render a markdown report when you
pass `--format=markdown`.

Runtime-dependent commands can auto-start a standalone daemon when needed. They
attach once the daemon is servable, which can happen during `INDEXING`. Pass
`--no-auto-start=true` when automation must fail instead of starting a daemon.
`workspace ensure` remains the explicit prewarm command and waits for `READY`
unless you add `--accept-indexing=true`. If the IntelliJ plugin backend is
running for the same workspace, the CLI discovers and connects to it
automatically.

When Kast detects an interactive terminal, the help pages use ANSI color. If
you need color in another context, set `CLICOLOR_FORCE=1` before you run the
help command.

## CLI management commands

Use these commands when you are installing the published bundle or copying the
bundled `kast` skill into a workspace.

| Command | Purpose | Key options | Notes |
| --- | --- | --- | --- |
| `install` | Install a portable Kast archive as a named local instance | `--archive`, optional `--instance`, `--bin-dir`, `--instances-root` | Used by local and dev packaging flows rather than the published installer script |
| `install skill` | Install the bundled `kast` skill into the current workspace | Optional `--target-dir`, `--name`, `--yes=true` | Defaults to `.agents/skills`, `.github/skills`, or `.claude/skills` based on directories already present; matching versions skip safely |

## Workspace lifecycle commands

Use these commands to inspect, start, reuse, and stop the standalone daemon
for one workspace.

| Command | Purpose | Key options | Notes |
| --- | --- | --- | --- |
| `workspace status` | Inspect registered descriptors, liveness, and readiness | `--workspace-root`, optional `--backend-name` | Reports the selected daemon plus any additional descriptors for the same workspace |
| `workspace ensure` | Explicitly prewarm or reuse a standalone daemon | `--workspace-root`, `--wait-timeout-ms`, optional `--accept-indexing=true`, `--backend-name` | Waits for `READY` by default; `--accept-indexing=true` returns once the daemon is servable in `INDEXING` |
| `workspace refresh` | Force a targeted or full workspace state refresh | `--workspace-root`, optional `--file-paths` | Use this as a manual recovery path; omit `--file-paths` for a full refresh |
| `workspace stop` | Stop the selected standalone daemon | `--workspace-root`, optional `--backend-name` | Removes the selected descriptor and reports what stopped; pass `--backend-name` to target a specific backend |

## Read commands

Use these commands when you want read-only analysis against a live workspace
runtime.

All read commands also accept `--backend-name` to target a specific backend
and `--no-auto-start=true` when you want them to fail instead of creating a
daemon implicitly.

| Command | Purpose | Input shape | Notes |
| --- | --- | --- | --- |
| `capabilities` | Print the runtime capability set | `--workspace-root`, optional `--wait-timeout-ms` | Use this before relying on an operation in automation |
| `resolve` | Resolve the symbol at a file position | Inline flags or `--request-file` | Inline form needs `--file-path` and `--offset` |
| `references` | Find references for the symbol at a file position | Inline flags or `--request-file` | Inline form also supports `--include-declaration=true` |
| `call-hierarchy` | Expand a bounded incoming or outgoing call tree | Inline flags or `--request-file` | Inline form needs `--file-path`, `--offset`, and `--direction`; optional bounds control truncation |
| `diagnostics` | Run diagnostics for one or more files | Inline flags or `--request-file` | Inline form uses comma-separated absolute file paths |
| `outline` | Get a hierarchical file outline | `--file-path` (absolute, required), `--workspace-root`, optional `--wait-timeout-ms` | Returns a nested tree of named declarations |
| `workspace-symbol` | Search the workspace for symbols by name | `--pattern` (required), `--workspace-root`, optional `--regex`, `--kind`, `--max-results` | Case-insensitive substring by default; pass `--regex=true` for pattern matching |
| `type-hierarchy` | Expand supertypes and subtypes from a resolved symbol | Inline flags or `--request-file` | Standalone backend only; inline form needs `--file-path` and `--offset` |
| `insertion-point` | Find the semantic insertion point for a new declaration | Inline flags or `--request-file` | Returns the best location to insert a new member |

## Mutation commands

Use these commands when you want Kast to produce or apply code edits through
the supported mutation flow.

Mutation commands also accept `--backend-name` and `--no-auto-start=true`.

| Command | Purpose | Input shape | Notes |
| --- | --- | --- | --- |
| `rename` | Plan a rename operation | Inline flags or `--request-file` | Inline form needs `--file-path`, `--offset`, and `--new-name` |
| `apply-edits` | Apply a prepared edit plan | `--request-file` only | The request file must include edits and expected file hashes |
| `optimize-imports` | Optimize imports for one or more files | `--file-paths` or `--request-file` | Returns optimized import lists |

## Validation commands

Use this command when you want Kast to exercise the public CLI surface against
the current workspace before you trust a local build, install, or agent setup.

| Command | Purpose | Key options | Notes |
| --- | --- | --- | --- |
| `smoke` | Run the portable smoke workflow against the current CLI and a real workspace | Optional `--workspace-root`, `--file`, `--source-set`, `--symbol`, `--format=markdown` | Defaults `--workspace-root` to the current working directory, emits an aggregated JSON readiness report on stdout, and shells out to the maintained `smoke.sh` entrypoint with the current `kast` launcher |

## Workspace refresh behavior

Kast keeps workspace state fresh automatically after `apply-edits` and after
most external `.kt` file changes. `workspace refresh` exists as the manual
recovery path when you need to force the daemon to rescan state.

- Omit `--file-paths` to refresh the full workspace and rebuild the daemon view
  of current Kotlin files.
- Pass `--file-paths=/absolute/A.kt,/absolute/B.kt` to refresh only the listed
  files.
- Read the `refreshedFiles`, `removedFiles`, and `fullRefresh` fields in the
  JSON result when automation needs to know what changed.

## Current support boundary

The public command surface is intentionally small. `call-hierarchy`, `outline`,
and `workspace-symbol` are available, but each is intentionally bounded.
`call-hierarchy` uses `--direction` plus the optional depth, total-call,
child-count, timeout, and cache flags; read `stats` or per-node `truncation`
fields before you claim the tree is complete. `outline` covers named
declarations but excludes parameters, anonymous elements, and local
declarations. `workspace-symbol` defaults to 100 results; read
`page.truncated` before treating the list as complete.

## Next steps

If you want fuller examples instead of lookup tables, move to the guided task
pages.

- [Get started](get-started.md)
- [Run analysis commands](run-analysis-commands.md)
- [Use Kast from an LLM agent](use-kast-from-an-llm-agent.md)
- [LLM scaffolding reference](llm-scaffolding-reference.md)
