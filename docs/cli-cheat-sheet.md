---
title: CLI cheat sheet
description: Every Kast CLI command, the flags you need most, and what each one returns.
icon: lucide/terminal
---

# CLI cheat sheet

A scannable index of the `kast` CLI. The public command tree is
small: lifecycle commands, install/manage commands, validation
helpers, and `rpc` for the analysis contract. Common flags only;
run `kast <command> --help` for the full set.

Lifecycle, metrics, demo, and RPC commands default to the current
workspace. When run from a subdirectory, Kast walks up to
`settings.gradle.kts`, `settings.gradle`, `build.gradle.kts`,
`build.gradle`, or `.kast`. Use `--workspace-root` only when you
need to target a different directory. Backend selection can be pinned
with `--backend=headless` or `--backend=idea` where the command
supports it.

## Output modes

Operator commands default to readable Markdown-style summaries so humans can
see status, next steps, paths, and warnings without parsing JSON. Add
`--output json` when automation needs the structured payload:

```console title="Machine-readable status"
kast --output json status
```

`kast rpc` is the raw JSON-RPC transport and always returns the backend
contract response on stdout. `kast lsp --stdio` is the Language Server
Protocol adapter and writes LSP-framed JSON-RPC messages on stdout.

## Workspace lifecycle

The daemon owns Kotlin state. These commands start it, inspect it,
and stop it. Manual refresh is an RPC method, not a top-level
command.

| Command                 | What it does                                                                  | Common flags                                       |
|-------------------------|-------------------------------------------------------------------------------|----------------------------------------------------|
| `kast setup`            | Install or update local integrations and refresh existing managed assets.    | `--force`, `--skip-shell`, `--skip-headless`, `--skip-plugin`, `--include-copilot` |
| `kast up`               | Start the backend if needed and print the selected runtime summary.          | `--backend`, `--output`                            |
| `kast status`           | Report whether a backend is running and what state it is in.                 | `--output`                                         |
| `kast rpc …runtime/status…` | Return the machine-readable runtime status response.                     | JSON argument or `--request-file`                  |
| `kast rpc …raw/workspace-refresh…` | Manually request a workspace refresh through raw JSON-RPC.           | JSON argument or `--request-file`                  |
| `kast stop`             | Shut the backend down cleanly and print what was removed.                    | `--output`                                         |
| `kast capabilities`     | Summarize which JSON-RPC methods this backend supports.                       | `--output`                                         |
| `kast doctor`           | Verify install metadata, config validity, canonical paths, and binary linkage. | `--output`                                      |
| `kast lsp --stdio`      | Run the Language Server Protocol adapter over stdio.                          | `--workspace-root`, `--backend`                    |
| `kast rpc …health…`     | Lightweight liveness ping. Returns immediately.                               | JSON argument or `--request-file`                  |

## Read operations

These RPC methods ask questions about your code. Nothing on disk
changes. Resolve-first applies: most "find X" workflows start
with `raw/resolve` to get a stable symbol identity, then feed the
same `filePath` and `offset` into the next request.

| RPC method                       | What it does                                                                | Key params                                                  |
|----------------------------------|-----------------------------------------------------------------------------|-------------------------------------------------------------|
| `raw/resolve`                    | Identify the symbol at a position. Returns FQN, kind, signature, location.  | `position.filePath`, `position.offset`                      |
| `raw/references`                 | Find every reference to the symbol at a position.                           | `position`, `includeDeclaration`                            |
| `raw/call-hierarchy`             | Walk callers (`INCOMING`) or callees (`OUTGOING`) of a function.            | `position`, `direction`, `depth`                            |
| `raw/type-hierarchy`             | Walk supertypes or subtypes of a class or interface.                        | `position`, `direction`, `depth`                            |
| `raw/implementations`            | Find every concrete implementation of an interface or abstract class.       | `position`, `maxResults`                                    |
| `raw/file-outline`               | Return a tree of named declarations in a file.                              | `filePath`                                                  |
| `raw/workspace-symbol`           | Search for symbols by name across the workspace.                            | `pattern`, `regex`, `maxResults`                            |
| `raw/workspace-search`           | Search workspace file contents by text or regex.                            | `pattern`, `regex`, `caseSensitive`, `fileGlob`             |
| `raw/workspace-files`            | Secondary module summary and bounded optional file paths.                   | `moduleName`, `includeFiles`, `maxFilesPerModule`            |
| `raw/semantic-insertion-point`   | Find a safe position to insert new code into a class or file.               | `position`, `target`                                        |
| `raw/diagnostics`                | Return errors and warnings for one or more files.                           | `filePaths`                                                 |
| `raw/code-actions`               | Return available code actions at a file position.                           | `position`                                                  |
| `raw/completions`                | Return completion candidates available at a file position.                  | `position`, `maxResults`                                    |

## Mutations

Mutations always follow plan-then-apply. The first command
computes edits and SHA-256 hashes of the files it read. The
second writes the edits *only if* the hashes still match — the
state `kast` planned against is the state `kast` writes to.

