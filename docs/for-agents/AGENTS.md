# Agent docs guide

This file applies to `docs/for-agents/` and descendants. These pages explain
how Copilot and other agents use Kast after the global binary and repository
integration files exist.

## Local purpose

- Keep the global binary vs repository-local Copilot integration split
  explicit.
- Treat `kast install copilot` as the primary Copilot path.
- Treat `kast install skill` as a fallback for hosts that do not load the
  Copilot package.
- Link detailed command and API material instead of copying it into these
  pages.

## Source boundaries

- Copilot package truth lives in `cli-rs/resources/plugin/`.
- The RPC/tool catalog lives in
  `cli-rs/resources/kast-skill/references/commands.json`.
- Generated installed outputs under `.github` are evidence of package shape,
  not the source to edit first.
- The current product operating model lives in
  `docs/adr/0001-agent-first-install-and-docs-operating-model.md`.

## Edit rules

- Do not use vague repository ownership phrases. Say "repository-local
  Copilot files" or "install into this repository."
- Keep agent prompts resolve-first: identity before references, callers,
  diagnostics, or edits.
- When a page names installed files, compare against
  `cli-rs/resources/plugin/primitive-manifest.json`.
- When a page names tools or methods, compare against
  `cli-rs/resources/kast-skill/references/commands.json`.

## Verify

Run these checks for agent-doc changes:

```console
.github/scripts/test-docs-content-contract.sh
.github/scripts/test-docs-navigation-contract.sh
zensical build --clean
```

Also run `.github/scripts/test-kast-copilot-plugin.sh` when prose describes
Copilot package outputs, tools, custom agents, or LSP startup behavior.
