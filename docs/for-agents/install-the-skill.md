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
    - **`references/quickstart.md`** — request snippets and recovery
      guidance for bootstrap and navigation
    - **`scripts/resolve-kast.sh`** — portable helper that finds the
      `kast` binary without repo-local hook paths
    - **`scripts/kast-session-start.sh`** — compatibility helper that
      prints `export KAST_CLI_PATH=...`

    Maintenance fixtures sit under `fixtures/maintenance/` so normal
    agents don't load them as active context.

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

## Next steps

- [Talk to your agent](talk-to-your-agent.md) — prompts that get the
  most out of `kast`
- [Direct CLI usage](direct-cli.md) — when the agent skips the skill
  and calls `kast` itself
