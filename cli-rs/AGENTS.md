# Rust CLI and agent resource guide

This file applies to `cli-rs/` and descendants. Deeper `AGENTS.md` files narrow
the rules for their subtrees. This tree owns the Rust AXI CLI, typed agent
command surface, installer, manifest-backed resource trust, runtime lifecycle
orchestration, source-index CLI reads, release packaging, and bundled agent
resources.

## Local purpose

- `src/cli/root.rs` and `src/main.rs` define the root AXI CLI: compact context,
  setup, readiness, repair, status, and developer operations.
- `src/cli/agent.rs` and `src/agent/` own typed compiler-backed agent commands:
  `verify`, `symbol`, `diagnostics`, `impact`, `rename`, and `lsp`.
- `src/runtime/` owns backend lifecycle inspection and mutation for IDEA and
  headless runtimes behind the same command dialect.
- `src/install/` owns repository setup, managed guidance, machine install,
  repair, bundle activation, shell integration, and IDEA plugin installation.
- `src/symbol_query/` and `src/metrics_database/` own operational source-index
  reads for the Rust CLI.
- `src/install.rs`, `src/manifest.rs`, and `src/self_mgmt.rs` own install
  state, managed resource records, doctor checks, and repair behavior.
- `resources/kast-skill/` owns the packaged `SKILL.md` and internal catalog
  source material used by release checks and generated artifacts.
- `resources/plugin/` owns package source material used by release validation.
- `protocol/` contains generated protocol artifacts for release and
  integration consumers.

The current public product surface, workflows, AXI contract, source ownership,
and validation gates live in
`.agents/adr/0006-forward-system-definition-and-audit-scope.md`.

## Edit rules

- Keep command invariants in typed Rust structures. Clap, serde, and catalog
  schema validation own command parsing and structured data boundaries.
- Agent-facing semantic workflows use typed `kast agent verify`, `symbol`,
  `diagnostics`, `impact`, `rename`, and `lsp` commands.
- Captured or agent-run commands default to compact structured output. Public
  mutations are plan-first and gated: repair and rename require `--apply`;
  setup supports `--dry-run`; forceful replacement requires `--force`.
- Treat generated or installed resource copies as outputs. Edit the authored
  resource source, then regenerate or reinstall from the active binary.
- Treat `AGENTS.md` files as authored guidance. Repository setup writes one
  managed `<kast>...</kast>` guidance region to the selected context file and
  records one manifest-backed packaged skill install.
- When install output shape changes, update manifest resource recording,
  doctor verification, package scripts, docs, and smoke tests in the same
  change.

## Source boundaries

- Command catalog truth lives in
  `resources/kast-skill/references/commands.json`.
- Package artifact output shape lives in `resources/plugin/primitive-manifest.json`.
- Installable skill source lives in `resources/kast-skill/`.
- Generated request schemas and samples are derived from the catalog. Regenerate
  them through the contract generator.
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
cargo run --manifest-path cli-rs/Cargo.toml --bin kast -- developer release generate contract --check
.github/scripts/test-kast-copilot-plugin.sh
.github/scripts/test-lsp-pivot-gates.sh
.github/scripts/test-docs-content-contract.sh
.github/scripts/test-docs-navigation-contract.sh
zensical build --clean
```
