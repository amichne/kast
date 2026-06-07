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

## Output modes

Top-level operator commands print readable Markdown-style summaries by
default. Use `--output json` for the structured payload that scripts and
contract tests consume:

```console title="Machine-readable runtime status"
kast --output json status --workspace-root="/absolute/path/to/workspace"
```

`kast rpc` remains the raw JSON-RPC passthrough and always returns a JSON-RPC
object on stdout.

## Workspace lifecycle

These commands manage or inspect a workspace backend. They may start or
talk to the headless JVM runtime.

| Command | What it does | Common flags |
|---------|--------------|--------------|
| `kast up` | Start or warm a backend and print the selected runtime summary. | `--workspace-root`, `--backend-name`, `--accept-indexing`, `--output` |
| `kast status` | Report known backends and runtime state. | `--workspace-root`, `--output` |
| `kast stop` | Stop a workspace daemon and print what changed. | `--workspace-root`, `--backend-name`, `--output` |
| `kast capabilities` | Summarize advertised backend capabilities. | `--workspace-root`, `--output` |
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
not mutate source files. They print readable summaries by default; add
`--output json` when a script needs the complete rows.

| Command | What it does | Common flags |
|---------|--------------|--------------|
| `kast metrics fan-in` | Rank symbols by incoming references. | `--workspace-root`, `--database`, `--limit`, `--output` |
| `kast metrics fan-out` | Rank files by outgoing references. | `--workspace-root`, `--database`, `--limit`, `--output` |
| `kast metrics dead-code` | List declarations with no inbound reference rows. | `--workspace-root`, `--file-glob`, `--folder-filter`, `--output` |
| `kast metrics impact <symbol>` | Walk files and symbols affected by a symbol change. | `--workspace-root`, `--depth`, `--output` |
| `kast metrics coupling` | Report cross-module references. | `--workspace-root`, `--database`, `--output` |
| `kast metrics search <query>` | Search indexed symbols by name. | `--workspace-root`, `--limit`, `--output` |
| `kast metrics graph <symbol>` | Open the lower-level interactive metrics graph or print readable/JSON output. | `--workspace-root`, `--depth`, `--json`, `--output` |
| `kast demo` | Open the symbol or spatial demo, or print a JSON snapshot. | `--workspace-root`, `--view`, `--symbol`, `--query`, `--limit`, `--json` |

`metrics` and `kast demo` are direct-index only because they need
current source-index relation rows, persistent FTS, spatial structure,
and local source previews.

## Install and self-management

These commands install packaged resources or inspect the global Kast
installation state recorded in `config.toml`.
They also follow the same output policy: readable by default,
machine-readable with `--output json`.

| Command | What it does |
|---------|--------------|
| `kast config init` | Write the default global config file with concrete paths if missing. |
| `kast install` | Initialize a portable archive install and record install state in `config.toml`. |
| `kast install headless` | Install the packaged headless backend. |
| `kast install skill` | Install the packaged Kast skill into a target directory. |
| `kast install copilot` | Install packaged Copilot agents, hooks, and extensions. |
| `kast install plugin` | Download the Homebrew `kast-plugin` cask ZIP to `~/Downloads`. |
| `kast install plugin --link-jetbrains-profiles` | Install or reinstall the Homebrew `kast-plugin` cask and link it into local JetBrains IDE profiles. |
| `kast install shell` | Patch your shell profile to add the configured `binDir`, export `KAST_CONFIG_HOME`, and source completions. |
| `kast install completion bash` / `kast install completion zsh` | Print completion code for manual sourcing or packaging. |
| `kast current headless` | Print the recorded headless backend version. |
| `kast current skill` | Print the installed packaged skill version. |
| `kast current copilot` | Print the installed Copilot extension version for the current repo. |
| `kast current plugin` | Print the Homebrew-installed IDEA plugin cask version. |
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
