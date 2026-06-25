# Rust CLI and agent resource guide

This file applies to `cli-rs/` and descendants unless a deeper `AGENTS.md`
narrows the rules. This tree owns the Rust CLI, installer, manifest-backed
resource trust, agent command surface, and bundled agent resources.

## Local purpose

- `src/cli.rs` defines the public and hidden CLI command surface.
- `src/agent.rs` owns `kast agent` aliases, `kast agent call`, and
  `kast agent workflow`.
- `src/install.rs`, `src/manifest.rs`, and `src/self_mgmt.rs` own install
  state, managed resource records, doctor checks, and repair behavior.
- `resources/plugin/` owns the Copilot package source.
- `resources/kast-skill/` owns the packaged skill and RPC/tool catalog.
- `resources/kast-instructions/` owns installable Markdown instructions.
- `protocol/` contains generated protocol artifacts for release and
  integration consumers. It is not a docs site.

The durable decision record for agent resources and workflows is
`.agents/adr/0002-agent-resource-and-workflow-source-of-truth.md`.

## Edit rules

- Keep command invariants in typed Rust structures. Do not bypass Clap, serde,
  or catalog schema validation with ad hoc string handling.
- Treat generated or installed resource copies as outputs. Edit the authored
  resource source, then regenerate or reinstall from the active binary.
- Do not maintain compatibility helpers only for older binaries. Missing
  `kast agent` or `kast agent workflow` support is an incompatibility that
  requires upgrade or reinstall.
- Keep raw `kast rpc` hidden/debug-oriented. Agent and Copilot integrations use
  `kast agent call` and `kast agent workflow`.
- When install output shape changes, update manifest resource recording,
  doctor verification, package scripts, docs, and smoke tests in the same
  change.

## Source boundaries

- Command catalog truth lives in
  `resources/kast-skill/references/commands.json`.
- Copilot package output shape lives in
  `resources/plugin/primitive-manifest.json`.
- Installable skill source lives in `resources/kast-skill/`.
- Installable instruction source lives in `resources/kast-instructions/`.
- Generated request schemas and samples are derived from the catalog. Regenerate
  them through the contract generator instead of hand-editing generated drift.
- Generated protocol markdown, OpenAPI YAML, and example fixtures live under
  `protocol/`; regenerate them through the Gradle docs generators.

## Verify

Use the narrowest checks that cover the edit, then broaden when shared
contracts move:

```console
cargo fmt --manifest-path cli-rs/Cargo.toml --all -- --check
cargo clippy --manifest-path cli-rs/Cargo.toml --locked --all-targets --all-features -- -D warnings
cargo test --manifest-path cli-rs/Cargo.toml --locked
```

For resource, package, or catalog changes, also run the relevant contracts:

```console
cargo run --manifest-path cli-rs/Cargo.toml -- generate contract --check
.github/scripts/test-kast-copilot-plugin.sh
.github/scripts/test-lsp-pivot-gates.sh
.github/scripts/test-docs-content-contract.sh
```
