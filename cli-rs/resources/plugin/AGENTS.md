# Copilot package source guide

This file applies to `cli-rs/resources/plugin/` and descendants. This tree is
the authored source for the Kast Copilot package that repository setup and
development install scripts copy into `.github`.

## Local purpose

The package provides the repository-local Copilot integration:

- `lsp.json` starts `kast agent lsp --stdio`.
- `extensions/kast/extension.mjs` injects runtime tooling guidance for the typed
  `kast`, `kast help`, `kast ready`, and public `kast agent` command dialect.
- `primitive-manifest.json` defines the files copied into a repository
  `.github` directory.

The durable decision record for the minimal v1 agent asset and command dialect
is `.agents/adr/0005-axi-only-agent-cli-and-semantic-edit-dialect.md`.

## Edit rules

- Edit this source tree first for Copilot package changes.
- Update `plugin.json` when entrypoints, package requirements, or package
  metadata change.
- Update `primitive-manifest.json` when installed output files change.
- Keep installed output paths relative and under the target `.github`
  package shape.
- Do not edit generated `.github` package copies as the source of truth.
  Regenerate or reinstall them from this tree.
- Do not add package behavior that exists only to support older active
  binaries. Missing typed `kast agent` support is an upgrade/reinstall
  requirement.
- Keep the package surface focused on LSP and runtime guidance. Do not add
  package-specific custom agents, static instruction entrypoints,
  `kast agent tools`, `kast agent call`, or `kast_*` tool registration unless
  the public package shape is intentionally being expanded in a new ADR.

## Downstream surfaces

When package behavior changes, update the affected public surfaces in the
same change:

- `docs/getting-started/install.md`
- `docs/commands/agent.md`
- `docs/commands/lsp.md`
- `docs/troubleshooting.md`
- `.agents/adr/0003-cli-command-documentation-operating-model.md` or a
  superseding ADR when the product story changes
- `.github/scripts/test-docs-content-contract.sh`

## Verify

Run the package checks before publishing package changes:

```console
.github/scripts/test-kast-copilot-plugin.sh
.github/scripts/test-lsp-config.mjs
.github/scripts/test-lsp-pivot-gates.sh
```

Also run the docs checks when package changes affect public installation or
agent-facing wording:

```console
.github/scripts/test-docs-content-contract.sh
zensical build --clean
```
