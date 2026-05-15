---
title: Install the skill
description: Install the packaged Kast skill into your workspace so agents
  can use it.
icon: lucide/download
---

# Install the packaged skill

The packaged `kast` skill is a directory of files that tells your LLM agent how to drive `kast`. Installing it copies
the files into either a repo-local skill directory or the global default at
`~/.kast/lib/skills/kast`, then writes a `.kast-version` marker so the same CLI version can skip reinstall.

## Prerequisites

You need the `kast` CLI on your machine first. If you don't have it, see
[Install](../getting-started/install.md).

## Install the skill

From the workspace root:

1. Run the install:

    ```console title="Install the skill"
    kast install skill
    ```

2. The command picks the default target from whichever of these directories already exists in your repo:

    - `.agents/skills/kast`
    - `.github/skills/kast`
    - `.claude/skills/kast`

   If none of those directories exist, it installs globally at
   `~/.kast/lib/skills/kast`.

3. Confirm — look for `.kast-version` in the target directory. If the same CLI version was already installed, the JSON
   output shows
   `skipped: true`.

## Force a reinstall

Pass `--yes=true` to skip the confirmation. Use `--target-dir` for a custom path:

```console title="Force reinstall to a custom path"
kast install skill --target-dir=/absolute/path/to/skills --yes=true
```

??? info "What's in the skill directory"

    Only what the agent reads at runtime:

    - **`SKILL.md`** — the instruction file: workflow, triggers, when to
      use what
    - **`evals/catalog.json`** and **`evals/pain_points.jsonl`** —
      durable maintenance cases plus the intake queue for new misses
    - **`history/progression.json`** — promotion and progression ledger
      for the durable suite
    - **`references/commands.json`**, **`references/quickstart.md`**, and
      **`references/routing-improvement.md`** — current wrapper request
      shapes, quick lookup material, and routing-maintenance guidance
    - **`scripts/resolve-kast.sh`** — portable helper that finds the
      `kast` binary without repo-local hook paths
    - **`scripts/kast-session-start.sh`** — compatibility helper for
      agents that still need a shell bootstrap
    - **`scripts/build-routing-corpus.py`** — maintenance helper that
      turns shared logs and session exports into routing candidate cases

    Durable eval assets now live in `evals/` and `history/`. Keep
    transient benchmark outputs in a separate workspace, not in the
    installed skill tree.

??? info "How the agent finds the kast binary"

    The packaged Copilot extension registers native `kast_*` tools that
    resolve the CLI through the installed extension files. For portable
    skill-only installs, use `kast rpc` as the CLI fallback and set
    `[cli] binaryPath` in `config.toml` when the default
    `$HOME/.kast/bin/kast` path doesn't match your machine:

    ```toml title="$HOME/.config/kast/config.toml"
    [cli]
    binaryPath = "/Users/alex/.kast/bin/kast"
    ```

GitHub Copilot custom agents are a separate surface. Personas and tool restrictions for Copilot belong in
`.github/agents/*.md` — not in the portable Agent Skills bundle.

## Install the Copilot extension files

Use `install copilot-extension` when you want the packaged GitHub Copilot agent, hook, and native extension files in the
current repository:

```console title="Install Copilot agents, hooks, and extensions"
kast install copilot-extension
```

The command writes into `<cwd>/.github` by default, including packaged
`.github/agents`, `.github/hooks`, and self-contained native extension scripts under `.github/extensions`. Packaged
scripts are installed executable, and the command records the installed CLI version in `.github/.kast-copilot-version`.
Pass `--target-dir` to point at another workspace `.github` directory, and
`--yes=true` to replace an older installed copy:

```console title="Force reinstall Copilot extension files"
kast install copilot-extension --target-dir=/absolute/path/to/repo/.github --yes=true
```

To remove only packaged Copilot files, pass `--uninstall=true`:

```console title="Uninstall Copilot extension files"
kast install copilot-extension --uninstall=true
```

Uninstall removes the packaged manifest files and the version marker. It preserves foreign files you created under
`.github`.

## Next steps

- [Talk to your agent](talk-to-your-agent.md) — prompts that get the most out of `kast`
- [Direct CLI usage](direct-cli.md) — when the agent skips the skill and calls `kast` itself
