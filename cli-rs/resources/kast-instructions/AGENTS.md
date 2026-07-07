# Instruction source guide

This file applies to `cli-rs/resources/kast-instructions/` and descendants.
This tree is authored Markdown instruction source used by resource validation
and package checks.

## Local purpose

- `README.md` routes agents across the instruction files.
- `cli.md` covers AXI CLI usage, setup, readiness, repair, status, and output
  formats.
- `tools.md` maps semantic tasks to `kast agent verify`, `symbol`,
  `diagnostics`, `impact`, and `rename`.
- `lsp.md` covers `kast agent lsp --stdio` for editor integration.

The current source-of-truth contract for public workflows and command dialect
is `.agents/adr/0006-forward-system-definition-and-audit-scope.md`.

## Edit rules

- Keep this tree lightweight and aligned with root CLI plus typed agent
  commands.
- Use typed `kast agent` commands for compiler-backed semantic work.
- When resource filenames or defaults change, update `src/install.rs`, docs,
  and smoke tests that assert the instruction shape.

## Verify

Run these checks after instruction-source edits:

```console
cargo test --manifest-path cli-rs/Cargo.toml --locked install_instructions_omits_marker_and_skips_matching_version
.github/scripts/test-docs-content-contract.sh
```

Also run `zensical build --clean` when public docs changed with the instruction
wording.
