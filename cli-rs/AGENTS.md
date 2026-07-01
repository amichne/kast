# Rust CLI and agent resource guide

This file applies to `cli-rs/` and descendants unless a deeper `AGENTS.md`
narrows the rules. This tree owns the Rust CLI, installer, manifest-backed
resource trust, agent command surface, and bundled agent resources.

## Local purpose

- `src/cli.rs` defines the public and hidden CLI command surface.
- Public CLI families are intent-first: `kast ready`, `kast agent`,
  `kast runtime`, `kast inspect`, `kast machine`, and `kast release`.
- `src/agent.rs` owns `kast agent` aliases, `kast agent tools`,
  `kast agent call`, and `kast agent workflow`; `kast agent up`,
  `kast agent setup`, and `kast agent lsp` dispatch through operator handlers
  before JSON-envelope execution.
- `kast agent setup` installs harness-agnostic agent exposure: the packaged
  skill under `.agents/skills/kast` plus a Kast-managed fenced region in the
  ignored root `AGENTS.local.md` file, with `--agents-md` available for
  explicit scoped guidance files. Its `--dry-run` mode must stay read-only and
  explain skill and guidance targets.
- `kast agent up` composes harness-agnostic setup with `kast runtime up`.
  Its explicit `--workspace-root` must stay authoritative for setup targets,
  and `--dry-run` must not write resources or start a backend.
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
- Treat `AGENTS.md` files as authored guidance. Default Kast setup writes the
  managed guidance block to ignored `AGENTS.local.md`; Kast may own only the
  `<kast files="*.kt, *.kts" type="instructions" replaceTools="grep,search,write">`
  region inside explicit guidance targets.
- Do not maintain compatibility helpers only for older binaries. Missing
  `kast agent` or `kast agent workflow` support is an incompatibility that
  requires upgrade or reinstall.
- Do not restore a shell `kast rpc` surface or older top-level developer
  aliases. Agent and Copilot integrations use the root setup/readiness
  commands, `kast developer ...`, `kast agent lsp`, `kast agent call`, and
  `kast agent workflow`.
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
cargo run --manifest-path cli-rs/Cargo.toml -- release generate contract --check
.github/scripts/test-kast-copilot-plugin.sh
.github/scripts/test-lsp-pivot-gates.sh
.github/scripts/test-docs-content-contract.sh
.github/scripts/test-docs-navigation-contract.sh
zensical build --clean
```
