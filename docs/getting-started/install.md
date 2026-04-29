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

| What you want | Recommended mode | How the backend starts |
|---------------|-----------------|----------------------|
| IDE-backed runtime (IntelliJ already open) | `minimal` | Plugin starts automatically with IntelliJ |
| Terminal, CI, or agent work | `full` | `kast daemon start --workspace-root=<path>` |
| Both | `full` + separate plugin install | Pick the backend you want per session |

The CLI alone does not run analysis. It routes commands to a running backend.
You must have at least one backend running before `kast` analysis commands
will work.

## Prerequisites

Before you install, confirm these are in place:

- **Java 21 or newer** available through `JAVA_HOME` or your shell
  `PATH`. The standalone backend runs on the JVM.
- **macOS, Linux, or Windows** — the installer covers all three.

## One-line install

Run this from any directory to launch the interactive install wizard.

```console linenums="1" title="Install kast (interactive)"
/bin/bash -c "$(curl -fsSL \
  https://raw.githubusercontent.com/amichne/kast/HEAD/kast.sh)"
```

Or via pipe:

```console title="Install via pipe"
curl -fsSL https://raw.githubusercontent.com/amichne/kast/HEAD/kast.sh | bash
```

The wizard detects your environment (running IntelliJ instances, existing
tools, Java), lets you choose an install mode, writes configuration to
`~/.config/kast/env`, and offers to install the Copilot skill.

## Install wizard

Running the installer without flags opens a step-by-step wizard:

1. **Environment detection** — scans for running IntelliJ instances, checks
   for Java and fzf.
2. **Mode selection** — choose `minimal` (CLI + optional IntelliJ plugin) or
   `full` (CLI + standalone JVM backend). If IntelliJ is running, the wizard
   offers to push the plugin directly.
3. **Configuration** — writes `~/.config/kast/env` with `KAST_INSTALL_ROOT`
   and `KAST_BIN_DIR`. Your shell RC file gets a single idempotent source line.
4. **CLI install** — downloads and installs the native `kast` launcher.
5. **Shell completions** — offers to enable tab completion for Bash or Zsh.
6. **Plugin install** — push to running IntelliJ, or download the zip for
   manual install.
7. **Copilot skill** — install the kast skill globally (`~/.agents/skills/kast`),
   locally, or both. Uses fzf if available, otherwise a numbered menu.
8. **Summary** — shows install root, binary path, and next steps.

## Install modes

=== "Minimal (interactive default)"

    ```console title="Minimal install — CLI only"
    ./kast.sh install --mode=minimal
    ```

    Installs the `kast` CLI. The wizard also offers to install the IntelliJ
    plugin (push to running instance or download zip). Ideal if IntelliJ is
    your primary analysis backend.

=== "Full"

    ```console title="Full install — CLI and standalone backend"
    ./kast.sh install --mode=full
    ```

    Installs the `kast` CLI and the standalone JVM backend. Start the backend
    with:

    ```console title="Start the standalone backend"
    kast daemon start --workspace-root=/absolute/path/to/workspace
    ```

=== "Non-interactive (CI)"

    ```console title="Non-interactive — CLI only, no prompts"
    ./kast.sh install --non-interactive
    ```

    Installs the CLI silently, skips all prompts, and skips the Copilot skill
    install. Safe for CI and scripted environments.

=== "Expert (--components)"

    ```console title="Expert — explicit component list"
    ./kast.sh install --components=cli,intellij,backend
    ```

    Skips the wizard entirely and installs exactly the specified components.
    Valid values: `cli`, `intellij`, `backend`, `all`.

## Configuration file

The installer writes all paths to `~/.config/kast/env` (never inline into
`.zshrc`/`.bashrc`). Your RC file gets a single block that sources it:

```bash title="~/.zshrc — added by installer"
# >>> kast env >>>
[[ -f "$HOME/.config/kast/env" ]] && source "$HOME/.config/kast/env"
# <<< kast env <<<
```

The config file itself looks like:

```bash title="~/.config/kast/env"
# >>> kast config >>>
export KAST_INSTALL_ROOT="~/.local/share/kast"
export KAST_BIN_DIR="~/.local/bin"
# export KAST_STANDALONE_RUNTIME_LIBS="..."  (present after full install)
# <<< kast config <<<
```

You can re-run the installer at any time; the config block is updated
in place rather than appended.

## Starting the standalone backend

After a `full` install, start the standalone backend before running
analysis commands:

```console title="Start the standalone backend"
kast daemon start --workspace-root=/absolute/path/to/your/workspace
```

Keep this running in a background terminal or as a background process.
Once it prints `READY`, the `kast` CLI will find it automatically for any
command targeting the same workspace root.

To stop it:

```console title="Stop the standalone backend"
kast daemon stop --workspace-root=/absolute/path/to/your/workspace
```

## Installer flags

| Flag | What it does |
|------|--------------|
| `--mode=minimal\|full\|auto` | Drive the install wizard path (default: interactive) |
| `--components=<list>` | Expert override: `cli`, `intellij`, `backend`, `all` — skips wizard |
| `--skip-skill` | Skip Copilot skill install step |
| `--non-interactive` | Skip all prompts; implies `--skip-skill` |
| `--local` | Install from local `dist/` artifacts (built by `./kast.sh build`) |

## Install the IntelliJ plugin manually

If you prefer to install the plugin without the wizard:

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

The installer prompts to set up completion during install. To enable it
manually after the fact:

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
