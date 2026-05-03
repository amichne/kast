---
title: Install the skill
description: Install the packaged Kast skill into your workspace so agents
  can use it.
icon: lucide/download
---

# Install the packaged skill

The packaged Kast skill is a directory of files that lives in your
repo and tells your LLM agent how to drive `kast`. Installing it copies
the files into your workspace and writes a `.kast-version` marker so
the same CLI version can skip reinstall.

## Prerequisites

You need the Kast CLI on your machine first. If you don't have it, see
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

3. Confirm — look for `.kast-version` in the target directory. If the
   same CLI version was already installed, the JSON output shows
   `skipped: true`.

## Force a reinstall

Pass `--yes=true` to skip the confirmation. Use `--target-dir` for a
custom path:

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
    - **`references/quickstart.md`**, **`references/routing-improvement.md`**,
      and **`references/wrapper-openapi.yaml`** — request shapes,
      routing-maintenance guidance, and checked-in wrapper contract
    - **`scripts/resolve-kast.sh`** — portable helper that finds the
      `kast` binary without repo-local hook paths
    - **`scripts/kast-session-start.sh`** — compatibility helper that
      prints `export KAST_CLI_PATH=...`
    - **`scripts/build-routing-corpus.py`** — maintenance helper that
      turns shared logs and session exports into routing candidate cases

    Durable eval assets now live in `evals/` and `history/`. Keep
    transient benchmark outputs in a separate workspace, not in the
    installed skill tree.

??? info "How the agent finds the kast binary"

    The skill assumes a companion hook sets `KAST_CLI_PATH` to an
    absolute path before the skill runs. Every command in `SKILL.md`
    runs as `"$KAST_CLI_PATH" skill <command> <json>`.

    Inside this repo, use `.github/hooks/resolve-kast-cli-path.sh`:

    ```bash
    export KAST_CLI_PATH="$(bash .github/hooks/resolve-kast-cli-path.sh)"
    ```

    Outside the repo, the installed skill ships its own helpers:

    ```console title="Use the installed compatibility helpers"
    eval "$(bash .agents/skills/kast/scripts/kast-session-start.sh)"
    ```

GitHub Copilot custom agents are a separate surface. Personas and tool
restrictions for Copilot belong in `.github/agents/*.md` — not in the
portable Agent Skills bundle.

## Install the Copilot extension files

Use `install copilot-extension` when you want the packaged GitHub Copilot
agent and hook files in the current repository:

```console title="Install Copilot agents and hooks"
kast install copilot-extension
```

The command writes into `<cwd>/.github` by default and records the installed
CLI version in `.github/.kast-copilot-version`. Pass `--target-dir` to point at
another workspace `.github` directory, and `--yes=true` to replace an older
installed copy:

```console title="Force reinstall Copilot extension files"
kast install copilot-extension --target-dir=/absolute/path/to/repo/.github --yes=true
```

## Next steps

- [Talk to your agent](talk-to-your-agent.md) — prompts that get the
  most out of `kast`
- [Direct CLI usage](direct-cli.md) — when the agent skips the skill
  and calls `kast` itself
