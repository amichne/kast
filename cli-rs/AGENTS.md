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
  `lease`, `verify`, `workspace-files`, `symbol`, `diagnostics`, `impact`,
  `rename`, and `lsp`.
- `src/runtime/` owns backend lifecycle inspection and mutation for IDEA and
  headless runtimes behind the same command dialect.
- `src/install/` owns repository setup, managed guidance, CLI machine receipts,
  repair, bundle activation, shell integration, and bounded legacy cleanup. It
  does not install or update IDEA plugins.
- `src/symbol_query/` and `src/metrics_database/` own operational source-index
  reads for the Rust CLI.
- `src/workspace_inventory.rs` and `src/workspace_inventory/` own uncapped
  exact-root `.kt` index reads, compiler/project-model candidate composition,
  deepest-existing-ancestor path containment, source generation/progress/
  pending evidence, build-qualified indexed Gradle project identities, the
  structured Gradle source-set and Kotlin package provenance states, the
  kind-relevant backend/index/filesystem/Git coherence barrier, and typed
  limitations used by `agent workspace-files` and Gradle DSL consumers.
- `src/install.rs`, `src/manifest.rs`, `src/self_mgmt.rs`, and
  `src/self_mgmt/agent_readiness.rs` own install state, managed resource
  records, doctor checks, effective binary/backend/skill/guidance evidence,
  CLI dialect compatibility, and repair behavior.
- `src/local_development.rs` and `src/local_development/` own framed checkout
  identity, independently attested CLI/backend artifacts, immutable local
  generations, the strict portable prepared-generation ledger, exact receipt topology, generation-scoped runtime state, and
  the canonical prefix authority lock shared by runtime registration,
  activation, rollback, and removal. It also owns local skill/guidance command
  routing. Keep this boundary separate from Homebrew and JetBrains release
  authority. Changes require
  `.github/scripts/test-local-development-refresh-contract.sh` plus focused
  Rust tests; never accept an artifact label without recomputing its source and
  byte identity. Prepared generations have one closed layout, path-independent
  component provenance, and an exact source-bound CLI. Verification must reject
  unknown ledger fields, missing or extra entries, symlinks, special files,
  component drift, stale source, and backend-manifest drift before activation.
  `developer local activate` consumes only an already-verified generation; it
  must never gain an implicit Cargo or Gradle build path.
- `src/self_mgmt.rs` parses revision-3 exact-root compatibility facts strictly
  and delegates active admission to the authored typed compatibility matrix.
  Unknown fields, capabilities, revisions, unsupported rows, and missing
  required capabilities fail closed; missing optional capabilities remain
  local to the operation that needs them.
- `resources/kast-skill/` owns only the provider-neutral packaged `SKILL.md`
  and its concise agent-facing references. Internal catalogs, schemas,
  generated request samples, and maintenance evaluations stay under
  `protocol/` and must never be distributed as skill support files.
- `resources/codex-plugin/` owns the repo-local Codex marketplace source. Rust
  exposure descriptors generate its manifest, hook configuration, command
  references, recovery assets, and contract fixtures; the thin skill,
  launcher, skill presentation metadata, and canonical logo remain authored.
- `resources/plugin/` owns the independent GitHub Copilot package source
  material used by release validation.
- `protocol/source/` contains the authored internal catalog plus generated
  schemas and request samples; `protocol/maintenance/` contains routing and
  format evaluation fixtures. Other `protocol/` outputs serve release and
  integration consumers.

The broader public product surface, workflows, and AXI contract live in
`.agents/adr/0006-forward-system-definition-and-audit-scope.md`. IDEA runtime
compatibility, index privacy, lifecycle, and semantic cockpit authority live
in `.agents/adr/0023-signed-idea-plugin-distribution-and-runtime-authority.md`;
`.agents/adr/0028-unsigned-github-idea-plugin-distribution.md` supersedes its
plugin distribution decisions. Exact-root agent lease identity, ownership,
recovery, and release authority live in
`.agents/adr/0028-exact-root-agent-workspace-leases.md`. The Codex CLI-only
plugin, exhaustive Rust exposure classifier, hook state, and
release coupling live in
`.agents/adr/0026-codex-cli-plugin-and-rust-exposure-authority.md`.

## Edit rules

- Keep command invariants in typed Rust structures. Clap, serde, and catalog
  schema validation own command parsing and structured data boundaries.
- Classify every root, agent, operation, and developer command for Codex with
  exhaustive Rust matches and no wildcard arm. A new command must fail to
  compile until it is deliberately agent-visible, hook-only, or unavailable.
