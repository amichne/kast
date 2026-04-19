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

The installed skill directory includes:

- **`SKILL.md`** — the agent-facing instruction file that describes
  every available command, its parameters, and expected output
- **`agents/openai.yaml`** — OpenAI-compatible agent configuration
- **`references/wrapper-openapi.yaml`** — OpenAPI specification for
  the CLI wrapper commands
- **`scripts/resolve-kast.sh`** — resolver script that locates the
  `kast` binary on the system

## How the resolver finds Kast

The resolver script searches for the `kast` executable in this order:

1. `$KAST_CLI_PATH` — if set, use this path directly
2. `$KAST_SOURCE_ROOT/kast-cli/build/scripts/kast-cli` — local build
   output
3. `$KAST_SOURCE_ROOT/dist/cli/kast-cli` — local portable distribution
4. `kast` on `$PATH` — system-installed binary
5. If `KAST_SOURCE_ROOT` is set and Java 21+ is available, attempt
   `./gradlew :kast-cli:writeWrapperScript` as a last resort

## Next steps

- [Talk to your agent](talk-to-your-agent.md) — how to prompt your
  agent to use Kast effectively
- [Direct CLI usage](direct-cli.md) — when agents call the CLI
  directly instead of through the skill
