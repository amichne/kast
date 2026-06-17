---
title: Install
description: Install the global Kast binary, then add repository-local
  Copilot integrations where agents should use it.
icon: lucide/download
---

# Install

Kast has two install scopes. Keep them separate and the setup stays simple:
the `kast` binary belongs to the machine, while Copilot integration files
belong to each repository.

| Scope | Command | Writes to | Repeat when |
|-------|---------|-----------|-------------|
| Machine | `brew install kast` | Homebrew-managed global binary on `PATH` | A machine needs Kast |
| Repository | `kast install copilot` | The current repository's `.github` directory | A repository should expose Kast to Copilot |
| Headless server | `scripts/install-ubuntu-debian.sh install` | Server-local binary, config, and headless runtime | A Linux agent needs its own backend |

## Developer machine

Use this path on a macOS developer machine. It installs `kast` globally, then
adds the Copilot package to one repository.

```console title="Global binary, then repository Copilot files"
brew tap amichne/kast
brew install kast

cd /path/to/your/repository
kast install copilot
```

Restart the IDE after installing the repository files. Copilot and IDE-hosted
tooling discover `.github/lsp.json`, repository instructions, and custom
agents at startup.

??? success "Global binary install"
    `brew install kast` is machine-level. It installs one `kast` executable
    that can serve many repositories. Confirm the binary is the one you expect
    before debugging repository files:

    ```console
    kast --version
    kast doctor
    ```

??? tip "Repository Copilot integration"
    `kast install copilot` is repository-level. By default, it targets the
    current working directory's `.github` directory. Run it from the repository
    root, or pass an explicit `.github` target:

    ```console title="Install into another repository"
    kast install copilot --target-dir=/Users/alex/work/project/.github --force
    ```

    The command writes managed files for the running CLI version:

    - `.github/lsp.json`
    - `.github/instructions/kast-kotlin.instructions.md`
    - `.github/agents/kast-reader.agent.md`
    - `.github/agents/kast-writer.agent.md`
    - `.github/extensions/kast/extension.mjs`
    - `.github/extensions/kast/_shared/kast-tools.mjs`
    - `.github/extensions/kast/_shared/kast-agents.mjs`
    - `.github/extensions/kast/_shared/commands.json`
    - `.github/.kast-copilot-version`

    Rerun with `--force` after upgrading the global binary or when the
    repository files look stale.

??? info "IDEA and Android Studio plugin"
    The plugin is optional for the first path. Install it when you want Kast to
    reuse an already-open IDEA or Android Studio project model and indexes.
    The plugin is managed by the Homebrew cask and linked into JetBrains
    profiles by the CLI:

    ```console title="Install or repair local IDE profiles"
    brew install --cask kast-plugin
    kast install plugin
    ```

    Restart the IDE after replacing or linking the plugin.

## Headless Linux server

Use the Linux headless bundle for CI runners, hosted agents, server images, or
air-gapped hosts that should not depend on Homebrew or an open developer IDE.
This path installs a server-local `kast` binary plus the packaged headless
runtime.

```bash title="Install Kast on Ubuntu or Debian"
export KAST_UBUNTU_DEBIAN_VERSION="v1.2.3"
./scripts/install-ubuntu-debian.sh install
./scripts/install-ubuntu-debian.sh verify
kast up --backend=headless
```

The release asset is
`kast-ubuntu-debian-headless-x86_64-<version>.tar.gz` with a matching
`.sha256` sidecar. The bundle contains the Rust CLI, one backend portable
runtime, `scripts/install-ubuntu-debian.sh`, metadata, and the license notice.

??? info "Server install details"
    The installer refuses non-Ubuntu/Debian hosts, installs to
    `$HOME/.local/share/kast/ubuntu-debian/<version>` by default, symlinks
    `$HOME/.local/bin/kast`, and writes `config.toml` so the CLI points at
    `lib/backends/headless-<version>/runtime-libs` and the bundled headless
    `idea-home`.

    Java 21 or newer must be available on `PATH` or through `JAVA_HOME` when
    the Linux headless runtime starts.

??? tip "Mirrored artifacts and image builds"
    Point the installer at an exact local tarball when the server pulls from a
    private artifact store or baked image layer:

    ```bash title="Install from a mirrored Linux headless tarball"
    export KAST_UBUNTU_DEBIAN_VERSION="v1.2.3"
    export KAST_UBUNTU_DEBIAN_ARTIFACT_PATH="/artifacts/kast-ubuntu-debian-headless-x86_64-v1.2.3.tar.gz"
    ./scripts/install-ubuntu-debian.sh install
    ./scripts/install-ubuntu-debian.sh verify
    ```

