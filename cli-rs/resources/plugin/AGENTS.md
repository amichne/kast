# Copilot package source guide

This file applies to `cli-rs/resources/plugin/` and descendants. This tree is
the authored source for the Kast Copilot package installed by
`kast install copilot`.

## Local purpose

The package provides the repository-local Copilot integration:

- `lsp.json` starts `kast lsp --stdio`.
- `instructions/kast-kotlin.instructions.md` routes Kotlin work through Kast.
- `agents/kast-reader.agent.md` and `agents/kast-writer.agent.md` define the
  read-only and write-capable agent split.
- `extensions/kast/extension.mjs` and shared modules expose catalog-backed
  `kast_*` tools.
- `primitive-manifest.json` defines the files copied into a repository
  `.github` directory.

## Edit rules

- Edit this source tree first for Copilot package changes.
- Update `plugin.json` when entrypoints, package requirements, or package
  metadata change.
- Update `primitive-manifest.json` when installed output files change.
- Keep installed output paths relative and under the target `.github`
  package shape.
- Do not edit generated `.github` package copies as the source of truth.
  Regenerate or reinstall them from this tree.
- Keep reader and writer agents intentionally different. The reader must not
  expose write tools; the writer must expose scoped edit and validation tools.

## Downstream surfaces

When package behavior changes, update the affected public surfaces in the
same change:

- `docs/getting-started/install.md`
- `docs/for-agents/index.md`
- `docs/for-agents/install-the-skill.md`
- `docs/supported-use-cases.md`
- `docs/adr/0001-agent-first-install-and-docs-operating-model.md` or a
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
