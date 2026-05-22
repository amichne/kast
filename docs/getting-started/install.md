---
title: Install
description: Install the kast CLI, the standalone backend, the IDEA plugin, or any combination.
icon: lucide/download
---

# Install

`kast` is two pieces: the **CLI** (the `kast` you type) and a **backend**
(the analysis process that does the work). The CLI on its own analyzes
nothing — it routes commands to a backend. Get one running before you
start asking questions.

## Prerequisites

- **Java 21 or newer** on your `PATH` or `JAVA_HOME`. Homebrew installs
  `openjdk@21`; the standalone backend is a JVM process and won't start
  without Java.
- **macOS, Linux, or Windows.** Homebrew is the preferred local CLI path on
  supported platforms; the shell installer covers the rest.

## Homebrew install

Homebrew is the default local developer path when your platform is supported by
the `amichne/kast` tap. It installs the stable CLI package and pulls
`openjdk@21` as the Java runtime dependency.

```console title="Install kast with Homebrew"
brew tap amichne/kast
brew install kast
```

Use Homebrew for ordinary terminal use. Use the shell installer when you need
the interactive wizard, a standalone backend install from GitHub release
assets, the IDEA plugin zip, packaged Copilot surfaces, local `dist/`
artifacts, or a non-Homebrew machine.

## Shell installer

The shell installer is still required for portable and full-stack setup flows:
Windows, non-Homebrew machines, local `dist/` artifacts, full standalone
backend installs, plugin zip downloads, and contained CI or agent images.

Run from any directory. The wizard handles the rest.

```console linenums="1" title="Install kast (interactive)"
/bin/bash -c "$(curl -fsSL \
  https://raw.githubusercontent.com/amichne/kast/HEAD/kast.sh)"
```

Or piped:

```console title="Install via pipe"
curl -fsSL https://raw.githubusercontent.com/amichne/kast/HEAD/kast.sh | bash
```

The wizard sniffs your environment (running IDEA-compatible IDEs, existing tools,
Java version), lets you pick an install mode, writes
`$HOME/.config/kast/config.toml`, installs managed files under
`$HOME/.kast`, records the install in `~/.kast/.manifest.json`, and can
install the packaged Copilot surfaces you use next. Interactive downloads
show a progress bar; non-interactive downloads stay quiet unless you override
that with `KAST_DOWNLOAD_PROGRESS`.

??? info "What the wizard does, step by step"

    Most people answer the prompts and move on. If you want the receipts:

    1. **Detect.** Scans for running IDEA and Android Studio instances, checks for
       Java and `fzf`.
    2. **Choose mode.** `minimal` (CLI plus optional plugin) or `full`
       (CLI plus standalone backend). If a supported IDE is running, the wizard
       offers to push the plugin straight in.
    3. **Configure.** Writes `$HOME/.config/kast/config.toml` with the
       install paths, the CLI binary path, and backend runtime paths.
    4. **Install the CLI.** Downloads the native launcher.
    5. **Shell completions.** Bash or Zsh, your call.
    6. **IDEA plugin.** Push to the running IDE, or download the zip
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

Run the one-liner first. Come back here only if you want to pick a path
explicitly.

| What you want                              | Mode                            | How the backend starts                            |
|--------------------------------------------|---------------------------------|---------------------------------------------------|
| IDEA or Android Studio already open on the project | `minimal`              | Plugin starts with the IDE                        |
| Terminal, CI, or agent work                | `full`                          | `kast up --workspace-root="$PWD"`                 |
| Both                                       | `full` + plugin install         | Pin per session with `--backend-name`             |

## Developer, CI, and cloud-agent paths

The installer has several entry points because local development, CI, and
hosted agent bootstraps need different side effects. Pick the smallest
path that matches the machine.

