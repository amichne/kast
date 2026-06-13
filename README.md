# Kast
[![CI](https://github.com/amichne/kast/actions/workflows/ci.yml/badge.svg)](https://github.com/amichne/kast/actions/workflows/ci.yml)

[![Ask DeepWiki](https://deepwiki.com/badge.svg)](https://deepwiki.com/amichne/kast)

`kast` gives you compiler-backed Kotlin answers in your terminal, CI, or
agent.
Use it when text search can show you where a name appears, but you need to
know which declaration it resolves to, which callers are real, or whether a
planned edit is safe to apply.

`kast` has two independent runtime modes:

- **Headless CLI + backend** — install the Linux headless tarball when you
  need an independent backend.
  The tarball contains the CLI, packaged IDEA-backed runtime, scripts, and
  metadata needed for terminals, CI, hosted agents, and offline images.
- **IDEA / Android Studio plugin-backed runtime** — runs inside a supported
  JetBrains IDE and reuses the IDE's already-open project model, indexes, and
  analysis session.

All runtime modes expose the same JSON-RPC contract, so the calling workflow
does not change when you switch between them.

## Install

Pick the entry point you want first:

| Runtime mode | Best when | Install |
| --- | --- | --- |
| **Headless CLI + backend** | You want an independent runtime for terminal work, CI, agents, or hosted Linux images | [Install guide](https://kast.michne.com/getting-started/install/) |
| **IDEA / Android Studio plugin-backed runtime** | IDEA or Android Studio is already open and you want to reuse its already-open project model and indexes | [Homebrew install guide](https://kast.michne.com/getting-started/install/#homebrew-install) |

Install the macOS developer distribution with Homebrew:

```console
brew tap amichne/kast
brew install kast
brew install --cask kast-plugin
kast setup
```

Or use the platform installer, which delegates to Homebrew on macOS and to the
Linux headless tarball on Ubuntu/Debian:

```console
curl -fsSL https://raw.githubusercontent.com/amichne/kast/main/kast.sh | bash
```

Use the Linux headless tarball when the target host needs a self-contained
headless runtime:

```console
./kast.sh install
```

For mirrored artifacts and CI images, use the same script with the
self-contained Linux headless tarball; the
[install guide](https://kast.michne.com/getting-started/install/#linux-headless-tarball)
shows the exact environment variables.

The bundled compatibility entrypoint `scripts/install-ubuntu-debian.sh install`
delegates to the same root installer.

After the Linux tarball installer has written the headless configuration, warm
the backend before running analysis commands:

```console
# Start or warm the backend
kast up --backend=headless

# Once READY, send JSON-RPC requests through the CLI
kast rpc '{"jsonrpc":"2.0","id":1,"method":"raw/resolve","params":{"position":{"filePath":"/path/to/your/workspace/src/App.kt","offset":42}}}'
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

Use the headless path when you need a fully independent process or when no IDE
is running. Use the IDEA / Android Studio plugin-backed path when the IDE
already has the project open and you want `kast` to piggyback on the IDE's
existing project model and index.

For the full comparison, see
[Backends](https://kast.michne.com/getting-started/backends/).

## Documentation

- Read the [documentation site](https://kast.michne.com/).
- Follow the [install guide](https://kast.michne.com/getting-started/install/).
- Compare runtime modes in [Backends](https://kast.michne.com/getting-started/backends/).
