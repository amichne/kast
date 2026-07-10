<a href="https://deepwiki.com/amichne/kast"><img src="https://deepwiki.com/badge.svg" alt="Ask DeepWiki"></a> [![CI](https://github.com/amichne/kast/actions/workflows/ci.yml/badge.svg)](https://github.com/amichne/kast/actions/workflows/ci.yml)

# Kast


`kast` gives Copilot, terminal workflows, CI jobs, and hosted agents
compiler-backed Kotlin answers. Use it when text search can show where a name
appears, but you need to know which declaration it resolves to, which callers
are real, or whether a planned edit is safe to apply.

## Install

For a macOS developer machine, run the installer and then open your project in IntelliJ IDEA or Android Studio.
Kast handles the CLI, matching plugin, and agent-facing project guidance from
there.

```console
/bin/bash -c "$(curl -fsSL https://raw.githubusercontent.com/amichne/kast/main/install.sh)"
```

The installer defaults to the public `amichne/kast` Homebrew tap. It explains
the planned machine changes before mutating anything. Restart the IDE after an
install or update, then open the project you want agents to work in.

Use the Linux headless bundle when a CI runner, hosted agent, server image, or
air-gapped host needs its own binary and backend runtime:

```console
export KAST_UBUNTU_DEBIAN_VERSION="v1.2.3"
./scripts/install-ubuntu-debian.sh install
./scripts/install-ubuntu-debian.sh verify
```

The [macOS install guide](https://kast.michne.com/install/macos/) covers the
root installer and IDE handoff. The [headless Linux guide](https://kast.michne.com/install/headless-linux/)
covers server and hosted-agent installs.

## Try it on your code

Once the workspace is prepared and its backend is ready, run the read-only
repository tour:

```console
kast demo
```

Kast ranks high-signal symbols from the source index, adds live compiler
identity, references, and diagnostics when the backend is available, then
hands each chapter back to an equivalent typed `kast agent` command. It does
not change source files. Use `kast --output json demo` for a deterministic
captured snapshot, or add `--symbol <name>` to choose the story anchor.

The [repository demo guide](https://kast.michne.com/learn/repository-demo/) explains
the full, index-only, and backend-only evidence modes.

## Why Kast instead of text search?

Kast answers questions that `grep` and `rg` cannot answer reliably on their
own:

- **Resolve the exact symbol, not just the spelling.** Kast asks the Kotlin
  analysis engine which declaration a position refers to.
- **Trace usage with semantic context.** Reference and caller queries follow
  compiler-backed relationships instead of matching strings.
- **Plan edits before applying them.** Agent edit flows surface identity,
  scope, and conflict evidence before they touch files.
- **Report completeness and bounds.** Reference and hierarchy responses tell
  agents whether evidence was exhaustive, truncated, or limited.

## Runtime choices

Kast has two runtime modes behind the same command surface:

| Runtime mode | Best when | Install path |
| --- | --- | --- |
| **IDEA / Android Studio plugin backend** | A macOS developer machine uses IDEA or Android Studio for local Kotlin state | Homebrew developer distribution |
| **Headless CLI + backend** | A CI runner, server, or hosted Linux image needs its own runtime | Linux headless bundle |

Repository agent guidance can use either runtime because agents call the same
global `kast` binary and command surface. The Linux headless bundle is a
server/hosted-agent distribution, not the local macOS developer fallback.

On developer machines, the JetBrains plugin starts the Kast backend when the
project opens and can request a Gradle refresh by default. Agents use that backend behind the scenes
when they need compiler-backed evidence.

## Documentation

- Read the [documentation site](https://kast.michne.com/).
- Follow the [macOS install guide](https://kast.michne.com/install/macos/) or
  [headless Linux guide](https://kast.michne.com/install/headless-linux/).
- Run the [first semantic workflow](https://kast.michne.com/learn/first-semantic-workflow/)
  or explore your repository with the
  [read-only demo](https://kast.michne.com/learn/repository-demo/).
- Browse the [command reference](https://kast.michne.com/reference/commands/).
- Use [inspect Kotlin](https://kast.michne.com/use/inspect-kotlin/) and
  [plan safe edits](https://kast.michne.com/use/plan-safe-edits/) for common
  CLI workflows.
