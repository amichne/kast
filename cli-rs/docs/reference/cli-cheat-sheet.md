---
title: CLI cheat sheet
description: The public `cli-rs` command tree, common flags, and which
  commands read the source index directly.
icon: lucide/list
---

# CLI cheat sheet

The Rust CLI mirrors the core Kast control-plane commands and adds
direct source-index metrics plus the ratatui demo views. Run
`kast <command> --help` for complete flag details.

## Workspace lifecycle

These commands manage or inspect a workspace backend. They may start or
talk to the headless JVM runtime.

| Command | What it does | Common flags |
|---------|--------------|--------------|
| `kast up` | Start or warm a backend and wait until it is servable. | `--workspace-root`, `--backend-name`, `--accept-indexing` |
| `kast status` | Report known backends and runtime state. | `--workspace-root` |
| `kast stop` | Stop a workspace daemon. | `--workspace-root`, `--backend-name` |
| `kast capabilities` | Print advertised backend capabilities. | `--workspace-root` |
| `kast daemon start` | Launch the headless JVM backend in the foreground. | `--workspace-root`, `--socket-path`, `--stdio` |

## JSON-RPC passthrough

`kast rpc` sends raw JSON-RPC to the selected workspace daemon. Use it
for compiler-backed operations such as resolve, references, callers,
rename planning, diagnostics, and generated operator methods.

```console title="Resolve a symbol at a file position"
kast rpc '{"jsonrpc":"2.0","id":1,"method":"raw/resolve","params":{"position":{"filePath":"/absolute/path/App.kt","offset":42}}}' \
  --workspace-root="/absolute/path/to/workspace"
```

| Command | What it does | Common flags |
|---------|--------------|--------------|
| `kast rpc <json>` | Send an inline JSON-RPC request. | `--workspace-root` |
| `kast rpc --request-file <path>` | Send a request from a JSON file. | `--workspace-root` |

## Direct source-index commands

These commands read `source-index.db` directly with `rusqlite`. They do
not mutate source files.

| Command | What it does | Common flags |
|---------|--------------|--------------|
| `kast metrics fan-in` | Rank symbols by incoming references. | `--workspace-root`, `--database`, `--limit` |
| `kast metrics fan-out` | Rank files by outgoing references. | `--workspace-root`, `--database`, `--limit` |
| `kast metrics dead-code` | List declarations with no inbound reference rows. | `--workspace-root`, `--file-glob`, `--folder-filter` |
| `kast metrics impact <symbol>` | Walk files and symbols affected by a symbol change. | `--workspace-root`, `--depth` |
| `kast metrics coupling` | Report cross-module references. | `--workspace-root`, `--database` |
| `kast metrics search <query>` | Search indexed symbols by name. | `--workspace-root`, `--limit` |
| `kast metrics graph <symbol>` | Open the lower-level interactive metrics graph or print JSON. | `--workspace-root`, `--depth`, `--json` |
| `kast demo` | Open the symbol or spatial demo, or print a JSON snapshot. | `--workspace-root`, `--view`, `--symbol`, `--query`, `--limit`, `--json` |

`metrics` and `kast demo` are direct-index only because they need
current source-index relation rows, persistent FTS, spatial structure,
and local source previews.

## Install and self-management

These commands install packaged resources or inspect the global Kast
installation state recorded in `config.toml`.

| Command | What it does |
|---------|--------------|
| `kast config init` | Write the default global config file with concrete paths if missing. |
| `kast install` | Initialize a portable archive install and record install state in `config.toml`. |
| `kast install skill` | Install the packaged Kast skill into a target directory. |
| `kast install copilot-extension` | Install packaged Copilot agents, hooks, and extensions. |
| `kast install idea-plugin` | Download the Homebrew `kast-plugin` cask ZIP to `~/Downloads`. |
| `kast install idea-plugin --link-jetbrains-profiles` | Install or reinstall the Homebrew `kast-plugin` cask and link it into local JetBrains IDE profiles. |
| `kast info` | Print the recorded global install state. |
| `kast doctor` | Verify the global install is still healthy. |
| `kast uninstall copilot-extension` | Remove managed Copilot resources. |
| `kast verify-extension` | Verify the installed Copilot extension version. |

## See also

- [Quickstart](../getting-started/quickstart.md) for build and demo setup.
- [Walk symbols in the terminal](../what-can-kast-do/symbol-walk-demo.md)
  for the ratatui controls and JSON snapshot shape.
- [Source index reader](../architecture/source-index-reader.md) for the
  direct SQLite contract.