??? question "Ubuntu/Debian installer overrides"
    Most installs do not need environment overrides. Use them only for
    packaged images, private artifact stores, and CI setup scripts.

    | Variable | What it does |
    |----------|--------------|
    | `KAST_UBUNTU_DEBIAN_VERSION` | Selects the release tag to install |
    | `KAST_UBUNTU_DEBIAN_ARTIFACT_PATH` | Installs from an exact local bundle tarball |
    | `KAST_UBUNTU_DEBIAN_BASE_URL` | Downloads from a mirrored release directory |
    | `KAST_UBUNTU_DEBIAN_ROOT` | Overrides the managed install root |
    | `KAST_UBUNTU_DEBIAN_BIN_DIR` | Overrides the `kast` symlink directory |
    | `KAST_UBUNTU_DEBIAN_CONFIG_HOME` | Overrides the config directory |
    | `KAST_JAVA_CMD` | Selects the Java executable used for verification |

## Repair and setup commands

Most readers do not need these commands on the first pass. Use them when an
existing install is stale, a shell profile needs to be updated, or a local IDE
profile needs repair.

??? question "Repair stale managed files"
    Use `kast install affected` after upgrading Kast, moving between install
    methods, or seeing `kast doctor` report stale managed paths. The default
    mode is a dry run:

    ```console title="Audit affected installs"
    kast install affected
    ```

    Apply the planned repair with `--apply`. The command creates backups under
    `KAST_CONFIG_HOME/backups` before replacing or removing managed files.

    ```console title="Repair affected installs"
    kast install affected --apply
    ```

??? info "One-command local setup"
    `kast setup` installs or refreshes local integrations and managed assets
    from the installed CLI. It is useful for local repair, but it is not the
    clearest first-run story because it crosses several scopes at once.

    ```console title="Refresh local integrations"
    kast setup
    ```

    Disable individual parts with `--skip-repair`, `--skip-shell`,
    `--skip-plugin`, `--skip-skill`, or `--skip-copilot`.

??? info "Shell integration"
    Use `kast install shell` to add the directory that contains the active
    `kast` binary to your `PATH`, export the active `KAST_CONFIG_HOME`, and
    source completions from a managed file under `KAST_CONFIG_HOME/shell`.

    === "Bash"

        ```console title="Install Bash integration"
        kast install shell --shell bash
        ```

    === "Zsh"

        ```console title="Install Zsh integration"
        kast install shell --shell zsh
        ```

## Source checkout development

From this source checkout, the development script installs the same
`cli-rs/resources/plugin/` Copilot package into another repository root. This
is for validating unreleased package changes, not for ordinary users.

```console title="Install Copilot package from this checkout"
cli-rs/resources/plugin/scripts/install-local.sh --target /Users/alex/work/project --force
```

Validate the source package with `.github/scripts/test-kast-copilot-plugin.sh`.
For live Copilot CLI validation of the SDK extension tools, load the source
package explicitly with `--plugin-dir cli-rs/resources/plugin`. Project
installs expose the agents as `kast-reader` and `kast-writer`; source-plugin
validation exposes them under the plugin namespace, such as
`kast-copilot-lsp:kast-reader`.

Use the development Gradle task when you need a local debug CLI and IDEA plugin
from the checkout:

```console title="Install local development CLI and plugin"
./gradlew installDevelopmentLocal
```

## Release asset verification

Published releases include CLI zips, the IDEA plugin zip, the Linux headless
tarball with its `.sha256` sidecar, `SHA256SUMS`, and
`build-provenance.json`. Mirror or promote the release directory as a unit,
then run the same verifier used by CI before importing Kast artifacts into an
internal store.

```bash title="Verify a downloaded release directory"
gh release download v1.2.3 --repo amichne/kast --dir kast-release-v1.2.3
./scripts/verify-release-assets.sh --release-dir kast-release-v1.2.3 --tag v1.2.3
```

Use `scripts/package-ubuntu-debian-bundle.sh` only when building the release
bundle from local CLI and backend artifacts.

## Next steps

After installation, choose the path that matches your workflow.

- [Use Kast with agents](../for-agents/index.md) explains what the Copilot
  package gives an agent.
- [Supported use cases](../supported-use-cases.md) describes where Kast is
  meant to help.
- [Troubleshooting](../troubleshooting.md) covers stale repository files,
  missing binaries, and backend startup issues.