| Environment | Install path | What gets installed | Follow-up command |
|-------------|--------------|---------------------|-------------------|
| Local developer using a terminal | Homebrew or `./kast.sh install --mode=full` | CLI plus standalone backend when using the full installer | `kast up --workspace-root="$PWD"` |
| Local developer using IDEA or Android Studio | `./kast.sh install --mode=minimal` or the plugin zip | CLI plus optional plugin, or plugin only | Open the project in the IDE |
| CI job that only gates a workspace | `./kast.sh install --non-interactive` or release archives | CLI only unless archives include backend components | Start or warm standalone before `kast rpc` |
| GitHub Actions-compatible hosted agent | `amichne/kast-action@v1` or an enterprise mirror | Headless agent bundle installed into a contained root and added to the job environment | Run `kast` directly in later steps |
| Cloud or headless coding agent | `scripts/headless-agent-install.sh` or a headless agent bundle | CLI, standalone backend, packaged skill, and repo-local Copilot extension | `source "$KAST_AGENT_INSTALL_ROOT/kast-env.sh"` |

For CI and cloud agents, keep the runtime isolated from a human shell
profile. Use setup-time environment variables or a bundle, then source the
generated environment file inside the job or image step that runs the
agent.

## GitHub Actions and hosted agents

Use `amichne/kast-action@v1` for GitHub Actions-compatible setup steps,
including hosted coding agents such as Devin when they run inside a workflow.
The action installs the headless agent bundle, adds the `kast` binary to
`PATH`, exports `KAST_CONFIG_HOME`, `KAST_INSTALL_ROOT`,
`KAST_MANAGED_ROOT`, and action-managed runtime roots, and marks the
install as `KAST_INSTALL_SOURCE=action`.

```yaml title="Install Kast in a GitHub Actions-compatible job"
steps:
  - uses: actions/checkout@v5

  - uses: amichne/kast-action@v1
    with:
      version: v1.2.3

  - run: kast --help
```

For enterprise or GHES runners, mirror both the action and the headless agent
bundle. Do not mirror only the action repository: the action consumes
`kast-headless-agent-<version>-linux-x64.zip`, while `SHA256SUMS` is the
checksum source to verify before promotion.

```yaml title="Install Kast from an enterprise mirror"
steps:
  - uses: actions/checkout@v5

  - uses: enterprise-mirror/kast-action@v1
    with:
      version: v1.2.3
      bundle-url: https://github.enterprise.example/org/kast/releases/download/v1.2.3/kast-headless-agent-v1.2.3-linux-x64.zip
      bundle-sha256: "<sha256>"
      skip-copilot-extension: true

  - run: kast --help
```

Set `skip-copilot-extension: true` unless the setup action is approved to
write packaged Copilot files into the checked-out repository. When
`bundle-url` is set, also set a pinned `version` and `bundle-sha256`; the
action rejects mirrored bundle installs without a checksum.

## Install modes

=== "Minimal (interactive default)"

    ```console title="Minimal install — CLI only"
    ./kast.sh install --mode=minimal
    ```

    Installs the `kast` CLI. The wizard also offers the IDEA plugin
    (push to a running IDE, or download the zip). Pick this if the IDE
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

## Headless agent with internal artifacts

Use the headless agent installer when an image or setup step should install
Kast from private artifact URLs instead of GitHub releases. The script keeps
the install contained, writes a sourceable environment file, installs the
packaged skill, installs the repo-local Copilot extension, and verifies the
result before it exits. Verification checks the CLI launcher, standalone
runtime libs, packaged skill, install manifest, Copilot hooks, native
extension files, and executable `resolve-kast.sh` resolver.

Set the direct artifact URLs and run the script from the checked-out
workspace:

```bash title="Install Kast for a headless agent"
export KAST_AGENT_CLI_URL="https://artifacts.example.internal/kast-cli.zip"
export KAST_AGENT_BACKEND_URL="https://artifacts.example.internal/kast-standalone.zip"
export KAST_AGENT_CLI_SHA256="sha256:<cli-digest>"
export KAST_AGENT_BACKEND_SHA256="sha256:<backend-digest>"
export KAST_AGENT_INSTALL_ROOT="$HOME/.kast-agent"
export KAST_AGENT_WORKSPACE="$PWD"

./scripts/headless-agent-install.sh
source "$KAST_AGENT_INSTALL_ROOT/kast-env.sh"
```

`KAST_AGENT_CLI_URL` and `KAST_AGENT_BACKEND_URL` are required. The SHA-256
variables are optional but should be set for CI-like installs. The script
expects `KAST_AGENT_WORKSPACE` to point inside a Git checkout because the
Copilot extension installs into that repository's `.github` directory.

