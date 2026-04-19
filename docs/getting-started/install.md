---
title: Install
description: Install the standalone CLI, the IntelliJ plugin, or both.
icon: lucide/download
---

# Install Kast

Kast ships two independent components. Install the one that fits your
workflow, or install both.

- **Standalone CLI** — for terminal workflows, CI pipelines, and LLM
  agents. Includes a native launcher and a JVM daemon.
- **IntelliJ plugin** — for IDE-integrated analysis. Starts
  automatically when IntelliJ opens a project.

## Prerequisites

Before you install, confirm these are in place:

- **Java 21 or newer** available through `JAVA_HOME` or your shell
  `PATH`. The launcher is native-first, but the daemon runs on the JVM.
- **macOS, Linux, or Windows** — the installer covers all three.

## One-line install

Run this from any directory. It downloads the latest release and installs
the standalone CLI.

```console linenums="1" title="Install standalone CLI"
/bin/bash -c "$(curl -fsSL \
  https://raw.githubusercontent.com/amichne/kast/HEAD/kast.sh)"
```

The installer prints a config summary at the end showing the install
root, binary path, and shell RC file path.

## Choose your components

=== "Standalone only"

    ```console title="Default — standalone CLI"
    ./kast.sh install
    ```

    This is the default. It installs the native launcher and the JVM
    daemon runtime.

=== "IntelliJ plugin only"

    ```console title="Install the IntelliJ plugin"
    ./kast.sh install --components=intellij
    ```

    Downloads the plugin zip to `$KAST_INSTALL_ROOT/plugins/`. Then
    install it from disk in IntelliJ: **Settings → Plugins → ⚙️ →
    Install Plugin from Disk**.

=== "Both"

    ```console title="Install everything"
    ./kast.sh install --components=all --non-interactive
    ```

    Installs the standalone CLI and downloads the IntelliJ plugin zip
    in one step. Add `--non-interactive` to skip all prompts.

## Installer flags

| Flag | What it does |
|------|--------------|
| `--components=<list>` | Comma-separated: `standalone`, `intellij`, `all`. Default: `standalone` |
| `--non-interactive` | Skip all interactive prompts |

## Install the IntelliJ plugin manually

If you prefer to install the plugin without the unified installer:

1. Download `kast-intellij-<version>.zip` from the
   [latest release](https://github.com/amichne/kast/releases/latest).
2. In IntelliJ, open **Settings → Plugins → ⚙️ → Install Plugin from
   Disk** and select the zip.
3. Restart IntelliJ when prompted.

!!! note
    The IntelliJ plugin does not require the standalone CLI. It reuses
    the IDE's own K2 analysis session. Install the standalone CLI
    separately if you also want terminal access.

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
