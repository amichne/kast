# Kast
[![CI](https://github.com/amichne/kast/actions/workflows/ci.yml/badge.svg)](https://github.com/amichne/kast/actions/workflows/ci.yml)

[![Ask DeepWiki](https://deepwiki.com/badge.svg)](https://deepwiki.com/amichne/kast)

`kast` gives you compiler-backed Kotlin answers in your terminal, CI, or
agent.
Use it when text search can show you where a name appears, but you need to
know which declaration it resolves to, which callers are real, or whether a
planned edit is safe to apply.

`kast` has three independent runtime modes:

- **Standalone CLI + backend** — install the `kast` CLI and run
  `kast up` to start or warm the analysis backend. Fully independent from
  any IDE; works in terminals, CI, and headless agents.
- **Headless CLI + backend** — install the Ubuntu/Debian bundle and run
  `kast up --backend=headless` for a packaged IntelliJ-backed runtime in
  hosted Linux agents.
- **IDEA / Android Studio plugin-backed runtime** — runs inside a supported
  JetBrains IDE and reuses the IDE's already-open project model, indexes, and
  analysis session.

All runtime modes expose the same JSON-RPC contract, so the calling workflow
does not change when you switch between them.

## Install

Pick the entry point you want first:

| Runtime mode | Best when | Install |
| --- | --- | --- |
| **Standalone CLI + backend** | You want an independent runtime for terminal work, CI, or agents | [Install guide](https://kast.michne.com/getting-started/install/) |
| **Headless CLI + backend** | You need a self-contained Ubuntu/Debian CI or hosted-agent runtime | [Ubuntu/Debian bundle](https://kast.michne.com/getting-started/install/#ubuntudebian-bundle) |
| **IDEA / Android Studio plugin-backed runtime** | IDEA or Android Studio is already open and you want to reuse its already-open project model and indexes | [Plugin install guide](https://kast.michne.com/getting-started/install/#install-the-idea-and-android-studio-plugin-manually) · [Latest plugin zip](https://github.com/amichne/kast/releases/latest) |

Install the Rust `kast` CLI with Homebrew when you can:

```console
brew tap amichne/kast
brew install kast
```

Use the Ubuntu/Debian installer when Homebrew is not available and the target
host is an Ubuntu or Debian x86_64 environment:

```console
./scripts/install-ubuntu-debian.sh install
```

That is the only supported non-Brew install path. For mirrored artifacts and
CI images, use the same script with the self-contained Ubuntu/Debian bundle;
the [install guide](https://kast.michne.com/getting-started/install/#ubuntudebian-bundle)
shows the exact environment variables.

Warm the packaged backend before running analysis commands. Ubuntu/Debian bundle
installs should pass `--backend=headless`; Homebrew standalone installs can omit
the selector:

```console
# Start or warm the backend
kast up --backend=headless --workspace-root=/path/to/your/workspace

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

- Read the [documentation site](https://kast.michne.com/).
- Follow the [install guide](https://kast.michne.com/getting-started/install/).
- Compare runtime modes in [Backends](https://kast.michne.com/getting-started/backends/).
