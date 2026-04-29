---
title: Install
description: Install the kast CLI, the standalone backend, the IntelliJ plugin, or any combination.
icon: lucide/download
---

# Install

`kast` is two pieces: the **CLI** (the `kast` you type) and a **backend**
(the analysis process that does the work). The CLI on its own analyzes
nothing — it routes commands to a backend. Get one running before you
start asking questions.

## Prerequisites

- **Java 21 or newer** on your `PATH` or `JAVA_HOME`. The standalone
  backend is a JVM process; without Java it won't start.
- **macOS, Linux, or Windows.** The installer covers all three.

## One-line install

Run from any directory. The wizard handles the rest.

```console linenums="1" title="Install kast (interactive)"
/bin/bash -c "$(curl -fsSL \
  https://raw.githubusercontent.com/amichne/kast/HEAD/kast.sh)"
```

Or piped:

```console title="Install via pipe"
curl -fsSL https://raw.githubusercontent.com/amichne/kast/HEAD/kast.sh | bash
```

The wizard sniffs your environment (running IntelliJ, existing tools,
Java version), lets you pick an install mode, writes config to
`~/.config/kast/env`, and offers to drop in the Copilot skill.

??? info "What the wizard does, step by step"

    Most people answer the prompts and move on. If you want the receipts:

    1. **Detect.** Scans for running IntelliJ instances, checks for
       Java and `fzf`.
    2. **Choose mode.** `minimal` (CLI plus optional plugin) or `full`
       (CLI plus standalone backend). If IntelliJ is running, the wizard
       offers to push the plugin straight in.
    3. **Configure.** Writes `~/.config/kast/env` with
       `KAST_INSTALL_ROOT` and `KAST_BIN_DIR`. Your shell RC gets one
       idempotent source line — no per-shell sprawl.
    4. **Install the CLI.** Downloads the native launcher.
    5. **Shell completions.** Bash or Zsh, your call.
    6. **IntelliJ plugin.** Push to the running IDE, or download the zip
       for manual install.
    7. **Copilot skill.** Install globally
       (`~/.agents/skills/kast`), per-repo, or both. Uses `fzf` if
       available, falls back to a numbered menu.
    8. **Summary.** Install root, binary path, next steps.

## Choose your setup

Run the one-liner first. Come back here only if you want to pick a path
explicitly.

| What you want                              | Mode                            | How the backend starts                            |
|--------------------------------------------|---------------------------------|---------------------------------------------------|
| IntelliJ already open on the project       | `minimal`                       | Plugin starts with the IDE                        |
| Terminal, CI, or agent work                | `full`                          | `kast workspace ensure --workspace-root=$(pwd)`   |
| Both                                       | `full` + plugin install         | Pin per session with `--backend-name`             |

## Install modes

=== "Minimal (interactive default)"

    ```console title="Minimal install — CLI only"
    ./kast.sh install --mode=minimal
    ```

    Installs the `kast` CLI. The wizard also offers the IntelliJ plugin
    (push to a running IDE, or download the zip). Pick this if IntelliJ
    is your primary backend.

=== "Full"

    ```console title="Full install — CLI and standalone backend"
    ./kast.sh install --mode=full
    ```

    Installs the CLI and the standalone JVM backend. Pick this for
    headless work — CI, agents, machines without an IDE.

=== "Non-interactive (CI)"

    ```console title="Non-interactive — CLI only, no prompts"
    ./kast.sh install --non-interactive
    ```

    CLI only. No prompts, no skill install. Safe for CI and automated
    images.

=== "Expert (--components)"

    ```console title="Expert — explicit component list"
    ./kast.sh install --components=cli,intellij,backend
    ```

    Skips the wizard entirely. Valid components: `cli`, `intellij`,
    `backend`, `all`.

??? info "Where kast stores configuration"

    The installer writes paths to `~/.config/kast/env` instead of inlining
    them into `.zshrc`/`.bashrc`. Your RC file gets one block that
    sources it:

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

    Re-run the installer any time. The block is updated in place, not
    appended.

## Installer flags

| Flag                          | What it does                                                          |
|-------------------------------|-----------------------------------------------------------------------|
| `--mode=minimal\|full\|auto`  | Drive the install wizard path (default: interactive)                  |
| `--components=<list>`         | Expert override: `cli`, `intellij`, `backend`, `all` — skips wizard   |
| `--skip-skill`                | Skip Copilot skill install step                                       |
| `--non-interactive`           | Skip all prompts; implies `--skip-skill`                              |
| `--local`                     | Install from local `dist/` artifacts (built by `./kast.sh build`)     |

## Install the IntelliJ plugin manually

Skip the wizard if you'd rather install from disk:

1. Download `kast-intellij-<version>.zip` from the
   [latest release](https://github.com/amichne/kast/releases/latest).
2. In IntelliJ: **Settings → Plugins → ⚙️ → Install Plugin from Disk** →
   pick the zip.
3. Restart IntelliJ when prompted.

!!! note
    The IntelliJ plugin doesn't need the standalone CLI. It reuses the
    IDE's K2 analysis session, project model, and indexes. Install the
    CLI separately if you also want a terminal entry point.

## Enable shell completion

The installer offers this during setup. To enable it after the fact:

=== "Bash"

    ```console title="Source completion in Bash"
    source <(kast completion bash)
    ```

=== "Zsh"

    ```console title="Source completion in Zsh"
    source <(kast completion zsh)
    ```

## Verify the install

Open a fresh shell so the updated `PATH` takes effect, then:

```console title="Verify kast is on PATH"
kast --help
```

You should see the grouped help page. If not, the binary isn't on your
`PATH` — see [troubleshooting](../troubleshooting.md).

## Next steps

- [Quickstart](quickstart.md) — start a backend, run your first query
- [Backends](backends.md) — standalone vs IntelliJ, when each one wins
