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

Lifecycle and RPC commands take `--workspace-root`, the absolute
path to your project root. Backend selection can be pinned with
`--backend-name=standalone` or `--backend-name=intellij` where the
command supports it.

## Workspace lifecycle

The daemon owns Kotlin state. These commands start it, inspect it,
and stop it. Manual refresh is an RPC method, not a top-level
command.

| Command                 | What it does                                                                  | Common flags                                       |
|-------------------------|-------------------------------------------------------------------------------|----------------------------------------------------|
| `kast up`               | Start the backend if needed and wait until it is servable.                   | `--workspace-root`, `--backend-name`               |
| `kast status`           | Report whether a backend is running and what state it is in.                 | `--workspace-root`                                 |
| `kast rpc …runtime/status…` | Return the machine-readable runtime status response.                     | JSON argument or `--request-file`                  |
| `kast rpc …raw/workspace-refresh…` | Manually request a workspace refresh through raw JSON-RPC.           | JSON argument or `--request-file`                  |
| `kast stop`             | Shut the backend down cleanly.                                               | `--workspace-root`                                 |
| `kast capabilities`     | Print which JSON-RPC methods this backend supports.                           | `--workspace-root`                                 |
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
| `raw/workspace-files`            | List workspace modules, source roots, and optional file paths.              | `includeFiles`, `maxFilesPerModule`                         |
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
`.agents/skills/kast/references/commands.json`, not from the OpenAPI
projection.

| RPC method                    | What it does                                                   | Key params                                              |
|-------------------------------|----------------------------------------------------------------|---------------------------------------------------------|
| `symbol/scaffold`             | Gather structural generation context for a Kotlin file.        | `targetFile`                                            |
| `symbol/resolve`              | Resolve a symbol by name to its declaration.                   | `symbol`, optional `kind`, `containingType`, `fileHint` |
| `symbol/references`           | Find usages of a named Kotlin symbol.                          | `symbol`, optional scope fields                         |
| `symbol/callers`              | Expand incoming or outgoing call hierarchy by symbol name.     | `symbol`, `direction`, `depth`                          |
| `symbol/rename`               | Resolve or target a symbol and apply a rename.                 | request `type`, symbol or offset target, `newName`      |
| `symbol/write-and-validate`   | Apply generated Kotlin code and validate the result.           | request `type`, file target, edit content               |
| `database/metrics`            | Query source-index metrics without a running daemon.           | `metric`, optional filters                              |

## Command tiers

Not every command targets the same audience. `kast` organizes
its surface into two tiers — both fully supported.

**Tier 1 (primary path):** `up`, `status`, `stop`, `capabilities`,
and `rpc`. The default operational flow starts or checks a
workspace session, then sends explicit JSON-RPC requests.

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
