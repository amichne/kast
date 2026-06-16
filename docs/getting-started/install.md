---
title: Install
description: Install Kast through Homebrew on macOS or the Linux headless tarball.
icon: lucide/download
---

# Install

Kast has two supported distribution paths:

- **macOS developer installs use Homebrew.** Homebrew owns the CLI, IDEA
  integration assets, local updates, and profile linking.
- **Linux headless installs use one self-contained tarball.** The tarball owns
  the CLI, packaged headless runtime, install scripts, metadata, and headless
  configuration.

## Prerequisites

- **Java 21 or newer** on your `PATH` or `JAVA_HOME` when you run the Linux
  headless runtime. The Homebrew CLI package is native and does not install a
  JDK.
- **macOS for Homebrew developer installs** and **Linux for headless
  tarballs**. Other local installation shapes are not supported distribution
  paths.

## Homebrew install

Homebrew is the macOS developer distribution. `kast` installs the Rust CLI from
`amichne/kast`; `kast-plugin` installs the IDEA plugin bundle from the same
release stream and links it into JetBrains profiles.

```console title="Install kast with Homebrew"
brew tap amichne/kast
brew install kast
brew install --cask kast-plugin
kast setup
```

Use Homebrew for ordinary macOS local use. `kast setup` installs shell
integration, repairs managed resources, and on macOS installs or refreshes the
IDEA plugin cask when JetBrains profile directories are present. Disable
individual parts with `--skip-repair`, `--skip-shell`, `--skip-plugin`,
`--skip-skill`, or `--skip-copilot`.

## Repair affected local installs

Use `kast install affected` after upgrading Kast, moving between install
methods, or seeing `kast doctor` report stale managed paths. The default mode
is a dry run: it audits global config, retired backend state, installed Kast
skills, managed Copilot plugin copies, managed shell source files, and existing
JetBrains profile plugin links without changing files.

```console title="Audit affected installs"
kast install affected
```

To apply the planned repair, rerun with `--apply`. The command creates backups
under `KAST_CONFIG_HOME/backups` before replacing or removing managed files.
If `config.toml` is malformed, apply mode preserves the original file in that
backup directory, writes safe default settings, and reports the recovery in the
repair result.

```console title="Repair affected installs"
kast install affected --apply
```

Headless deployment is not repaired by downloading a separate backend. If
repair removes stale headless metadata, reinstall or refresh the Linux
headless tarball that owns that runtime.

## Linux headless tarball

Use the Linux headless tarball when a CI image, hosted agent snapshot, mirror,
or air-gapped host should install Kast without Homebrew, Rust, Gradle, or
network access to individual release assets. This is the only supported
headless deployment path.

The release asset is `kast-ubuntu-debian-headless-x86_64-<version>.tar.gz`
with a matching `.sha256` sidecar. Each bundle contains the
Rust CLI, one backend portable runtime, `scripts/install-ubuntu-debian.sh`,
bundle metadata, and the license notice.

Linux headless tarballs are built, validated, and published by the normal
release workflow. They are part of the release manifest and are verified before
the release is published.

```bash title="Install Kast on Ubuntu/Debian"
export KAST_UBUNTU_DEBIAN_VERSION="v1.2.3"
./scripts/install-ubuntu-debian.sh install
./scripts/install-ubuntu-debian.sh verify
```

For mirrored artifacts or image builds, point the same installer at an exact
local tarball:

```bash title="Install from a mirrored Linux headless tarball"
export KAST_UBUNTU_DEBIAN_VERSION="v1.2.3"
export KAST_UBUNTU_DEBIAN_ARTIFACT_PATH="/artifacts/kast-ubuntu-debian-headless-x86_64-v1.2.3.tar.gz"
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
kast up --backend=headless
```

Use `scripts/package-ubuntu-debian-bundle.sh` when building the release bundle
from local CLI and backend artifacts:

```bash title="Package the Ubuntu/Debian bundle"
./scripts/package-ubuntu-debian-bundle.sh \
  --cli-archive dist/kast-v1.2.3-linux-x64.zip \
  --backend-archive dist/headless.zip \
  --version v1.2.3 \
  --output dist/kast-ubuntu-debian-headless-x86_64-v1.2.3.tar.gz
```

## Verify release assets

