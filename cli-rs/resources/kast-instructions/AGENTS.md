# Installable instructions guide

This file applies to `cli-rs/resources/kast-instructions/` and descendants.
This tree is the authored source for Markdown instructions installed by
`kast install instructions`.

## Local purpose

- `README.md` routes agents across the installed instruction files.
- `cli.md` covers non-interactive CLI usage.
- `rpc.md` covers `kast agent`, file-backed request exchange, and the raw RPC
  debug escape hatch.
- `lsp.md` covers `kast lsp --stdio` and custom `kast/*` method discovery.

The durable source-of-truth contract for agent resources and workflows is
`.agents/adr/0002-agent-resource-and-workflow-source-of-truth.md`.

## Edit rules

- Keep this tree lightweight and installable. Do not copy the full packaged
  skill or generated request catalog into these files.
- Prefer `kast agent` and `kast agent workflow` as the current agent surface.
  If an active binary lacks those commands, instruct agents to upgrade or
  reinstall Kast instead of preserving older-binary instructions.
- Keep raw RPC language explicitly debug-oriented.
- When installed filenames or defaults change, update `src/install.rs`, docs,
  and smoke tests that assert the installed instruction shape.

## Verify

Run these checks after instruction-source edits:

```console
cargo test --manifest-path cli-rs/Cargo.toml --locked install_instructions_omits_marker_and_skips_matching_version
.github/scripts/test-docs-content-contract.sh
```

Also run `zensical build --clean` when public docs changed with the instruction
wording.
