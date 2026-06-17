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
| Machine CLI | `brew install kast` | Homebrew-managed global binary on `PATH` | A macOS developer machine needs Kast |
| Machine IDE plugin | `brew install --cask kast-plugin` | Homebrew-managed plugin linked into local JetBrains profiles | A macOS developer machine uses Kast |
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
brew install --cask kast-plugin

cd /path/to/your/repository
kast install copilot
```

Restart IDEA or Android Studio after Homebrew links or refreshes the plugin,
then restart after installing repository files so Copilot and IDE-hosted
tooling discover `.github/lsp.json`, repository instructions, and custom
agents at startup.

??? success "Homebrew machine install"
    `brew install kast` and `brew install --cask kast-plugin` are
    machine-level. They install one `kast` executable that can serve many
    repositories and link the Kast plugin into local JetBrains IDE profiles.
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

??? info "Homebrew-managed IDE plugin"
    The IDEA or Android Studio plugin is part of the macOS developer install.
    The `kast-plugin` cask stages the plugin and links it into local
    JetBrains profiles. Use the CLI command when profile links need repair or
    when a Homebrew cask refresh needs to be applied through Kast:

    ```console title="Install or repair local IDE profiles"
    brew install --cask kast-plugin
    kast install plugin
    ```

    Restart the IDE after replacing or linking the plugin.

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

    Narrow the refresh with `--skip-repair`, `--skip-shell`, `--skip-skill`,
    or `--skip-copilot` when a specific non-IDE integration should be left
    untouched.

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
package explicitly with `--plugin-dir cli-rs/resources/plugin`. Project
installs expose the agents as `kast-reader` and `kast-writer`; source-plugin
validation exposes them under the plugin namespace, such as
`kast-copilot-lsp:kast-reader`.

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
