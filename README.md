# Kast
[![CI](https://github.com/amichne/kast/actions/workflows/ci.yml/badge.svg)](https://github.com/amichne/kast/actions/workflows/ci.yml)

[![Ask DeepWiki](https://deepwiki.com/badge.svg)](https://deepwiki.com/amichne/kast)

`kast` gives Copilot, terminal workflows, CI jobs, and hosted agents
compiler-backed Kotlin answers. Use it when text search can show where a name
appears, but you need to know which declaration it resolves to, which callers
are real, or whether a planned edit is safe to apply.

## Install

Keep the install scopes separate:

- **Machine install:** put the global `kast` binary on the machine once.
- **Repository install:** add Copilot integration files to each repository
  where agents should use Kast.

Install the macOS developer binary with Homebrew, then add the Copilot package
to a repository:

```console
brew tap amichne/kast
brew install kast

cd /path/to/your/repository
kast install copilot
```

Restart the IDE after installing or refreshing repository files. The repository
install writes managed files under `.github`, including the LSP config,
Kotlin instructions, `kast-reader`, `kast-writer`, and catalog-backed
extension tools.

Use the Linux headless bundle when a CI runner, hosted agent, server image, or
air-gapped host needs its own binary and backend runtime:

```console
export KAST_UBUNTU_DEBIAN_VERSION="v1.2.3"
./scripts/install-ubuntu-debian.sh install
./scripts/install-ubuntu-debian.sh verify
kast up --backend=headless
```

The [install guide](https://kast.michne.com/getting-started/install/) covers
Homebrew, repository Copilot files, optional IDEA plugin setup, repair
commands, and mirrored Linux artifacts.

## Why Kast instead of text search?

Kast answers questions that `grep` and `rg` cannot answer reliably on their
own:

- **Resolve the exact symbol, not just the spelling.** Kast asks the Kotlin
  analysis engine which declaration a position refers to.
- **Trace usage with semantic context.** Reference and caller queries follow
  compiler-backed relationships instead of matching strings.
- **Plan edits before applying them.** Rename and edit flows surface conflicts
  before they touch files.
- **Report completeness and bounds.** Reference and hierarchy responses tell
  agents whether evidence was exhaustive, truncated, or limited.

## Runtime choices

Kast has two runtime modes behind the same JSON-RPC contract:

| Runtime mode | Best when | Install path |
| --- | --- | --- |
| **Headless CLI + backend** | The agent needs an independent runtime for terminal work, CI, servers, or hosted Linux images | Linux headless bundle |
| **IDEA / Android Studio plugin backend** | IDEA or Android Studio is already open and you want to reuse its project model and indexes | Optional Homebrew cask plus `kast install plugin` |

The repository Copilot package can use either runtime because it starts the
same global `kast` binary and speaks the same protocol.

## Documentation

- Read the [documentation site](https://kast.michne.com/).
- Follow the [install guide](https://kast.michne.com/getting-started/install/).
- Review [supported use cases](https://kast.michne.com/supported-use-cases/).
- Compare runtime modes in [Backends](https://kast.michne.com/getting-started/backends/).
