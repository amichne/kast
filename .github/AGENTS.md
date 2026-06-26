# GitHub integration guide

This file applies to `.github/` and descendants. This tree contains both
authored GitHub automation and repository-local Copilot package outputs, so
agents must identify which surface they are touching before editing.

## Authored surfaces

These files are hand-authored and may be edited directly when they own the
change:

- `.github/workflows/*.yml`
- `.github/scripts/*`
- `.github/copilot-instructions.md`
- `.github/dependabot.yml`
- `.github/skill-shadowing.json`

Run the narrowest script or workflow contract that covers the edit. For docs
contract changes, run both docs contract scripts and `zensical build --clean`.
For release workflow changes, run `.github/scripts/test-release-workflow-contract.sh`.

## Generated package and instruction outputs

These files are repository-local install outputs from the active Kast resource
installers:

- `.github/lsp.json` from `kast install copilot`
- `.github/instructions/Kotlin.instructions.md` from `kast install copilot`
- `.github/extensions/kast/**` from `kast install copilot`
- ignored `.github/instructions/kast/**` fallback instruction copies from
  `kast install instructions`

Do not make these the source of truth for package behavior. Edit
`cli-rs/resources/plugin/` first, then reinstall or regenerate the package
outputs. Keep Copilot package instructions as top-level
`.github/instructions/*.instructions.md` files because Copilot does not
recursively traverse instruction subdirectories. Edit
`cli-rs/resources/kast-instructions/` first for fallback Markdown instruction
wording, then reinstall instructions from the active binary. The installed
command catalog under `.github/extensions/kast/_shared/commands.json` comes from
`cli-rs/resources/kast-skill/references/commands.json`. The global
`install.json` records installed resource versions and checksums. The durable
agent-only contract is
`.agents/adr/0002-agent-resource-and-workflow-source-of-truth.md`.

## Verify

For generated Copilot package changes, run:

```console
.github/scripts/test-kast-copilot-plugin.sh
.github/scripts/test-lsp-config.mjs
```

For docs contract changes, run:

```console
.github/scripts/test-docs-content-contract.sh
.github/scripts/test-docs-navigation-contract.sh
zensical build --clean
```
