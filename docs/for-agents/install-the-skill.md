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

    The Copilot LSP package starts `kast lsp --stdio` from `.github/lsp.json`,
    so normal Copilot routing only needs `kast` on `PATH`. For portable
    skill-only installs, use `kast rpc` as the CLI fallback and set
    `[cli] binaryPath` in `config.toml` when the default configured CLI path
    doesn't match your machine:

    ```toml title="$HOME/.config/kast/config.toml"
    [cli]
    binaryPath = "/home/alex/.local/bin/kast"
    ```

## Install the Copilot LSP package

Use `kast install copilot` when you want Copilot to use the `kast-kotlin` LSP
server, Kotlin-scoped instructions, the `kast-reader` and `kast-writer` custom
agents, and the catalog-backed `kast_*` extension source:

```console title="Install the LSP-first Copilot package"
kast install copilot
```

The command writes these managed files into the target `.github` directory:

- `lsp.json`
- `instructions/kast-kotlin.instructions.md`
- `agents/kast-reader.agent.md`
- `agents/kast-writer.agent.md`
- `extensions/kast/extension.mjs`
- `.kast-copilot-version`

Pass `--target-dir` to point at another workspace `.github` directory, and
`--force` to replace an older installed copy:

```console title="Force reinstall Copilot LSP package"
kast install copilot --target-dir=/absolute/path/to/repo/.github --force
```

By default, installs inside Git repositories also write a managed block to
`.git/info/exclude` for the generated package files. Use
`--no-auto-exclude-git` when the repository should decide how to track or
ignore those files:

```console title="Install without local Git excludes"
kast install copilot --no-auto-exclude-git
```

From this source checkout, `cli-rs/resources/plugin/scripts/install-local.sh`
installs the same package into a target repository root for local development.
Validate the source package with `.github/scripts/test-kast-copilot-plugin.sh`.
For live Copilot CLI validation of the SDK extension tools, load the source
package explicitly with `--plugin-dir cli-rs/resources/plugin`. Project
installs expose the agents as `kast-reader` and `kast-writer`; source-plugin
validation exposes them under the plugin namespace, such as
`kast-copilot-lsp:kast-reader`.

## Next steps

- [Talk to your agent](talk-to-your-agent.md) — prompts that get the
  most out of `kast`
- [Direct CLI usage](direct-cli.md) — when the agent skips the skill
  and calls `kast` itself
