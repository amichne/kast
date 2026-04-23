# Kast
[![CI](https://github.com/amichne/kast/actions/workflows/ci.yml/badge.svg)](https://github.com/amichne/kast/actions/workflows/ci.yml) [![DeepWiki](https://deepwiki.com/badge.svg)](https://deepwiki.com/amichne/kast)

`kast` gives you compiler-backed Kotlin answers in your terminal, CI, or
agent.
Use it when text search can show you where a name appears, but you need to
know which declaration it resolves to, which callers are real, or whether a
planned edit is safe to apply.

`kast` has two independent runtime modes:

- **Standalone CLI + daemon** runs fully independently from IntelliJ. The
  `kast` command starts and talks to a separate backend process for terminal
  workflows, CI, and headless agents.
- **IntelliJ plugin-backed runtime** runs inside IntelliJ IDEA and reuses the
  IDE's already-open project model, indexes, and analysis session.

Both runtime modes expose the same JSON-RPC contract, so the calling workflow
does not change when you switch between them.

## Install

Pick the entry point you want first:

| Runtime mode | Best when | Install |
| --- | --- | --- |
| **Standalone CLI + daemon** | You want an independent runtime for terminal work, CI, or agents | [Install guide](https://amichne.github.io/kast/getting-started/install/) |
| **IntelliJ plugin-backed runtime** | IntelliJ is already open and you want to reuse its already-open project model and indexes | [Plugin install guide](https://amichne.github.io/kast/getting-started/install/#install-the-intellij-plugin-manually) · [Latest plugin zip](https://github.com/amichne/kast/releases/latest) |

Install the standalone CLI from any shell with a copyable one-line command:

```console
/bin/bash -c "$(curl -fsSL https://raw.githubusercontent.com/amichne/kast/HEAD/kast.sh)"
```

Or via pipe:

```console
curl -fsSL https://raw.githubusercontent.com/amichne/kast/HEAD/kast.sh | bash
```

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
IDE is running. Use the IntelliJ plugin-backed path when IntelliJ already has
the project open and you want `kast` to piggyback on the IDE's existing
project model and index.

For the full comparison, see
[Backends](https://amichne.github.io/kast/getting-started/backends/).

## See it on your code

`kast demo` opens a live Kotter shell on your own Kotlin workspace. It picks a
symbol (or uses `--symbol`) and lets you switch between semantic references,
rename dry-run, and incoming callers while keeping the grep baseline visible.
Run it in a reasonably wide terminal; if the terminal is too narrow, `kast`
stops and asks you to rerun after resizing.

```console
kast demo --workspace-root=/path/to/your/kotlin/project
kast demo --workspace-root=/path/to/your/kotlin/project --symbol=YourClassName
```

## Documentation

- Documentation site: <https://amichne.github.io/kast/>
- Install guide: <https://amichne.github.io/kast/getting-started/install/>
- Backend comparison: <https://amichne.github.io/kast/getting-started/backends/>