| Variable | Required | What it does |
|----------|----------|--------------|
| `KAST_AGENT_CLI_URL` | Yes | Direct URL for the internal CLI zip |
| `KAST_AGENT_BACKEND_URL` | Yes | Direct URL for the standalone backend zip |
| `KAST_AGENT_CLI_SHA256` | No | Expected SHA-256 for the CLI zip |
| `KAST_AGENT_BACKEND_SHA256` | No | Expected SHA-256 for the backend zip |
| `KAST_AGENT_INSTALL_ROOT` | No | Contained install root, defaulting to `$HOME/.kast-agent` |
| `KAST_AGENT_WORKSPACE` | No | Git workspace for repo-local Copilot extension install |
| `KAST_AGENT_VERSION` | No | Version label written to install metadata |
| `KAST_SKIP_COPILOT_EXTENSION` | No | Set `true` to skip repo-local Copilot extension install |

## Headless agent bundle

Use the headless agent bundle when an image or setup step should install Kast
from one self-contained archive. The bundle carries the CLI zip, standalone
backend zip, checksums, metadata, and an `install.sh` entrypoint that sets the
bundle-local artifact parameters before running the installer.

Download or publish `kast-headless-agent-<version>-linux-x64.zip`, unzip it
on the target machine, and run `install.sh` from the checked-out workspace:

```bash title="Install Kast from a headless agent bundle"
unzip kast-headless-agent-v1.2.3-linux-x64.zip -d kast-agent
cd /path/to/target/workspace
export KAST_AGENT_INSTALL_ROOT="$HOME/.kast-agent"

/path/to/kast-agent/install.sh
source "$KAST_AGENT_INSTALL_ROOT/kast-env.sh"
```

The bundle is self-describing: `README.md` explains the install flow,
`manifest.json` lists the bundle kind, platform, entrypoint, and artifact
digests, and `checksums.txt` records the bundled artifact SHA-256 values.
Run from a Git checkout or set `KAST_AGENT_WORKSPACE` explicitly because the
Copilot extension installs into that repository's `.github` directory and is
verified before `install.sh` exits.

Use `scripts/package-headless-agent-bundle.sh` when you need to create the
same bundle shape from local artifacts:

```bash title="Package a headless agent bundle"
./scripts/package-headless-agent-bundle.sh \
  --cli-archive dist/kast-v1.2.3-linux-x64.zip \
  --backend-archive dist/backend.zip \
  --version v1.2.3 \
  --platform-id linux-x64 \
  --output dist/kast-headless-agent-v1.2.3-linux-x64.zip
```

## Verify release assets

Published releases include the platform zips, headless agent bundle,
`SHA256SUMS`, and `build-provenance.json`. Mirror or promote those files
together, then run the same verifier used by CI before importing them into an
internal artifact store:

```bash title="Verify a downloaded release directory"
gh release download v1.2.3 --repo amichne/kast --dir kast-release-v1.2.3
./scripts/verify-release-assets.sh --release-dir kast-release-v1.2.3 --tag v1.2.3
```

The verifier requires exactly the shipped zip asset set, checks each SHA-256
digest, and confirms that combined provenance names the same assets and
digests.

