---
title: Install the skill
description: Install the packaged Kast skill into your workspace so agents
  can use it.
icon: lucide/download
---

# Install the packaged Kast skill

The packaged Kast skill is a repository-local directory that tells your
LLM agent how to use Kast. Installing it copies the skill files into your
workspace and writes a `.kast-version` marker so the same CLI version
skips reinstallation.

## Prerequisites

Before you install the skill, you need the Kast CLI installed on your
machine. If you haven't done that yet, follow the
[install guide](../getting-started/install.md).

## Install the skill

From the workspace root, run the following command to install the skill.

1. Run the install command:

    ```console title="Install the skill"
    kast install skill
    ```

2. Let the command choose the default target directory. It picks from
   the directories already present in your workspace:

    - `.agents/skills/kast`
    - `.github/skills/kast`
    - `.claude/skills/kast`

3. Verify the install by checking for the `.kast-version` file in the
   target directory. If the same CLI version was already installed, the
   JSON result shows `skipped: true`.

## Force a reinstall

If you need to replace an existing install, pass `--yes=true` to skip
the confirmation prompt. If you need a non-default target directory,
pass `--target-dir`:

```console title="Force reinstall to a custom path"
kast install skill --target-dir=/absolute/path/to/skills --yes=true
```

## What the skill contains

The active skill directory includes only runtime context:

- **`SKILL.md`** — the agent-facing instruction file that describes
  the portable Kast workflow and when to trigger it
- **`references/quickstart.md`** — compact request snippets and recovery
  guidance for bootstrap and navigation
- **`scripts/resolve-kast.sh`** — a portable helper that resolves the
  Kast binary without repo-local hook paths
- **`scripts/kast-session-start.sh`** — a compatibility helper that
  prints an `export KAST_CLI_PATH=...` fragment

Maintenance-only fixtures live under `fixtures/maintenance` so normal agents do
not load them as active context:

- **`fixtures/maintenance/evals/evals.json`** — richer transcript-derived eval
  prompts for measuring routing, recovery, and semantic persistence
- **`fixtures/maintenance/evals/routing.json`** — sanitized routing prompts
  used to keep real-world trigger coverage stable
- **`fixtures/maintenance/references/wrapper-openapi.yaml`** — OpenAPI
  specification for the `kast skill` subcommand surface
- **`fixtures/maintenance/references/routing-improvement.md`** — the playbook
  for mining transcripts and logs into routing evals
- **`fixtures/maintenance/scripts/build-routing-corpus.py`** — builds
  sanitized routing cases and promotion candidates from session exports and
  process logs

GitHub Copilot custom agents are a separate surface. If you want
Copilot-specific personas or tool restrictions, keep those in
`.github/agents/*.md`; they are not part of the portable Agent Skills bundle.

## How agents locate the Kast binary

The packaged skill assumes a companion hook sets `KAST_CLI_PATH` to
an absolute path to the Kast binary before the skill runs. Every
command documented in `SKILL.md` is then invoked as
`"$KAST_CLI_PATH" skill <command> <json>`.

Use `.github/hooks/resolve-kast-cli-path.sh` in your harness to encode
the standard resolution flow (existing `KAST_CLI_PATH`, then `kast` on
`$PATH`, then local build/distribution outputs). For example:

```bash
export KAST_CLI_PATH="$(bash .github/hooks/resolve-kast-cli-path.sh)"
```

If you are operating outside the repo hook environment, the installed skill
also includes portable helpers:

```console title="Use the installed compatibility helpers"
eval "$(bash .agents/skills/kast/scripts/kast-session-start.sh)"
python3 .agents/skills/kast/fixtures/maintenance/scripts/build-routing-corpus.py --help
```

## Next steps

- [Talk to your agent](talk-to-your-agent.md) — how to prompt your
  agent to use Kast effectively
- [Direct CLI usage](direct-cli.md) — when agents call the CLI
  directly instead of through the skill
