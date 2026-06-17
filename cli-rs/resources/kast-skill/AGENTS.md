# Kast skill and RPC catalog guide

This file applies to `cli-rs/resources/kast-skill/` and descendants. This tree
is the packaged skill and command catalog used by agents, docs, the Copilot
extension, and generated LSP custom route metadata.

## Local purpose

- `SKILL.md` is the packaged skill entrypoint for hosts that load skills.
- `references/commands.json` is the canonical machine-readable RPC and tool
  catalog.
- `references/commands.yaml` and generated request schemas/samples are derived
  contract artifacts.
- `references/quickstart.md` and `references/runbook.md` are agent-facing
  lookup material.

## Edit rules

- Treat `references/commands.json` as the source catalog for methods, request
  fields, tool names, and flow grouping.
- Regenerate derived contract artifacts after catalog changes.
- Keep command and tool descriptions aligned with the current product story in
  `docs/adr/0001-agent-first-install-and-docs-operating-model.md`.
- Do not add JVM-owned handlers for Rust-owned `database/*` or source-index
  query methods.
- Keep fallback guidance resolve-first and compiler-backed; do not route
  Kotlin symbol work through text search.

## Downstream surfaces

Catalog changes can affect:

- `cli-rs/resources/plugin/extensions/kast/_shared/commands.json` after
  repository Copilot installation
- `docs/reference/api-specification.md` generated summary block
- `cli-rs/src/lsp.rs` generated custom method list and dispatch metadata
- `docs/cli-cheat-sheet.md`, agent docs, and package tests when tool names
  or flow groups change

## Verify

Run the catalog and docs checks after catalog changes:

```console
cargo run --manifest-path cli-rs/Cargo.toml -- generate contract --check
python3 .github/scripts/render-rpc-contract-summary.py --check
.github/scripts/test-kast-copilot-plugin.sh
.github/scripts/test-lsp-pivot-gates.sh
```

Use `kast validate --request-file <file>` for hand-authored request examples.
