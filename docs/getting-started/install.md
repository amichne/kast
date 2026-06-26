---
title: Install
description: Install the macOS Homebrew CLI and IDEA plugin, then add
  repository-local Copilot integrations where agents should use it.
icon: lucide/download
---

# Developer Machine Install

This page is the macOS developer-machine path. Homebrew owns the developer
machine's CLI and IDEA or Android Studio plugin, while Copilot integration
files belong to each repository.

| Scope | Command | Writes to | Repeat when |
|-------|---------|-----------|-------------|
| Machine CLI + IDE plugin | `brew install kast` | Homebrew-managed global binary on `PATH` and version-coupled `kast-plugin` cask | A macOS developer machine needs Kast |
| Machine IDE plugin repair | `brew reinstall --cask kast-plugin` | Homebrew-managed plugin linked into local JetBrains profiles | Local IDE profile links need repair |
| Repository | `kast install copilot` | The current repository's `.github` directory | A repository should expose Kast to Copilot |

Linux CI, hosted agents, and server images use the separate
[Headless Linux server](headless-linux.md) install path.

## Developer machine

Use this path on a macOS developer machine. A functional Homebrew install
includes the global `kast` binary and the Homebrew-managed IDEA or Android
Studio plugin, then the repository install adds Copilot package files to one
repository.

```console title="Global binary, then repository Copilot files"
brew tap amichne/kast
brew install kast

cd /path/to/your/repository
kast install copilot
```

`brew install kast` installs or refreshes the matching `kast-plugin` cask as
part of the Homebrew formula install, using the same cask path as
`brew install --cask kast-plugin`. Restart IDEA or Android Studio after
Homebrew links or refreshes the plugin, then restart after installing
repository files so Copilot and IDE-hosted tooling discover `.github/lsp.json`,
runtime guidance, and catalog-backed tools at startup.

??? success "Homebrew machine install"
    `brew install kast` is machine-level. It installs one `kast` executable
    that can serve many repositories, then installs or reinstalls the
    version-coupled `kast-plugin` cask so local JetBrains IDE profiles link to
    the matching plugin.
    Confirm the binary and managed plugin state before debugging repository
    files:

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
    - `.github/instructions/Kotlin.instructions.md`
    - `.github/extensions/kast/extension.mjs`
    - `.github/extensions/kast/_shared/kast-tools.mjs`
    - `.github/extensions/kast/_shared/kast-trace.mjs`
    - `.github/extensions/kast/_shared/commands.json`

    The instruction file is installed directly under `.github/instructions/`
    because Copilot does not recursively traverse instruction subdirectories.

    The global `$HOME/.local/share/kast/install.json` manifest records the
    repository resource version, source bundle checksum, output checksums, and
    install time. `kast doctor` verifies those manifest-backed files and fails
    closed when an installed output is missing or tampered.

??? info "Homebrew-managed IDE plugin"
    The IDEA or Android Studio plugin is part of the macOS developer install.
    The `kast-plugin` cask stages the plugin and links it into local
    JetBrains profiles. Use the CLI command when profile links need repair or
    when a Homebrew cask refresh needs to be applied through Kast:

    ```console title="Install or repair local IDE profiles"
    brew reinstall --cask kast-plugin
    kast install plugin
    ```

    Restart the IDE after replacing or linking the plugin.

## Repair and path inspection

Most readers do not need these commands on the first pass. Use them when an
existing install is stale, a shell profile needs to be updated, or a local IDE
profile needs repair.

Kast 1.0 resolves every install-owned path from the install manifest at
`$HOME/.local/share/kast/install.json`. The user config file remains
`$HOME/.config/kast/config.toml`, but it only owns behavior settings such as
backend selection, indexing policy, launch policy, telemetry, and profiling.
Do not put install roots, CLI paths, daemon paths, socket paths, runtime
library paths, or managed install state in `config.toml`; those values come
from the manifest-backed resolver.

??? question "Inspect the active path model"
    Use `kast paths` when you need the exact resolved paths that the CLI,
    repository Copilot package, headless runtime, and IDE integration should share.

    ```console title="Show resolved paths"
    kast paths
    kast --output json paths
    ```

??? question "Repair stale managed files"
    Plain `kast doctor` is read-only. It reports manifest validity, canonical
    paths, binary linkage, behavior config validity, and managed files that
    can be repaired. Use `kast doctor --repair` as the only broad convergence
    command after upgrading Kast, moving between install methods, or seeing
    stale managed paths.

    ```console title="Audit install state"
    kast doctor
    ```

    ```console title="Repair install state"
    kast doctor --repair
    ```

    Repair mode writes the install manifest, refreshes the stable shim,
    removes install-owned keys from behavior config, and creates backups under
    `KAST_CONFIG_HOME/backups` before replacing or removing managed files.

??? info "Shell integration"
    Use `kast install shell` to add the directory that contains the active
    `kast` shim to your `PATH` and source completions from a managed file
    under `KAST_CONFIG_HOME/shell`.

    === "Bash"

        ```console title="Install Bash integration"
        kast install shell --shell bash
        ```

    === "Zsh"

        ```console title="Install Zsh integration"
        kast install shell --shell zsh
        ```

## Source checkout development

When the target directory is inside a Git repository, `kast install copilot`
adds an idempotent managed block to `.git/info/exclude` for the generated
package files. Keep those generated files visible to Git with
`--no-auto-exclude-git`:

```console title="Install without updating .git/info/exclude"
kast install copilot --no-auto-exclude-git
```

From this source checkout, the development script installs the same
`cli-rs/resources/plugin/` Copilot package into another repository root. This
is for validating unreleased package changes, not for ordinary users.

```console title="Install Copilot package from this checkout"
cli-rs/resources/plugin/scripts/install-local.sh --target /Users/alex/work/project --force
```

Validate the source package with `.github/scripts/test-kast-copilot-plugin.sh`.
For live Copilot CLI validation of the SDK extension tools, load the source
package explicitly with `--plugin-dir cli-rs/resources/plugin`.

Use the development Gradle task when you need a local debug CLI and IDEA plugin
from the checkout:

```console title="Install local development CLI and plugin"
./gradlew installDevelopmentLocal
```

## Next steps

After installation, choose the path that matches your workflow.

- [Use Kast with agents](../for-agents/index.md) explains what the Copilot
  package gives an agent.
- [Headless Linux server](headless-linux.md) covers CI runners, hosted
  agents, and server images.
- [Supported use cases](../supported-use-cases.md) describes where Kast is
  meant to help.
- [Troubleshooting](../troubleshooting.md) covers stale repository files,
  missing binaries, and backend startup issues.
