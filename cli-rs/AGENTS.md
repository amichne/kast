# Rust CLI and agent resource guide

This file applies to `cli-rs/` and descendants. Deeper `AGENTS.md` files narrow
the rules for their subtrees. This tree owns the Rust AXI CLI, typed agent
command surface, installer, manifest-backed resource trust, runtime lifecycle
orchestration, source-index CLI reads, and release packaging.

## Local purpose

- `src/cli/root.rs` and `src/main.rs` define the root AXI CLI: compact context,
  transactional setup, readiness, status, and developer operations.
- `src/cli/agent.rs` and `src/agent/` own typed compiler-backed agent commands:
  the cross-provider `task begin|status|finish|abort` proof lifecycle plus
  `lease`, `verify`, `workspace-files`, `symbol`, `diagnostics`, `impact`,
  `rename`, and `lsp`. Task ownership is persisted and cross-process; it is
  distinct from the IDEA runtime lease used by individual semantic requests.
- `src/runtime/` owns IDEA lifecycle inspection and exact-root leases on macOS,
  plus release headless lifecycle on supported non-macOS hosts.
- `src/install/` owns the sole persistent setup transaction: bundle validation,
  staging, atomic activation, rollback, active-root verification, receipts, and
  bounded legacy backup.
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
  records, doctor checks, effective binary/backend evidence, and readiness
  behavior.
- `src/self_mgmt.rs` parses revision-3 exact-root compatibility facts strictly
  and delegates active admission to the authored typed compatibility matrix.
  Unknown fields, capabilities, revisions, unsupported rows, and missing
  required capabilities fail closed; missing optional capabilities remain
  local to the operation that needs them.
- `https://github.com/amichne/kast-marketplace` owns the Codex marketplace,
  routing skill, hook wiring, launcher, and presentation assets. This crate
  retains only the CLI hook implementation.
- `resources/plugin/` owns the independent GitHub Copilot package source
  material used by release validation.
- `protocol/source/` contains the authored internal catalog plus generated
  schemas and request samples. Other `protocol/` outputs serve release and
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
The sole installation boundary lives in
`.agents/adr/0031-sole-transactional-setup-authority.md`.

## Edit rules

- Keep command invariants in typed Rust structures. Clap, serde, and catalog
  schema validation own command parsing and structured data boundaries.
- Keep Codex hook parsing, decisions, state, and output schemas in Rust. The
  launcher may only resolve the active binary and forward the event and stdin.
  Hooks may inspect readiness but must never invoke setup mutations.
- Keep task completion evidence in the typed task core. Provider adapters may
  supply a session identity and render provider-native allow/block decisions,
  but must not duplicate changed-file, diagnostics-hash, Gradle-outcome, or
  test-report policy.
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
- `protocol/source-index-schema-version.txt` is the schema-version source
  consumed by `build.rs`. Keep its generated Rust value aligned with build-logic's Kotlin
  value and fail closed on an older/malformed source-index schema.
- Captured or agent-run commands default to compact structured output. Public
  semantic mutations are plan-first and gated. Setup always replaces the
  complete Kast-owned release from one verified bundle.
- Treat generated or installed resource copies as outputs. Edit the authored
  resource source, then regenerate or rerun `kast setup --source <bundle>`.
- When install output shape changes, update manifest resource recording,
  doctor verification, package scripts, docs, and smoke tests in the same
  change.
- Diagnostics completion proof requires the backend response to carry one
  current-read hash for every requested file. Never reuse a pre-request hash
  or silently accept a missing file hash.

## Source boundaries

- Command catalog truth lives in
  `protocol/source/commands.json`.
- Codex skill and launcher truth lives in `amichne/kast-marketplace`; no
  marketplace payload or generator belongs in this repository.
- Package artifact output shape lives in `resources/plugin/primitive-manifest.json`.
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

For workspace-files, Copilot package, resource, or catalog changes, run the
relevant package, LSP, generated-contract, and docs gates below:

```console
cargo run --manifest-path cli-rs/Cargo.toml --bin kast -- developer release generate contract --check
cargo test --manifest-path cli-rs/Cargo.toml --locked --test source_index_schema_version_smoke
.github/scripts/test-kast-copilot-plugin.sh
.github/scripts/test-lsp-pivot-gates.sh
.github/scripts/test-docs-content-contract.sh
.github/scripts/test-docs-navigation-contract.sh
zensical build --clean
./gradlew test --no-daemon
./gradlew buildIdeaPlugin --no-daemon
```
