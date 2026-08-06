# Rust CLI and agent resource guide

This file applies to `cli-rs/` and descendants. Deeper `AGENTS.md` files narrow
the rules for their subtrees. This tree owns the Rust AXI CLI, typed agent
command surface, installer, manifest-backed resource trust, runtime lifecycle
orchestration, source-index CLI reads, and release packaging.

## Local purpose

- `src/interface/cli/agent/agent_surface.rs` and `src/main.rs` define the public
  agent-facing `kast` CLI. `src/interface/cli/root.rs` retains administrative
  operations behind the private `kastctl` multicall name.
- `src/interface/cli/agent.rs` and `src/agent/` own typed compiler-backed agent commands:
  the cross-provider `task begin|status|finish|abort` proof lifecycle plus
  `lease`, `verify`, `workspace-files`, `symbol`, `diagnostics`, `impact`,
  and `rename`. Task ownership is persisted and cross-process; it is distinct
  from the exact-root indexer lease used by semantic requests.
- `src/execution/runtime/` owns the one typed indexer admission boundary,
  descriptor proof, exact-root leases, and indexer lifecycle. On macOS, an
  installed supported IDE supplies private sidecar runtime files only.
- `src/operations/install/` owns the sole persistent setup transaction: bundle validation,
  staging, atomic activation, rollback, active-root verification, receipts, and
  bounded legacy backup.
- `src/semantics/symbol_query/` and `src/storage/metrics_database/` own operational source-index
  reads for the Rust CLI.
- `src/semantics/workspace_inventory.rs` and `src/semantics/workspace_inventory/` own uncapped
  exact-root `.kt` index reads, compiler/project-model candidate composition,
  deepest-existing-ancestor path containment, source generation/progress/
  pending evidence, build-qualified indexed Gradle project identities, the
  structured Gradle source-set and Kotlin package provenance states, the
  kind-relevant backend/index/filesystem/Git coherence barrier, and typed
  limitations used by `agent workspace-files` and Gradle DSL consumers.
- `src/operations/install.rs`, `src/configuration/manifest.rs`,
  `src/operations/self_mgmt.rs`, and
  `src/operations/self_mgmt/agent_readiness.rs` own install state, managed resource
  records, doctor checks, effective binary/backend evidence, and readiness
  behavior.
- `src/operations/self_mgmt.rs` owns install readiness. Semantic readiness also
  requires one live exact-root semantic workspace admission; extension metadata
  is never readiness evidence.
- `resources/kast/` owns the embedded Codex, Claude, and Copilot resources
  installed by `install.sh`; installation must not fetch a remote marketplace.
- `protocol/source/` contains the authored internal catalog plus generated
  schemas and request samples. Other `protocol/` outputs serve release and
  integration consumers.

The retained cross-module boundaries are:

- `.agents/adr/0025-indexer-bound-opaque-selector-handles.md`
- `.agents/adr/0026-proof-carrying-relationship-coverage.md`
- `.agents/adr/0027-effective-agent-environment-readiness.md`
- `.agents/adr/0028-exact-root-agent-workspace-leases.md`
- `.agents/adr/0031-cli-install-and-data-authority.md`
- `.agents/adr/0033-exact-root-indexer-authority.md`

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
- Agent-facing semantic workflows use the public `kast` surface. Administrative
  lifecycle operations use the release-local
  `libexec/kastctl`.
- Route every semantic read, mutation, graph operation, readiness check, lease,
  and lifecycle operation through `SemanticWorkspaceAdmission`. Do not inspect,
  select, contact, start, or stop a foreground application.
- Treat a runtime descriptor as evidence only after exact canonical-root,
  indexer-kind, runtime-instance, process-start, effective-owner, socket
  device/inode, status, schema, and capability checks. Revalidate that proof
  before an RPC or destructive lifecycle action.
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
- Codex, Claude, and Copilot resource truth lives under `resources/kast/`.
- Generated request schemas and samples under `protocol/source/requests/` are
  derived from the catalog. Regenerate them through the contract generator.
- Generated protocol markdown, OpenAPI YAML, and example fixtures live under
  `protocol/`; regenerate them through the Gradle docs generators.
- Supported private indexer host inputs live in
  `../packaging/indexer/runtime-compatibility.json`. They do not authorize a
  foreground IDE process, public plugin, or semantic endpoint.

## Verify

Use the narrowest checks that cover the edit, then broaden when shared
contracts move:

```console
cargo fmt --manifest-path cli-rs/Cargo.toml --all -- --check
cargo clippy --manifest-path cli-rs/Cargo.toml --locked --all-targets --all-features -- -D warnings
cargo test --manifest-path cli-rs/Cargo.toml --locked
.github/scripts/runtime/test-runtime-compatibility-contract.sh
```

For workspace-files, resource, or catalog changes, run the relevant
generated-contract and docs gates below:

```console
cargo test --manifest-path cli-rs/Cargo.toml --locked --test rpc_catalog_smoke
cargo test --manifest-path cli-rs/Cargo.toml --locked --test source_index_schema_version_smoke
.github/scripts/docs/test-docs-content-contract.sh
.github/scripts/docs/test-docs-navigation-contract.sh
zensical build --clean
./gradlew test --no-daemon
./gradlew :analysis-api:test :analysis-server:test :indexer:test :index-store:test --no-daemon
```

## Agent-safe Rust tooling

Run Rust commands from the repository root with an explicit manifest or the
checked-in wrapper. Keep data on standard output machine-readable. Leave
progress and compiler rendering on standard error.

Read the package and target graph without resolving dependencies or using the
network:

```console
scripts/rust-agent-metadata.sh
```

Collect structured compiler diagnostics:

```console
cargo check --manifest-path cli-rs/Cargo.toml --locked --all-targets --all-features --message-format=json-diagnostic-rendered-ansi
```

Use rust-analyzer for batch semantic validation and ast-grep for syntax-aware
search. Ast-grep is not type-aware, so confirm every edit with the compiler.

```console
rust-analyzer analysis-stats cli-rs
ast-grep run --lang rust --pattern '$VALUE.clone()' --json=stream cli-rs/src
```

Use nextest for focused RED and GREEN proof. A test that exceeds 15 seconds is
reported as slow. Failures are not retried.

```console
cargo nextest run --manifest-path cli-rs/Cargo.toml --locked --test <test-target> <test-name>
```

Nextest does not run documentation tests. Retain a separate documentation-test
command when a library target is present.
