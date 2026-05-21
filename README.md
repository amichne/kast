# Kast
[![CI](https://github.com/amichne/kast/actions/workflows/ci.yml/badge.svg)](https://github.com/amichne/kast/actions/workflows/ci.yml) [![DeepWiki](https://deepwiki.com/badge.svg)](https://deepwiki.com/amichne/kast)

`kast` gives you compiler-backed Kotlin answers in your terminal, CI, or
agent.
Use it when text search can show you where a name appears, but you need to
know which declaration it resolves to, which callers are real, or whether a
planned edit is safe to apply.

`kast` has two independent runtime modes:

- **Standalone CLI + backend** — install the `kast` CLI and run
  `kast up` to start or warm the analysis backend. Fully independent from
  any IDE; works in terminals, CI, and headless agents.
- **IDEA / Android Studio plugin-backed runtime** — runs inside a supported
  JetBrains IDE and reuses the IDE's already-open project model, indexes, and
  analysis session.

Both runtime modes expose the same JSON-RPC contract, so the calling workflow
does not change when you switch between them.

## Install

Pick the entry point you want first:

| Runtime mode | Best when | Install |
| --- | --- | --- |
| **Standalone CLI + backend** | You want an independent runtime for terminal work, CI, or agents | [Install guide](https://kast.michne.com/getting-started/install/) |
| **IDEA / Android Studio plugin-backed runtime** | IDEA or Android Studio is already open and you want to reuse its already-open project model and indexes | [Plugin install guide](https://kast.michne.com/getting-started/install/#install-the-idea-and-android-studio-plugin-manually) · [Latest plugin zip](https://github.com/amichne/kast/releases/latest) |

Install the `kast` CLI with Homebrew when you can:

```console
brew tap amichne/kast
brew install kast
```

Use the shell installer when Homebrew is not available, or when you want the
interactive wizard to install the standalone backend, IDEA plugin zip, packaged
skill, or repo-local Copilot extension in one pass:

```console
curl -fsSL https://raw.githubusercontent.com/amichne/kast/HEAD/kast.sh | bash
```

For CI or headless agent installs from internal artifacts or self-contained
bundles, use the
[install guide](https://kast.michne.com/getting-started/install/#headless-agent-with-internal-artifacts).

Warm the standalone backend before running analysis commands:

```console
# Start or warm the backend
kast up --workspace-root=/path/to/your/workspace

# Once READY, send JSON-RPC requests through the CLI
kast rpc '{"jsonrpc":"2.0","id":1,"method":"raw/resolve","params":{"position":{"filePath":"/path/to/your/workspace/src/App.kt","offset":42}}}' \
  --workspace-root=/path/to/your/workspace
```

If IDEA or Android Studio with the plugin is already open on the project, skip
`kast up` — the CLI connects to the IDE's backend automatically.

## Why `kast` instead of text search?

`kast` answers questions that `grep` and `rg` cannot answer reliably on their
own:

- **Resolve the exact symbol, not just the spelling.** `kast` asks the Kotlin
  analysis engine which declaration a position refers to.
- **Trace usage with semantic context.** Reference and caller queries follow
  compiler-backed relationships instead of matching strings.
- **Plan edits before applying them.** Rename and edit flows are designed to
  surface conflicts before they touch files.

## Choose the runtime that fits your workflow

Use the standalone path when you need a fully independent process or when no
IDE is running. Use the IDEA / Android Studio plugin-backed path when the IDE
already has the project open and you want `kast` to piggyback on the IDE's
existing project model and index.

For the full comparison, see
[Backends](https://kast.michne.com/getting-started/backends/).

## Documentation

- Documentation site: <https://kast.michne.com/>
- Install guide: <https://kast.michne.com/getting-started/install/>
- Backend comparison: <https://kast.michne.com/getting-started/backends/>
