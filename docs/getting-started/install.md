---
title: Install
description: Install the kast CLI, the standalone backend, the IntelliJ plugin, or any combination.
icon: lucide/download
---

# Install

`kast` is two pieces: the **CLI** (the `kast` you type) and a **backend**
(the analysis process that does the work). The CLI on its own analyzes nothing — it routes commands to a backend. Get
one running before you start asking questions.

## Prerequisites

- **Java 21 or newer** on your `PATH` or `JAVA_HOME`. The standalone backend is a JVM process; without Java it won't
  start.
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

The wizard sniffs your environment (running IntelliJ, existing tools, Java version), lets you pick an install mode,
writes
`$HOME/.config/kast/config.toml`, installs managed files under
`$HOME/.kast`, records the install in `~/.kast/.manifest.json`, and can install the packaged Copilot surfaces you use
next.

??? info "What the wizard does, step by step"

    Most people answer the prompts and move on. If you want the receipts:

    1. **Detect.** Scans for running IntelliJ instances, checks for
       Java and `fzf`.
    2. **Choose mode.** `minimal` (CLI plus optional plugin) or `full`
       (CLI plus standalone backend). If IntelliJ is running, the wizard
       offers to push the plugin straight in.
    3. **Configure.** Writes `$HOME/.config/kast/config.toml` with the
       install paths, the CLI binary path, and backend runtime paths.
    4. **Install the CLI.** Downloads the native launcher.
    5. **Shell completions.** Bash or Zsh, your call.
    6. **IntelliJ plugin.** Push to the running IDE, or download the zip
       for manual install.
    7. **Copilot skill.** Install globally
       (`~/.kast/lib/skills/kast`), per-repo, or both. Uses `fzf` if
       available, falls back to a numbered menu.
    8. **Copilot extension.** When you're inside a Git repo, install the
       packaged `.github` agents, hooks, and native extensions for that
       workspace.
    9. **Summary.** Install root, binary path, managed manifest, next
       steps.

## Choose your setup

Run the one-liner first. Come back here only if you want to pick a path explicitly.

| What you want                        | Mode                    | How the backend starts                          |
|--------------------------------------|-------------------------|-------------------------------------------------|
| IntelliJ already open on the project | `minimal`               | Plugin starts with the IDE                      |
| Terminal, CI, or agent work          | `full`                  | `kast workspace ensure --workspace-root=$(pwd)` |
| Both                                 | `full` + plugin install | Pin per session with `--backend-name`           |

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

    CLI only. No prompts, no skill install, and no Copilot extension
    install. Safe for CI and automated images.

=== "Expert (--components)"

    ```console title="Expert — explicit component list"
    ./kast.sh install --components=cli,intellij,backend
    ```

    Skips the wizard entirely. Valid components: `cli`, `intellij`,
    `backend`, `all`.

??? info "Where kast stores configuration"

    By default, `kast` reads user configuration from
    `$HOME/.config/kast/config.toml`. The installer also writes
    `$HOME/.config/kast/env`, which your shell sources to set
    `KAST_CONFIG_HOME`. Managed runtime files live under `$HOME/.kast`:

    - `$HOME/.kast/bin` — the `kast` launcher
    - `$HOME/.kast/releases` and `$HOME/.kast/current` — installed CLI
      releases and the active symlink
    - `$HOME/.kast/backends` and `$HOME/.kast/plugins` — standalone
      backend bits and IntelliJ plugin zips
    - `$HOME/.kast/lib/skills` — global packaged skills
    - `$HOME/.kast/workspaces` — per-workspace metadata and caches
    - `$HOME/.kast/cache` and `$HOME/.kast/logs` — daemon caches and logs
    - `$HOME/.kast/.manifest.json` — installer-managed inventory,
      including shell patches and repo-local Copilot installs

    The only `kast`-specific environment variable is `KAST_CONFIG_HOME`.
    Set it only when you need to move the directory that contains
    `config.toml`:

    ```bash title="Use a non-default config directory"
    export KAST_CONFIG_HOME="$HOME/.config/kast-dev"
    ```

    Most installs don't need a custom config file because the defaults
    already point at `$HOME/.kast`. When you override paths, write absolute
    paths in TOML:

    ```toml title="$HOME/.config/kast/config.toml"
    [paths]
    installRoot = "/Users/alex/.kast"
    binDir = "/Users/alex/.kast/bin"
    libDir = "/Users/alex/.kast/lib"
    cacheDir = "/Users/alex/.kast/cache"
    logsDir = "/Users/alex/.kast/logs"

    [cli]
    binaryPath = "/Users/alex/.kast/bin/kast"

    [backends.standalone]
    runtimeLibsDir = "/Users/alex/.kast/lib/backends/current/runtime-libs"
    ```

    Re-run the installer any time. It updates managed files in place.

