---
title: Install
description: Install the kast CLI, the headless backend, or the IDEA plugin.
icon: lucide/download
---

# Install

`kast` is two pieces: the **CLI** (the `kast` you type) and a **backend**
(the analysis process that does the work). The CLI on its own analyzes
nothing — it routes commands to a backend. Get one running before you
start asking questions.

## Prerequisites

- **Java 21 or newer** on your `PATH` or `JAVA_HOME` when you run a packaged
  JVM backend. The Homebrew CLI package is native and does not install a JDK.
- **macOS, Linux, or Windows.** Homebrew is the preferred local CLI path on
  supported platforms. Ubuntu/Debian x86_64 has the only supported non-Brew
  installer path.

## Homebrew install

Homebrew is the default local developer path when your platform is supported by
the `amichne/kast` tap. `kast` installs the Rust CLI from `amichne/kast`;
`kast-plugin` installs the IDEA plugin bundle from this repository's releases.

```console title="Install kast with Homebrew"
brew tap amichne/kast
brew install kast
brew install kast-plugin
```

Use Homebrew for ordinary terminal use when your platform is supported. Use the
Ubuntu/Debian bundle for every non-Brew installation.

## Ubuntu/Debian bundle

Use the Ubuntu/Debian bundle when a CI image, hosted agent snapshot, or
developer machine should install Kast without Homebrew, Rust, Gradle, or the
IDEA plugin. This is the only supported non-Brew installer path.

The release asset is `kast-ubuntu-debian-x86_64-<version>.tar.gz` with a
matching `.sha256` sidecar. It contains the Rust CLI, the headless backend
portable runtime, `scripts/install-ubuntu-debian.sh`, bundle metadata, and the
license notice.

```bash title="Install Kast on Ubuntu/Debian"
export KAST_UBUNTU_DEBIAN_VERSION="v1.2.3"
./scripts/install-ubuntu-debian.sh install
./scripts/install-ubuntu-debian.sh verify
```

For mirrored artifacts or image builds, point the same installer at an exact
local tarball:

```bash title="Install from a mirrored Ubuntu/Debian bundle"
export KAST_UBUNTU_DEBIAN_VERSION="v1.2.3"
export KAST_UBUNTU_DEBIAN_ARTIFACT_PATH="/artifacts/kast-ubuntu-debian-x86_64-v1.2.3.tar.gz"
./scripts/install-ubuntu-debian.sh install
./scripts/install-ubuntu-debian.sh verify
```

The installer refuses non-Ubuntu/Debian hosts, installs to
`$HOME/.local/share/kast/ubuntu-debian/<version>` by default, symlinks
`$HOME/.local/bin/kast`, and writes `config.toml` so the CLI points at
`lib/backends/headless-<version>/runtime-libs` and the bundled headless
`idea-home`.

Start the bundled backend explicitly as headless:

```bash title="Warm the Ubuntu/Debian headless backend"
kast up --backend=headless --workspace-root="$PWD"
```

Use `scripts/package-ubuntu-debian-bundle.sh` when building the release bundle
from local CLI and backend artifacts:

```bash title="Package the Ubuntu/Debian bundle"
./scripts/package-ubuntu-debian-bundle.sh \
  --cli-archive dist/kast-v1.2.3-linux-x64.zip \
  --backend-archive dist/headless.zip \
  --version v1.2.3 \
  --output dist/kast-ubuntu-debian-x86_64-v1.2.3.tar.gz
```

## Verify release assets

Published releases from `amichne/kast` include CLI zips, the standalone daemon
zip, headless backend zip, IDEA plugin zip, Ubuntu/Debian bundles, bundle
`.sha256` sidecars, `SHA256SUMS`, and `build-provenance.json`. Mirror or
promote the release directory as a unit, then run the same verifier used by CI
before importing Kast artifacts into an internal artifact store:

```bash title="Verify a downloaded release directory"
gh release download v1.2.3 --repo amichne/kast --dir kast-release-v1.2.3
./scripts/verify-release-assets.sh --release-dir kast-release-v1.2.3 --tag v1.2.3
```

