---
title: Install
description: Install the kast CLI, the standalone backend, the IntelliJ plugin, or any combination.
icon: lucide/download
---

# Install

`kast` is split into two independently managed pieces: the **CLI** (the
`kast` command you type) and a **backend** (the analysis process that does
the work). You install and start them separately.

## Choose your setup

| What you want | Install this | How you start the backend |
|---------------|--------------|---------------------------|
| Terminal, CI, or agent work | `kast` CLI + standalone backend | `kast daemon start --workspace-root=<path>` |
| IDE-backed runtime (IntelliJ already open) | IntelliJ plugin | The plugin starts automatically when IntelliJ opens the project |
| Both | CLI + plugin + standalone backend | Pick the backend you want for each session |

The CLI alone does not run analysis. It routes commands to a running backend.
You must have at least one backend running before `kast` analysis commands
will work.

## Prerequisites

Before you install, confirm these are in place:

- **Java 21 or newer** available through `JAVA_HOME` or your shell
  `PATH`. The standalone backend runs on the JVM.
- **macOS, Linux, or Windows** — the installer covers all three.

## One-line install

Run this from any directory to install the `kast` CLI.

```console linenums="1" title="Install the kast CLI"
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

=== "CLI only (default)"

    ```console title="Default — kast CLI"
    ./kast.sh install
    ```

    Installs the native `kast` launcher. You still need a backend running
    before analysis commands work. Start the backend separately with
    `kast daemon start`, or open the project in IntelliJ with the plugin installed.

=== "Standalone backend"

    ```console title="Install CLI and standalone backend"
    ./kast.sh install --components=cli,backend --non-interactive
    ```

    Installs the `kast` CLI and the standalone JVM backend. Start
    the backend with:

    ```console title="Start the standalone backend"
    kast daemon start --workspace-root=/absolute/path/to/workspace
    ```

=== "IntelliJ plugin only"

    ```console title="Install the IntelliJ plugin"
    ./kast.sh install --components=intellij
    ```

    Downloads the plugin zip to `$KAST_INSTALL_ROOT/plugins/`. Then
    install it from disk in IntelliJ: **Settings → Plugins → ⚙️ →
    Install Plugin from Disk**. This path does not require the `kast` CLI.

=== "CLI and IntelliJ plugin"

    ```console title="Install CLI and IntelliJ plugin"
    ./kast.sh install --components=cli,intellij --non-interactive
    ```

    Installs the `kast` CLI and downloads the IntelliJ plugin zip
    in one step. Add `--non-interactive` to skip prompts.

=== "All three"

    ```console title="Install CLI, IntelliJ plugin, and standalone backend"
    ./kast.sh install --components=all --non-interactive
    ```

    Installs the `kast` CLI, the IntelliJ plugin zip, and the standalone
    backend in one step.

## Starting the standalone backend

After installing the CLI, start the standalone backend before running
analysis commands:

```console title="Start the standalone backend"
kast daemon start --workspace-root=/absolute/path/to/your/workspace
```

Keep this running in a background terminal or as a background process.
Once it prints `READY`, the `kast` CLI will find it automatically for any
command targeting the same workspace root.

To stop it, send `SIGTERM` or use:

```console title="Stop the standalone backend"
kast daemon stop --workspace-root=/absolute/path/to/your/workspace
```

## Installer flags

| Flag | What it does |
|------|--------------|
| `--components=<list>` | Comma-separated: `cli`, `intellij`, `backend`, `all`. Default: `cli` |
| `--non-interactive` | Skip all interactive prompts |

## When Gradle files matter

The installer itself does not require Gradle files. They matter later,
when the standalone backend discovers a workspace.

> **Note:** If your workspace root contains `settings.gradle.kts`,
> `settings.gradle`, `build.gradle.kts`, or `build.gradle`, the
> standalone backend uses Gradle-aware discovery. Without those files,
> `kast` still falls back to conventional source roots and source-file
> scanning. A root `settings.gradle.kts` matters most for multi-module
> Gradle workspaces.

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
