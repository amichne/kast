<a href="https://deepwiki.com/amichne/kast"><img src="https://deepwiki.com/badge.svg" alt="Ask DeepWiki"></a> [![CI](https://github.com/amichne/kast/actions/workflows/ci.yml/badge.svg)](https://github.com/amichne/kast/actions/workflows/ci.yml)

# Kast


`kast` gives Copilot, terminal workflows, CI jobs, and hosted agents
compiler-backed Kotlin answers. Use it when text search can show where a name
appears, but you need to know which declaration it resolves to, which callers
are real, or whether a planned edit is safe to apply.

## Install

Keep the install scopes separate:

- **Machine install:** put the global `kast` binary and version-coupled IDEA
  plugin on the machine once.
- **Repository setup:** run one command in each repository where agents should
  use Kast.

Install the macOS developer distribution with Homebrew, then set up a
repository:

```console
brew tap amichne/kast
brew install kast

cd /path/to/your/repository
kast setup
```

Use `kast setup --dry-run` to preview repository guidance, optional IDEA
onboarding, and runtime warmup before writing files or starting a backend.

`brew install kast` installs or refreshes the matching `kast-plugin` cask, the
same cask path exposed by `brew install --cask kast-plugin` for direct repair.
Restart IDEA or Android Studio after Homebrew links or refreshes the plugin.
Repository setup writes the shared Kast skill and managed `AGENTS.md` guidance,
then warms the selected backend.

Use the Linux headless bundle when a CI runner, hosted agent, server image, or
air-gapped host needs its own binary and backend runtime:

```console
export KAST_UBUNTU_DEBIAN_VERSION="v1.2.3"
./scripts/install-ubuntu-debian.sh install
./scripts/install-ubuntu-debian.sh verify
kast setup --backend=headless --no-open-ide
```

The [install guide](https://kast.michne.com/getting-started/install/) covers
the Homebrew CLI and IDEA plugin, repository setup, manifest-backed paths, and
repair commands. The [headless Linux guide](https://kast.michne.com/getting-started/headless-linux/)
covers server and hosted-agent installs.

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

Kast has two runtime modes behind the same command surface:

| Runtime mode | Best when | Install path |
| --- | --- | --- |
| **IDEA / Android Studio plugin backend** | A macOS developer machine uses IDEA or Android Studio for local Kotlin state | Homebrew formula plus `kast-plugin` cask |
| **Headless CLI + backend** | A CI runner, server, or hosted Linux image needs its own runtime | Linux headless bundle |

Repository agent guidance can use either runtime because agents call the same
global `kast` binary and command surface. The Linux headless bundle is a
server/hosted-agent distribution, not the local macOS developer fallback.

## Documentation

- Read the [documentation site](https://kast.michne.com/).
- Follow the [install guide](https://kast.michne.com/getting-started/install/).
- Run the [quickstart](https://kast.michne.com/getting-started/quickstart/).
- Browse the [command manual](https://kast.michne.com/commands/).
- Use [recipes](https://kast.michne.com/recipes/) for common CLI workflows.
