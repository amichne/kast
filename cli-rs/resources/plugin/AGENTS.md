# Package source guide

This file applies to `cli-rs/resources/plugin/` and descendants. This tree is
the authored source for Kast package artifacts used by release validation and
development packaging.

## Local purpose

The package provides repository-local editor integration material:

- `lsp.json` starts `kast agent lsp --stdio`.
- `extensions/kast/extension.mjs` translates Copilot session and tool events
  into the shared `kast-agent-task` lifecycle and records the non-blocking
  session-end audit.
- `primitive-manifest.json` defines the package artifact shape.

The current source-of-truth contract for public workflows and command dialect
is `.agents/adr/0006-forward-system-definition-and-audit-scope.md`.

## Edit rules

- Edit this source tree first for package changes.
- Update `plugin.json` when entrypoints, package requirements, or package
  metadata change.
- Update `primitive-manifest.json` when installed output files change.
- Keep installed output paths relative and under the target `.github`
  package shape.
- Generated `.github` package copies come from this source tree.
- Resolve only an absolute attested task launcher or the stable installed
  launcher pair. Do not search `PATH`, build output, or standalone `kast`
  candidates.
- Keep provider hooks thin: begin on session start, inspect status around tool
  events, and audit status on session end. Explicit finish stays user-directed
  and task policy stays in Rust.
- Public package shape changes begin with a superseding ADR.

## Downstream surfaces

When package behavior changes, update the affected public surfaces in the
same change:

- `docs/getting-started/install.md`
- `docs/commands/agent.md`
- `docs/commands/lsp.md`
- `docs/troubleshooting.md`
- a superseding ADR when the product story changes
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
