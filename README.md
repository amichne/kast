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
- **macOS workspace setup:** open the repository in IDEA or Android Studio with
  the Kast plugin enabled; the plugin prepares the workspace.

Install the macOS developer distribution with Homebrew, then repair JetBrains
profile links if needed:

```console
brew tap amichne/kast
brew install kast
kast developer machine plugin
```

`brew install kast` installs the global CLI and matching IntelliJ plugin
artifact.
Restart IDEA or Android Studio after Homebrew links or refreshes the plugin,
then open the repository. On macOS, the IntelliJ plugin writes the skill-facing
guidance, invocation metadata, and workspace setup manifest. The CLI does not
support skill-only, runtime-only, or resource-only workspace setup on macOS.

Use the Linux headless bundle when a CI runner, hosted agent, server image, or
air-gapped host needs its own binary and backend runtime:

```console
export KAST_UBUNTU_DEBIAN_VERSION="v1.2.3"
./scripts/install-ubuntu-debian.sh install
./scripts/install-ubuntu-debian.sh verify
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
- **Place Kotlin changes with scope evidence.** Typed mutation commands create
  files, insert declarations or statements, and replace declarations from
  content files after a dry-run plan.
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
project opens and can request a Gradle refresh by default. The backend reports
indexing and source-index readiness through `kast agent verify`, so automation
can wait on evidence instead of guessing from IDE state.

## Documentation

- Read the [documentation site](https://kast.michne.com/).
- Follow the [install guide](https://kast.michne.com/getting-started/install/).
- Run the [quickstart](https://kast.michne.com/getting-started/quickstart/).
- Browse the [command manual](https://kast.michne.com/commands/).
- Use [recipes](https://kast.michne.com/recipes/) for common CLI workflows.
