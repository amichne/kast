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

These files are repository-local install outputs from
`kast agent setup copilot`:

- `.github/lsp.json`
- `.github/extensions/kast/extension.mjs`
- `.github/extensions/kast/_shared/kast-tools.mjs`
- `.github/extensions/kast/_shared/kast-trace.mjs`

Do not make these the source of truth for package behavior. Edit
`cli-rs/resources/plugin/` first, then reinstall or regenerate the package
outputs. Copilot loads catalog-derived tool specs from the active CLI through
`kast agent tools`; no command catalog is copied into `.github`. The global
`install.json` records installed resource versions and checksums. The durable
agent-only contract is `.agents/adr/0002-agent-resource-and-workflow-source-of-truth.md`.

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