The verifier requires exactly the shipped zip and tar asset set, checks each
SHA-256 digest, validates the Ubuntu/Debian bundle sidecar, and confirms that
combined provenance names the same assets and digests.

??? info "Where kast stores configuration"

    By default, `kast` reads user configuration from
    `$HOME/.config/kast/config.toml`. The Ubuntu/Debian installer writes that
    file and keeps managed runtime files under
    `$HOME/.local/share/kast/ubuntu-debian/<version>`:

    - `$HOME/.local/bin/kast` — symlink to the installed CLI
    - `$HOME/.local/share/kast/ubuntu-debian/<version>/bin` — installed CLI
    - `$HOME/.local/share/kast/ubuntu-debian/<version>/lib/backends` —
      headless backend runtime files
    - `$HOME/.local/share/kast/ubuntu-debian/<version>/cache` and `logs` —
      daemon caches and logs

    Set `KAST_CONFIG_HOME` only when you need to move the directory that
    contains `config.toml`:

    ```bash title="Use a non-default config directory"
    export KAST_CONFIG_HOME="$HOME/.config/kast-dev"
    ```

    Most installs don't need a custom config file because the installer writes
    absolute paths. When you override paths, keep them absolute:

    ```toml title="$HOME/.config/kast/config.toml"
    [paths]
    installRoot = "/home/alex/.local/share/kast/ubuntu-debian/v1.2.3"
    binDir = "/home/alex/.local/bin"
    libDir = "/home/alex/.local/share/kast/ubuntu-debian/v1.2.3/lib"
    cacheDir = "/home/alex/.local/share/kast/ubuntu-debian/v1.2.3/cache"
    logsDir = "/home/alex/.local/share/kast/ubuntu-debian/v1.2.3/logs"

    [cli]
    binaryPath = "/home/alex/.local/bin/kast"

    [backends.headless]
    runtimeLibsDir = "/home/alex/.local/share/kast/ubuntu-debian/v1.2.3/lib/backends/headless-v1.2.3/runtime-libs"
    ideaHome = "/home/alex/.local/share/kast/ubuntu-debian/v1.2.3/lib/backends/headless-v1.2.3/idea-home"
    ```

## Ubuntu/Debian installer environment overrides

Most users do not need environment overrides. They are useful for packaged
images, private artifact stores, and CI-style setup scripts.

| Variable | What it does |
|----------|--------------|
| `KAST_UBUNTU_DEBIAN_VERSION` | Selects the release tag to install |
| `KAST_UBUNTU_DEBIAN_ARTIFACT_PATH` | Installs from an exact local bundle tarball |
| `KAST_UBUNTU_DEBIAN_BASE_URL` | Downloads from a mirrored release directory |
| `KAST_UBUNTU_DEBIAN_ROOT` | Overrides the managed install root |
| `KAST_UBUNTU_DEBIAN_BIN_DIR` | Overrides the `kast` symlink directory |
| `KAST_UBUNTU_DEBIAN_CONFIG_HOME` | Overrides the config directory |
| `KAST_JAVA_CMD` | Selects the Java executable used for verification |

## Install the Copilot extension

Install the Copilot extension when you want the repository-local GitHub
Copilot files that ship with `kast`. The command copies packaged agents,
hooks, and native extensions into `.github`, marks scripts executable,
writes `.github/.kast-copilot-version`, and records the managed repo in the
CLI-managed inventory.

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

### Install Copilot extension from IDEA or Android Studio

The IDEA / Android Studio plugin exposes the same install and uninstall flow
from the IDE. The action calls the CLI path from `[cli] binaryPath` in
`config.toml`; it doesn't search `PATH`.

Before using the action, confirm the configured binary exists and is
executable:

```toml title="$HOME/.config/kast/config.toml"
[cli]
binaryPath = "/home/alex/.local/bin/kast"
```

Then use the IDE menu:

1. Open the project in IDEA or Android Studio.
2. Choose **Tools → Kast → Install Copilot Extension**.
3. To remove managed files later, choose
   **Tools → Kast → Uninstall Copilot Extension**.

## Install the IDEA and Android Studio plugin manually

Download the plugin zip and install it from disk:

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

To enable shell completion:

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
- [Backends](backends.md) — standalone, headless, and IDEA, when each one wins