| RPC method                 | What it does                                                                | Key params                                                  |
|----------------------------|-----------------------------------------------------------------------------|-------------------------------------------------------------|
| `raw/rename`               | Plan a rename of the symbol at a position. Returns edits + file hashes.     | `position`, `newName`, `dryRun`                             |
| `raw/optimize-imports`     | Plan import cleanup for one or more files.                                  | `filePaths`                                                 |
| `raw/apply-edits`          | Write a previously-planned edit set, rejecting on hash mismatch.            | `edits`, `fileHashes`, optional `fileOperations`            |

## Operator-level RPC methods

The packaged skill and Copilot extension also use generated `symbol/*`
and `database/*` methods. They live in the same `kast rpc` transport,
but their exact request shapes come from
`cli-rs/resources/kast-skill/references/commands.json`, not from the OpenAPI
projection.

| RPC method                    | What it does                                                   | Key params                                              |
|-------------------------------|----------------------------------------------------------------|---------------------------------------------------------|
| `symbol/scaffold`             | Gather structural generation context for a Kotlin file.        | `targetFile`                                            |
| `symbol/resolve`              | Resolve a symbol by name to its declaration.                   | `symbol`, optional `kind`, `containingType`, `fileHint` |
| `symbol/references`           | Find usages of a named Kotlin symbol.                          | `symbol`, optional scope fields                         |
| `symbol/callers`              | Expand incoming or outgoing call hierarchy by symbol name.     | `symbol`, `direction`, `depth`                          |
| `symbol/rename`               | Resolve or target a symbol and apply a rename.                 | request `type`, symbol or offset target, `newName`      |
| `symbol/write-and-validate`   | Apply generated Kotlin code and validate the result.           | request `type`, file target, edit content               |
| `database/metrics`            | Query Rust-owned source-index metrics without a running daemon. | `metric`, optional filters                              |

## Language Server Protocol

`kast lsp --stdio` exposes Kotlin navigation and prepared rename through
standard LSP framing while reusing the existing Kast daemon and raw
RPC methods. It advertises only capabilities supported by the selected
backend. Rename is planned through `raw/rename` with `dryRun=true`,
then returned to the LSP client as a `WorkspaceEdit`.

| LSP method | Backing Kast method |
|------------|---------------------|
| `textDocument/definition` | `raw/resolve` |
| `textDocument/references` | `raw/references` |
| `textDocument/hover` | `raw/resolve` |
| `textDocument/documentSymbol` | `raw/file-outline` |
| `workspace/symbol` | `raw/workspace-symbol` |
| `textDocument/implementation` | `raw/implementations` |
| `textDocument/prepareCallHierarchy`, `callHierarchy/incomingCalls`, `callHierarchy/outgoingCalls` | `raw/resolve`, `raw/call-hierarchy` |
| `textDocument/prepareTypeHierarchy`, `typeHierarchy/supertypes`, `typeHierarchy/subtypes` | `raw/resolve`, `raw/type-hierarchy` |
| `textDocument/prepareRename`, `textDocument/rename` | `raw/resolve`, `raw/rename` |

## Direct source-index commands

The `metrics` commands read `source-index.db` directly through the Rust CLI.
They print readable summaries by default and preserve full rows with
`--output json`.

| Command | What it does | Common flags |
|---------|--------------|--------------|
| `kast metrics fan-in` | Rank symbols by incoming references. | `--database`, `--limit`, `--output` |
| `kast metrics fan-out` | Rank files by outgoing references. | `--database`, `--limit`, `--output` |
| `kast metrics dead-code` | List declarations with no inbound reference rows. | `--file-glob`, `--folder-filter`, `--output` |
| `kast metrics impact <symbol>` | Walk files and symbols affected by a symbol change. | `--depth`, `--output` |
| `kast metrics coupling` | Report cross-module references. | `--database`, `--output` |
| `kast metrics search <query>` | Search indexed symbols by name. | `--limit`, `--output` |

## Command tiers

Not every command targets the same audience. `kast` organizes
its surface into two tiers — both fully supported.

**Tier 1 (primary path):** `up`, `status`, `stop`, `capabilities`,
`rpc`, and `lsp`. The default operational flow starts or checks a workspace
session with readable CLI summaries, then sends explicit JSON-RPC requests or
LSP-framed requests for compiler-backed analysis and prepared rename.

**Tier 2 (specialized RPC methods):** the `raw/*`, `symbol/*`, and
`database/*` method families. Use them for semantic navigation,
mutation planning, workspace recovery, and metrics without growing
the public CLI command tree.

## See also

- [Recipes](recipes.md) — copy-paste workflows that combine
  these commands
- [Understand symbols](what-can-kast-do/understand-symbols.md) —
  long-form reference for the read commands
- [API specification](reference/api-specification.md) — the
  OpenAPI projection for raw backend methods