## Installer flags

| Flag                         | What it does                                                             |
|------------------------------|--------------------------------------------------------------------------|
| `--mode=minimal\|full\|auto` | Drive the install wizard path (default: interactive)                     |
| `--components=<list>`        | Expert override: `cli`, `intellij`, `backend`, `all` — skips wizard      |
| `--skip-skill`               | Skip the Copilot skill install step                                      |
| `--skip-copilot-extension`   | Skip the repo-local Copilot extension install step                       |
| `--yes`                      | Auto-install the Copilot extension into `.github` when inside a Git repo |
| `--non-interactive`          | Skip all prompts; implies `--skip-skill` and `--skip-copilot-extension`  |
| `--local`                    | Install from local `dist/` artifacts (built by `./kast.sh build`)        |

## Install the Copilot extension

Install the Copilot extension when you want the repository-local GitHub Copilot files that ship with `kast`. The command
copies packaged agents, hooks, and native extensions into `.github`, marks scripts executable, writes
`.github/.kast-copilot-version`, and records the managed repo in
`~/.kast/.manifest.json`.

From the repository root, run:

```console title="Install Copilot agents, hooks, and extensions"
kast install copilot-extension
```

The install writes these packaged trees:

- `.github/agents`
- `.github/hooks`
- `.github/extensions`

Pass `--target-dir` when you need to install into another workspace's
`.github` directory. Pass `--yes=true` to replace an older managed copy:

```console title="Install into another workspace"
kast install copilot-extension --target-dir=/Users/alex/work/project/.github --yes=true
```

To remove only packaged files, pass `--uninstall=true`:

```console title="Uninstall Copilot agents, hooks, and extensions"
kast install copilot-extension --uninstall=true
```

Uninstall removes the packaged manifest entries and the version marker. It preserves foreign files that you created
under `.github`.

When you run `./kast.sh install` from a Git repository, the installer offers this step for the current repo. Pass
`--yes` to auto-install it, or
`--skip-copilot-extension` to skip it.

### Install from IntelliJ or Android Studio

The IntelliJ plugin exposes the same install and uninstall flow from the IDE. The action calls the CLI path from
`[cli] binaryPath` in
`config.toml`; it doesn't search `PATH`.

Before using the action, confirm the configured binary exists and is executable:

```toml title="$HOME/.config/kast/config.toml"
[cli]
binaryPath = "/Users/alex/.kast/bin/kast"
```

Then use the IDE menu:

1. Open the project in IntelliJ IDEA or Android Studio.
2. Choose **Tools → Kast → Install Copilot Extension**.
3. To remove managed files later, choose
   **Tools → Kast → Uninstall Copilot Extension**.

## Install the IntelliJ plugin manually

Skip the wizard if you'd rather install from disk:

1. Download `kast-intellij-<version>.zip` from the
   [latest release](https://github.com/amichne/kast/releases/latest).
2. In IntelliJ: **Settings → Plugins → ⚙️ → Install Plugin from Disk** → pick the zip.
3. Restart IntelliJ when prompted.

!!! note The IntelliJ plugin doesn't need the standalone CLI. It reuses the IDE's K2 analysis session, project model,
and indexes. Install the CLI separately if you also want a terminal entry point.

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