- Keep the Codex plugin CLI-only. Do not add `.mcp.json`, `.app.json`, an MCP
  server, app connector, custom agent profile, raw RPC payload, or copied
  command catalog to `resources/codex-plugin/`.
- Keep Codex hook parsing, decisions, state, and output schemas in Rust. The
  launcher may only resolve the active binary and forward the event and stdin.
  Hooks may inspect readiness and produce repair plans but must never apply
  setup or repair mutations.
- Agent-facing semantic workflows acquire and release one typed exact-root
  lease, then use `kast agent verify`,
  `workspace-files`, `symbol`, `diagnostics`, `impact`, `rename`, and `lsp`
  commands.
- Keep raw workspace paging handles and public workspace-file continuation
  handles distinct and opaque. Public continuations bind every result-affecting
  query field and the coherent multi-source composition stamp, including each
  relevant lane's exact available/unavailable state; invalid or stale state
  must never restart at page one. Stable backend-only/index-only partial pages
  may continue known matches without claiming exactness.
- Do not assert `EXACT`, `INDEX_ONLY`, or clean filter evidence while a relevant
  backend, source-index, filesystem, or Git lane is moving, incomplete, pending,
  or unprovable. Retry the full composition only within its documented bound.
- Compute lane relevance from the normalized source-only, script-only, or mixed
  kind domain before collection. `.kt` index progress is irrelevant to
  script-only discovery and #340; mixed results retain separate source/script
  coverage before computing overall and grouped cardinality.
- Never parse legacy `file_metadata.module_path` as Gradle project identity.
  Indexed Gradle owners require validated rows from the dedicated
  `file_gradle_projects` association table and render/filter as a
  build-qualified identity.
- Never match package/source-set filters against legacy strings. Only
  compiler/PSI-proven package states and model-proven build-qualified Gradle
  source sets match; unproven values remain explicit partial filter evidence.
  The package selector is closed: `root` matches only proven-root evidence and
  `named:<canonical-kotlin-package-fq-name>` matches only equal proven-named
  evidence.
- `packaging/homebrew/release-state.json` is the schema-version source consumed
  by `build.rs`. Keep its generated Rust value aligned with build-logic's Kotlin
  value and fail closed on an older/malformed source-index schema.
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
  `protocol/source/commands.json`.
- Codex command exposure truth lives in the exhaustive Rust exposure enums and
  typed descriptors. Generated Codex references must not consume the internal
  command catalog.
- The authored Codex skill and launcher live under
  `resources/codex-plugin/plugins/kast/`; generated files are enumerated by its
  scoped `AGENTS.md` and checked by `developer codex generate --check`.
- Package artifact output shape lives in `resources/plugin/primitive-manifest.json`.
- Installable skill source lives in `resources/kast-skill/`.
- Generated request schemas and samples under `protocol/source/requests/` are
  derived from the catalog. Regenerate them through the contract generator.
- Generated protocol markdown, OpenAPI YAML, and example fixtures live under
  `protocol/`; regenerate them through the Gradle docs generators.
- Runtime compatibility admission truth lives in
  `../packaging/jetbrains/runtime-compatibility.json`; the Rust metadata parser
  consumes exact-root facts but does not own supported-pair policy.

## Verify

Use the narrowest checks that cover the edit, then broaden when shared
contracts move:

```console
cargo fmt --manifest-path cli-rs/Cargo.toml --all -- --check
cargo clippy --manifest-path cli-rs/Cargo.toml --locked --all-targets --all-features -- -D warnings
cargo test --manifest-path cli-rs/Cargo.toml --locked
.github/scripts/test-runtime-compatibility-contract.sh
```

For any workspace-files, packaged guidance, resource, or catalog change, run
all package, LSP, routing, generated-contract, and docs gates below:

```console
cargo run --manifest-path cli-rs/Cargo.toml --bin kast -- developer release generate contract --check
cargo run --manifest-path cli-rs/Cargo.toml --bin kast -- developer codex generate --check
cargo test --manifest-path cli-rs/Cargo.toml --locked --test packaged_content_smoke
cargo test --manifest-path cli-rs/Cargo.toml --locked --test codex_plugin_smoke
cargo test --manifest-path cli-rs/Cargo.toml --locked --test source_index_schema_version_smoke
python3 packaging/homebrew/scripts/test-formulas.py
.github/scripts/test-kast-copilot-plugin.sh
.github/scripts/test-codex-plugin-package-contract.sh
.github/scripts/test-lsp-pivot-gates.sh
.github/scripts/test-kast-routing-evals.sh
.github/scripts/test-docs-content-contract.sh
.github/scripts/test-docs-navigation-contract.sh
zensical build --clean
./gradlew test --no-daemon
./gradlew buildIdeaPlugin --no-daemon
```