??? info "Where kast stores configuration"

    By default, `kast` reads user configuration from
    `$HOME/.config/kast/config.toml`. The installer also writes
    `$HOME/.config/kast/env`, which your shell sources to set
    `KAST_CONFIG_HOME`. Managed runtime files live under `$HOME/.kast`:

    - `$HOME/.kast/bin` — the `kast` launcher
    - `$HOME/.kast/releases` and `$HOME/.kast/current` — installed CLI
      releases and the active symlink
    - `$HOME/.kast/backends` and `$HOME/.kast/plugins` — standalone
      backend bits and IDEA plugin zips
    - `$HOME/.kast/lib/skills` — global packaged skills
    - `$HOME/.kast/workspaces` — per-workspace metadata and caches
    - `$HOME/.kast/cache` and `$HOME/.kast/logs` — daemon caches and logs
    - `$HOME/.kast/.manifest.json` — installer-managed inventory,
      including shell patches and repo-local Copilot installs

    The runtime environment variable most installs need after setup is
    `KAST_CONFIG_HOME`. Set it only when you need to move the directory
    that contains `config.toml`:

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
    runtimeLibsDir = "/Users/alex/.kast/backends/current/runtime-libs"
    ```

    Re-run the installer any time. It updates managed files in place.

## Installer flags

| Flag                          | What it does                                                          |
|-------------------------------|-----------------------------------------------------------------------|
| `--mode=minimal\|full\|auto`  | Drive the install wizard path (default: interactive)                  |
| `--components=<list>`         | Expert override: `cli`, `intellij`, `backend`, `all` — skips wizard   |
| `--skip-skill`                | Skip the Copilot skill install step                                   |
| `--skip-copilot-extension`    | Skip the repo-local Copilot extension install step                    |
| `--yes`                       | Auto-install the Copilot extension into `.github` when inside a Git repo |
| `--non-interactive`           | Skip all prompts; implies `--skip-skill` and `--skip-copilot-extension` |
| `--local`                     | Install from local `dist/` artifacts (built by `./kast.sh build`)     |

## Installer environment overrides

Most users do not need environment overrides. They are useful for packaged
images, private artifact stores, and CI-style setup scripts.

| Variable                         | What it does                                      |
|----------------------------------|---------------------------------------------------|
| `KAST_MANAGED_ROOT`              | Overrides the managed install root                |
| `KAST_ARCHIVE_PATH`              | Installs the CLI from a local zip                 |
| `KAST_EXPECTED_SHA256`           | Verifies `KAST_ARCHIVE_PATH` before extraction    |
| `KAST_BACKEND_ARCHIVE_PATH`      | Installs the standalone backend from a local zip  |
| `KAST_BACKEND_EXPECTED_SHA256`   | Verifies `KAST_BACKEND_ARCHIVE_PATH`              |
| `KAST_DOWNLOAD_PROGRESS`         | Sets download display mode: `auto`, `always`, or `never` |
| `KAST_INSTALL_SOURCE`            | Writes a custom source label into install metadata |
| `KAST_SKILL_SCOPE`               | Sets skill scope when prompts are unavailable     |

Do not combine `KAST_SKILL_SCOPE` with `--non-interactive`; that flag
deliberately skips both skill and Copilot extension phases.

## Install the Copilot extension

Install the Copilot extension when you want the repository-local GitHub
Copilot files that ship with `kast`. The command copies packaged agents,
hooks, and native extensions into `.github`, marks scripts executable,
writes `.github/.kast-copilot-version`, and records the managed repo in
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

To remove only packaged files, use the uninstall command:

```console title="Uninstall Copilot agents, hooks, and extensions"
kast uninstall copilot-extension
```

Uninstall removes the packaged manifest entries and the version marker. It
preserves foreign files that you created under `.github`.

When you run `./kast.sh install` from a Git repository, the installer offers
this step for the current repo. Pass `--yes` to auto-install it, or
`--skip-copilot-extension` to skip it.

### Install Copilot extension from IDEA or Android Studio

The IDEA / Android Studio plugin exposes the same install and uninstall flow
from the IDE. The action calls the CLI path from `[cli] binaryPath` in
`config.toml`; it doesn't search `PATH`.

Before using the action, confirm the configured binary exists and is
executable:

```toml title="$HOME/.config/kast/config.toml"
[cli]
binaryPath = "/Users/alex/.kast/bin/kast"
```

Then use the IDE menu:

1. Open the project in IDEA or Android Studio.
2. Choose **Tools → Kast → Install Copilot Extension**.
3. To remove managed files later, choose
   **Tools → Kast → Uninstall Copilot Extension**.

## Install the IDEA and Android Studio plugin manually

Use the shell installer first when a supported IDE is already running on macOS;
it can push the plugin archive directly into the selected IDE's plugin
directory and then asks you to restart the IDE. Skip the wizard if you'd rather
install from disk:

1. Download `kast-intellij-<version>.zip` from the
   [latest release](https://github.com/amichne/kast/releases/latest).
2. In IDEA or Android Studio: **Settings → Plugins → ⚙️ → Install Plugin from Disk** →
   pick the zip.
3. Restart the IDE when prompted.

!!! note
    The IDEA / Android Studio plugin doesn't need the standalone CLI. It reuses the
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
- [Backends](backends.md) — standalone vs IDEA, when each one wins