Published releases from `amichne/kast` include CLI zips, the IDEA plugin zip,
the Linux headless tarball with its `.sha256` sidecar, `SHA256SUMS`, and
`build-provenance.json`. Mirror or promote the release directory as a unit,
then run the same verifier used by CI before importing Kast artifacts into an
internal artifact store:

```bash title="Verify a downloaded release directory"
gh release download v1.2.3 --repo amichne/kast --dir kast-release-v1.2.3
./scripts/verify-release-assets.sh --release-dir kast-release-v1.2.3 --tag v1.2.3
```

The verifier uses `build-provenance.json` as the release manifest, checks each
SHA-256 digest, requires the Linux headless tarball sidecar, and rejects assets
not named by provenance.

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

## Install the Copilot LSP package

Install the Copilot LSP package when you want repository-local GitHub
Copilot files that use standard LSP, Kotlin instructions, two Kast-routed
custom agents, and the catalog-backed `kast_*` extension source.

From an installed CLI, run:

```console title="Install Copilot LSP package"
kast install copilot
```

The CLI writes these packaged entries:

- `.github/lsp.json`
- `.github/instructions/kast-kotlin.instructions.md`
- `.github/agents/kast-reader.agent.md`
- `.github/agents/kast-writer.agent.md`
- `.github/extensions/kast/extension.mjs`
- `.github/.kast-copilot-version`

Pass `--target-dir` when you need to install into another workspace's
`.github` directory. Pass `--force` to replace an older managed copy:

```console title="Install into another workspace"
kast install copilot --target-dir=/Users/alex/work/project/.github --force
```

From this source checkout, the development script installs the same
`cli-rs/resources/plugin/` package into a target repository root:

```console title="Install Copilot LSP package from a checkout"
cli-rs/resources/plugin/scripts/install-local.sh --target /Users/alex/work/project --force
```

Validate the source package with `.github/scripts/test-kast-copilot-plugin.sh`.
For live Copilot CLI validation of the SDK extension tools, load the source
package explicitly with `--plugin-dir cli-rs/resources/plugin`. Project
installs expose the agents as `kast-reader` and `kast-writer`; source-plugin
validation exposes them under the plugin namespace, such as
`kast-copilot-lsp:kast-reader`.

To refresh packaged files in place, reinstall with `--force`. This replaces
the managed LSP package file.

### IDEA and Android Studio plugin role

Install or refresh the IDEA / Android Studio plugin through Homebrew and the CLI
profile-link command:

```console title="Install and link local IDE profiles"
kast install plugin
```

!!! note
    The IDEA / Android Studio plugin is installed through the Homebrew cask and
    linked into JetBrains profile directories. Inside the IDE, Kast stays
    focused on diagnostics and the IDE-hosted analysis backend; it does not
    duplicate CLI install workflows.

## Install a local development build

From a repository checkout, use the development install task to build the
debug Rust CLI, install it as `kast-dev`, wire shell integration for that
binary, build the IDEA plugin, and replace the plugin in your newest local
IntelliJ IDEA profile:

```console title="Install local development CLI and plugin"
./gradlew installDevelopmentLocal
```

Use properties when Gradle should target a specific shell profile or IDE
profile:

```console title="Install into explicit local targets"
./gradlew installDevelopmentLocal \
  -PkastDevShell=zsh \
  -PkastDevShellProfile="$HOME/.zshrc" \
  -PkastDevJetBrainsProfile=IntelliJIdea2025.3
```

If auto-detection is not enough, pass the plugins directory directly with
`-PkastDevJetBrainsPluginsDir="<profile>/plugins"`. Restart the IDE after
replacing the plugin.

## Install shell integration

Use `kast install shell` to add the directory that contains the active `kast`
binary to your `PATH`, export the active `KAST_CONFIG_HOME`, and source
completions from a managed file under `KAST_CONFIG_HOME/shell`. When the command
name cannot be resolved, Kast falls back to the configured `binDir`.

=== "Bash"

    ```console title="Install Bash integration"
    kast install shell --shell bash
    ```

=== "Zsh"

    ```console title="Install Zsh integration"
    kast install shell --shell zsh
    ```

For only a local development CLI, use `installDevelopmentShell` so the
generated profile block targets `kast-dev`:

```console title="Install kast-dev shell integration"
./gradlew installDevelopmentShell -PkastDevShell=zsh
```

If you only need completion code for packaging or manual sourcing, print it
directly:

```console title="Print completion code"
kast install completion zsh
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
- [Backends](backends.md) — headless and IDEA, when each one wins
