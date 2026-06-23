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

## Generated Copilot package outputs

These files are repository-local install outputs from `kast install copilot`:

- `.github/lsp.json`
- `.github/extensions/kast/**`
- `.github/.kast-copilot-version`

Do not make these the source of truth for package behavior. Edit
`cli-rs/resources/plugin/` first, then reinstall or regenerate the package
outputs. The installed command catalog under
`.github/extensions/kast/_shared/commands.json` comes from
`cli-rs/resources/kast-skill/references/commands.json`.

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
