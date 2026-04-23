---
title: Install
description: Install the standalone CLI, the IntelliJ plugin, or both.
icon: lucide/download
---

# Install

`kast` supports two fully independent runtime modes. You can install the
standalone CLI path for terminal, CI, and agent workflows, the IntelliJ
plugin path for IDE-hosted analysis, or both when you move between them.

## Choose a runtime mode

Use this table to pick the install path that matches how you work.

| Runtime mode | Install this | Best when | What you gain |
|--------------|--------------|-----------|---------------|
| Standalone CLI + daemon | `kast` CLI | You work in a terminal, CI, or an agent | A self-managed headless path that works without IntelliJ |
| IntelliJ plugin-backed runtime | IntelliJ plugin | IntelliJ IDEA already has the project open | Reuse IntelliJ's already-open project model and indexes without a second analysis JVM |
| Both | CLI + plugin | You switch between terminal and IDE workflows | A headless path when the IDE is closed, plus instant reuse when it is open |

Start with [One-line install](#one-line-install) for the standalone CLI path.
If you want the plugin entry point instead, jump to [Install options](#install-options)
or [Install the IntelliJ plugin manually](#install-the-intellij-plugin-manually).

## Prerequisites

Before you install, confirm these are in place:

- **Java 21 or newer** available through `JAVA_HOME` or your shell
  `PATH`. The launcher is native-first, but the daemon runs on the JVM.
- **macOS, Linux, or Windows** — the installer covers all three.

## One-line install

Run this from any directory to install the standalone CLI path. This is
the fastest way to get `kast` running in a terminal, CI job, or agent.

```console linenums="1" title="Install standalone CLI"
/bin/bash -c "$(curl -fsSL \
  https://raw.githubusercontent.com/amichne/kast/HEAD/kast.sh)"
```

Or via pipe:

```console title="Install via pipe"
curl -fsSL https://raw.githubusercontent.com/amichne/kast/HEAD/kast.sh | bash
```

The installer prints a config summary at the end showing the install
root, binary path, and shell RC file path.

## Install options

Use these install commands when you want a specific combination of
components.

=== "Standalone only"

    ```console title="Default — standalone CLI"
    ./kast.sh install
    ```

    This is the default. It installs the native launcher and the
    packaged standalone daemon runtime. You do not need IntelliJ for
    this path.

=== "IntelliJ plugin only"

    ```console title="Install the IntelliJ plugin"
    ./kast.sh install --components=intellij
    ```

    Downloads the plugin zip to `$KAST_INSTALL_ROOT/plugins/`. Then
    install it from disk in IntelliJ: **Settings → Plugins → ⚙️ →
    Install Plugin from Disk**. This path does not require the
    standalone CLI.

=== "Both"

    ```console title="Install everything"
    ./kast.sh install --components=all --non-interactive
    ```

    Installs the standalone CLI and downloads the IntelliJ plugin zip
    in one step. Add `--non-interactive` to skip prompts.

If both backends are available for the same workspace, `kast` prefers a
running IntelliJ backend by default. Add
`--backend-name=standalone` when you want to pin commands to the
self-managed standalone path.

## Installer flags

| Flag | What it does |
|------|--------------|
| `--components=<list>` | Comma-separated: `standalone`, `intellij`, `all`. Default: `standalone` |
| `--non-interactive` | Skip all interactive prompts |

## When Gradle files matter

The installer itself does not require Gradle files. They matter later,
when the standalone backend discovers a workspace.

> **Note:** If your workspace root contains `settings.gradle.kts`,
> `settings.gradle`, `build.gradle.kts`, or `build.gradle`, the
> standalone backend uses Gradle-aware discovery. Without those files,
> `kast` still falls back to conventional source roots and source-file
> scanning. A root `settings.gradle.kts` matters most for multi-module
> Gradle workspaces and for repo-cloning demo flows such as
> `kast demo generate`.

## Install the IntelliJ plugin manually

If you prefer to install the plugin without the unified installer:

1. Download `kast-intellij-<version>.zip` from the
   [latest release](https://github.com/amichne/kast/releases/latest).
2. In IntelliJ, open **Settings → Plugins → ⚙️ → Install Plugin from
   Disk** and select the zip.
3. Restart IntelliJ when prompted.

!!! note
    The IntelliJ plugin does not require the standalone CLI. It reuses
    the IDE's already-open K2 analysis session, project model, and
    indexes. Install the standalone CLI separately if you also want
    terminal access.

## Enable shell completion

The installer can set up completion in your shell init file. If you
skip the prompt or want to enable it manually:

=== "Bash"

    ```console title="Source completion in Bash"
    source <(kast completion bash)
    ```

=== "Zsh"

    ```console title="Source completion in Zsh"
    source <(kast completion zsh)
    ```

## Verify the install

Open a new shell session so the updated `PATH` takes effect, then run:

```console title="Verify kast is on PATH"
kast --help
```

You should see the grouped help page with available commands.

## Next steps

- [Quickstart](quickstart.md) — run your first analysis command
- [Backends](backends.md) — understand standalone vs IntelliJ plugin
