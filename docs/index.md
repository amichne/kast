---
title: Kast
description: Install Kast once on a machine, then add repository-local
  Copilot integrations where agents should use it.
icon: lucide/network
hide:
  - toc
---

# Kast

Kast gives Copilot and other agents compiler-backed Kotlin answers without
asking them to guess from text search. The install model has two scopes:
install the `kast` binary once on the machine, then add Copilot integration
files to each repository where you want agents to use Kast.

## The golden path

Run these commands on a developer machine, then restart IDEA or Android Studio
so the IDE host loads the Homebrew-managed plugin and Copilot picks up the
repository-local files.

```console title="Install Kast globally, then add it to one repository"
brew tap amichne/kast
brew install kast

cd /path/to/your/repository
kast install copilot
```

!!! success "Two scopes, one setup"
    `brew install kast` is the macOS machine-level install. It puts the global
    `kast` binary on `PATH` and installs or refreshes the matching
    `kast-plugin` cask, the same plugin path exposed by
    `brew install --cask kast-plugin`, so local JetBrains profiles link to a
    version-coupled IDE backend.
    `kast install copilot` is a repository-level install. It writes managed
    files under this repository's `.github` directory so Copilot can start
    `kast lsp --stdio`, load Kotlin instructions, and expose Kast tools.

??? tip "When to rerun `kast install copilot`"
    Run it once in every repository where Copilot should use Kast. Rerun it
    with `--force` after upgrading the global binary or when the repository
    files look stale.

??? info "Where the IDEA plugin fits"
    The IDEA or Android Studio plugin is part of the macOS Homebrew developer
    install, not a per-repository choice. Homebrew manages the cask and links
    it into local JetBrains profiles so Kast can reuse the IDE project model
    and indexes on developer machines. Use `brew reinstall --cask kast-plugin`
    only when profile links need direct repair.

## What this gives your agent

Kast is for the work that happens after a prompt asks for real Kotlin
understanding: find the exact declaration, prove a usage list, inspect a call
tree, or plan a safe rename.

- **Symbol identity:** resolve the declaration the compiler sees, not every
  line that happens to match a string.
- **Bounded evidence:** report whether reference and hierarchy results are
  complete, truncated, or limited by a configured bound.
- **Safe edits:** plan rename and edit operations with file hashes before
  writing anything.
- **Workspace awareness:** answer from the Gradle workspace instead of a pile
  of unrelated files.

## Headless Linux servers

Use the Linux headless bundle when the machine is a CI runner, hosted agent,
server snapshot, or image build with no developer IDE. That path installs its
own `kast` binary and bundled headless runtime on the server.

```console title="Install on Ubuntu or Debian from the headless bundle"
export KAST_UBUNTU_DEBIAN_VERSION="v1.2.3"
./scripts/install-ubuntu-debian.sh install
kast up --backend=headless
```

??? info "Why this is separate from Homebrew"
    Homebrew is the developer-machine path. The Ubuntu/Debian bundle is the
    [headless-server path](getting-started/headless-linux.md). Use it when the
    agent needs its own binary, config, runtime libraries, and backend without
    relying on a human shell profile or an already-open IDE. It is not the
    local macOS developer-machine fallback.

## Where to go next

Choose the page that matches the job in front of you. The first two pages are
the main path; reference material stays available after that.

<div class="grid cards" markdown>

-   :octicons-download-24:{ .lg .middle } **Install**

    ---

    Install the macOS developer-machine binary and plugin, then add
    repository-local Copilot files.

    [:octicons-arrow-right-24: Install](getting-started/install.md)

-   :octicons-copilot-24:{ .lg .middle } **Use with agents**

    ---

    Understand what the Copilot package gives an agent and when to use the
    direct CLI fallback.

    [:octicons-arrow-right-24: Agent setup](for-agents/index.md)

-   :octicons-checklist-24:{ .lg .middle } **Supported use cases**

    ---

    See where Kast is meant to help, where it excels, and which complexity can
    stay out of the first install.

    [:octicons-arrow-right-24: Use cases](supported-use-cases.md)

-   :octicons-terminal-24:{ .lg .middle } **Reference**

    ---

    Keep the detailed API, CLI, backend, and architecture material available
    when you need exact behavior.

    [:octicons-arrow-right-24: CLI reference](cli-cheat-sheet.md)

</div>
