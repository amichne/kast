# Internal protocol source guide

This file applies to `cli-rs/protocol/` and descendants. The tree owns
provider-neutral internal protocol sources, generated contract artifacts, and
maintenance fixtures. None of these files are skill payloads.

## Source boundaries

- `source/commands.json` is the authored internal command catalog.
- `source/commands.yaml`, `source/commands.schema.json`, and
  `source/requests/` are generated contract artifacts.
- `maintenance/evals/` and `maintenance/references/` own routing and output
  evaluation fixtures that are never installed with a skill or plugin.
- Generated protocol documentation and examples elsewhere in this tree remain
  downstream outputs of the catalog and backend contracts.

The Codex-facing command contract does not consume this catalog. It is owned by
the exhaustive Rust types in `cli-rs/src/codex/exposure.rs`.

## Edit rules

- Change the authored catalog, then regenerate derived YAML, schemas, samples,
  protocol docs, and LSP route metadata.
- Keep generated files deterministic and review source and output together.
- Do not copy catalog or request material into provider package trees.
- Preserve the distinction between public typed CLI commands and internal RPC
  methods.

## Verify

```console
cargo run --manifest-path cli-rs/Cargo.toml --bin kast -- \
  developer release generate contract --check
python3 .github/scripts/render-rpc-contract-summary.py --check
cargo test --manifest-path cli-rs/Cargo.toml --locked --test rpc_catalog_smoke
.github/scripts/test-lsp-pivot-gates.sh
```
