---
title: Copilot integrations
description: Install repository-local Copilot files, or use the packaged skill
  fallback for non-Copilot agents.
icon: lucide/download
---

# Copilot integrations

The preferred agent path is `kast install copilot`. It installs
repository-local files that let Copilot start Kast through LSP, load
Kotlin-specific instructions, and expose Kast reader and writer agents.

## Install into this repository

Run the command from the repository root. The global `kast` binary stays on
the machine; this command writes files only for the repository you are in.

```console title="Install the repository Copilot package"
kast install copilot
```

The default target is `$PWD/.github`. Pass `--target-dir` when installing into
another repository's `.github` directory, and `--force` when replacing a stale
managed copy.

```console title="Force reinstall into another repository"
kast install copilot --target-dir=/absolute/path/to/repo/.github --force
```

??? tip "What gets installed"
    The installed tree is managed by the running CLI version:

    - `lsp.json`
    - `instructions/kast-kotlin.instructions.md`
    - `agents/kast-reader.agent.md`
    - `agents/kast-writer.agent.md`
    - `extensions/kast/extension.mjs`
    - `extensions/kast/_shared/kast-tools.mjs`
    - `extensions/kast/_shared/kast-agents.mjs`
    - `extensions/kast/_shared/commands.json`
    - `.kast-copilot-version`

    These paths live under the target `.github` directory. Restart the IDE
    after installing or refreshing them.

??? info "How Copilot finds the binary"
    The repository package starts `kast lsp --stdio` from `.github/lsp.json`.
    That means the machine running Copilot must have the global `kast` binary
    on `PATH`. Use the install guide if the binary is missing.

## Use the packaged skill fallback

Use `kast install skill` for hosts that do not load the Copilot package but do
understand repo-local or global agent skills. This is not the primary Copilot
path.

```console title="Install the packaged skill"
kast install skill
```

The command picks the default target from whichever of these directories
already exists in your repo:

- `.agents/skills/kast`
- `.github/skills/kast`
- `.claude/skills/kast`

If none of those directories exist, it installs globally at
`~/.kast/lib/skills/kast`. Look for `.kast-version` in the target directory to
confirm the install. If the same CLI version was already installed, JSON output
shows `skipped: true`.

```console title="Force reinstall to a custom skill path"
kast install skill --target-dir=/absolute/path/to/skills --force
```

??? info "What's in the skill directory"
    The installed tree is the same manifest embedded in the CLI:

    - `SKILL.md` for workflow, triggers, and routing rules
    - `references/commands.json`, `references/quickstart.md`, and
      `references/routing-improvement.md`
    - `scripts/resolve-kast.sh` to find the `kast` binary
    - `scripts/kast-session-start.sh` for shell bootstrap compatibility
    - `scripts/build-routing-corpus.py` for routing maintenance
    - `fixtures/maintenance/` and `evaluation/` for maintenance and eval work

    Keep transient benchmark outputs outside the installed skill tree.

## Validate package changes from this checkout

Contributors can validate the authored source package under
`cli-rs/resources/plugin/`. Project installs expose agents as `kast-reader`
and `kast-writer`; source-plugin validation exposes them under the plugin
namespace, such as `kast-copilot-lsp:kast-reader`.

```console title="Validate the checked-in source package"
.github/scripts/test-kast-copilot-plugin.sh
```

For live Copilot CLI validation of the SDK extension tools, load the source
package explicitly with `--plugin-dir cli-rs/resources/plugin`.

```console title="Load the source package in Copilot CLI"
copilot -C /path/to/repo --plugin-dir cli-rs/resources/plugin \
  --agent kast-copilot-lsp:kast-reader \
  --model gpt-5-mini --effort low \
  -p 'Validation only. Reply exactly: KAST_READER_LOADED'
```

## Next steps

After the repository files exist, use the agent prompt guide or the CLI
fallback depending on the host.

- [Talk to your agent](talk-to-your-agent.md) shows resolve-first prompt
  patterns.
- [Direct CLI usage](direct-cli.md) explains `kast rpc` for hosts without
  native tools.
