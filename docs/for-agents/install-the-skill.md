---
title: Install the skill
description: Install the packaged Kast skill into your workspace so agents
  can use it.
icon: lucide/download
---

# Install the packaged skill

The packaged `kast` skill is a directory of files that tells your LLM
agent how to drive `kast`. Installing it copies the files into either a
repo-local skill directory or the global default at
`~/.kast/lib/skills/kast`, then writes a `.kast-version` marker so the
same CLI version can skip reinstall.

## Prerequisites

You need the `kast` CLI on your machine first. If you don't have it, see
[Install](../getting-started/install.md).

## Install the skill

From the workspace root:

1. Run the install:

    ```console title="Install the skill"
    kast install skill
    ```

2. The command picks the default target from whichever of these
   directories already exists in your repo:

    - `.agents/skills/kast`
    - `.github/skills/kast`
    - `.claude/skills/kast`

   If none of those directories exist, it installs globally at
   `~/.kast/lib/skills/kast`.

3. Confirm — look for `.kast-version` in the target directory. If the
   same CLI version was already installed, the JSON output shows
   `skipped: true`.

## Force a reinstall

Pass `--force` to skip the confirmation. Use `--target-dir` for a
custom path:

```console title="Force reinstall to a custom path"
kast install skill --target-dir=/absolute/path/to/skills --force
```

??? info "What's in the skill directory"

    The installed tree is the same manifest embedded in the CLI:

    - **`SKILL.md`** — the instruction file: workflow, triggers, when to
      use what
    - **`references/commands.json`**, **`references/quickstart.md`**, and
      **`references/routing-improvement.md`** — generated RPC catalog,
      quick lookup material, and routing-maintenance guidance
    - **`scripts/resolve-kast.sh`** — portable helper that finds the
      `kast` binary without repo-local hook paths
    - **`scripts/kast-session-start.sh`** — compatibility helper for
      agents that still need a shell bootstrap
    - **`scripts/build-routing-corpus.py`** — maintenance helper that
      turns shared logs and session exports into routing candidate cases
    - **`fixtures/maintenance/`** — maintenance-only copies of the
      routing-improvement reference and corpus builder
    - **`evaluation/`** — embedded evaluation schemas, bindings, and
      runner scripts used by `kast eval skill`

    Keep transient benchmark outputs in a separate workspace, not in the
    installed skill tree.

??? info "How the agent finds the kast binary"

    The packaged Copilot extension registers native `kast_*` tools that
    resolve the CLI through the installed extension files. For portable
    skill-only installs, use `kast rpc` as the CLI fallback and set
    `[cli] binaryPath` in `config.toml` when the default
    configured CLI path doesn't match your machine:

    ```toml title="$HOME/.config/kast/config.toml"
    [cli]
    binaryPath = "/home/alex/.local/bin/kast"
    ```

GitHub Copilot custom agents are a separate surface. Packaged Kast Copilot
agent material belongs under `.github/extensions/kast/agents/*.md` — not in
the portable Agent Skills bundle.

## Install the Copilot extension files

Use `install copilot` when you want the packaged GitHub Copilot
native extension files in the current repository:

```console title="Install Copilot extension files"
kast install copilot
```

The command writes into `<cwd>/.github` by default. The managed install lives
under `.github/extensions/kast`, including the extension entry point, helper
agent material, the RPC command catalog, and support scripts. Packaged scripts
are installed executable, and the command records the installed CLI version in
`.github/extensions/kast/.kast-copilot-version`. Pass `--target-dir` to point
at another workspace `.github` directory, and `--force` to replace an older
installed copy:

```console title="Force reinstall Copilot extension files"
kast install copilot --target-dir=/absolute/path/to/repo/.github --force
```

To remove only packaged Copilot files, use the uninstall command:

```console title="Uninstall Copilot extension files"
kast uninstall copilot-extension
```

Uninstall removes the packaged extension files and the version marker. It
preserves foreign files you created under `.github`.

## Next steps

- [Talk to your agent](talk-to-your-agent.md) — prompts that get the
  most out of `kast`
- [Direct CLI usage](direct-cli.md) — when the agent skips the skill
  and calls `kast` itself
