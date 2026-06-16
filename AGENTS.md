# Kast agent guide

This file applies to the whole repository. Deeper `AGENTS.md` files narrow
these rules for their own units; when rules overlap, follow the deeper file.

## Operating posture

Type safety is the default design tool. Prefer compiler-enforced states,
schema-backed contracts, and narrow data types over primitive strings, casts,
or runtime convention. A compiler error is a prevented bug.

Work in the smallest unit that owns the behavior, write the failing test or
contract check first when behavior changes, and verify base assumptions before
editing. Do not bypass compiler, schema, or protocol checks to make a change
"work"; move the invariant into a type, schema, or checked boundary instead.

All dependencies must be declared in `gradle/libs.versions.toml`. Add a
dependency only to the narrowest module that needs it and update every
consumer deliberately.

## Product shape

Kast is a Kotlin analysis tool with one line-delimited JSON-RPC contract and
two supported operator paths:

- the Rust `kast` CLI in `cli-rs/`, which manages local workspace daemons,
  release/install surfaces, the source-index query plane, and the packaged
  Copilot/LSP primitive package;
- the IDEA / Android Studio backend in `backend-idea`, which runs inside an
  IDE process and serves the same analysis contract where capabilities allow.

The Kotlin modules own the backend contract, transport, indexing, and runtime
behavior. The Rust CLI owns operator-facing orchestration, installer behavior,
LSP adaptation, source-index SQLite query methods, and packaged agent assets.

## First moves

- Start with `git status --short --branch`; preserve unrelated dirty work.
- Choose the narrowest unit from the unit map before editing.
- For Kotlin symbol identity, references, call hierarchy, or rename scope,
  use Kast semantic tooling first. Do not use text search as a substitute.
- Before contract-surface changes, enumerate consumers listed in this file and
  update every generated or packaged surface in the same change.
- Verify with the smallest meaningful test or contract check, then broaden
  only when shared contracts, packaging, or cross-module behavior moved.

## Unit map

Use this map to route changes to the smallest owning unit.

- `analysis-api`: shared contract, serializable models, JSON-RPC wire types,
  descriptor discovery helpers, server launch options, errors, file edit
  validation, descriptor schema, and disk edit helpers.
- `analysis-server`: JSON-RPC dispatch, local socket and stdio transport,
  request limits, and descriptor lifecycle.
- `index-store`: SQLite source index persistence, file manifest state,
  workspace discovery cache payload storage, and generic reference-index
  batching without IDEA or backend runtime dependencies.
- `backend-headless`: headless host, Analysis API session bootstrap,
  packaged IDEA runtime bootstrap, and runtime startup.
- `backend-idea`: IDEA / Android Studio plugin backend, project-level service,
  plugin lifecycle, and IDE-hosted analysis server.
- `backend-shared`: shared analysis utilities consumed by backend runtimes via
  compileOnly IDEA platform dependencies.
- `analysis-api/src/testFixtures`: fake backend fixtures and shared contract
  assertions for tests.
- `build-logic`: Gradle convention plugins, runtime-lib sync, wrapper
  generation, and shared build configuration.
- `cli-rs`: Rust control plane, installer, LSP adapter, source-index queries,
  release packaging checks, and packaged Copilot/LSP resources.
- `cli-rs/resources/kast-skill`: shipped Kast skill, command catalog, request
  samples, and validation scripts.
- `cli-rs/resources/plugin`: checked-in source for the Copilot/LSP primitive
  package. Generated target-repo `.github` and `.agents` copies are install
  outputs.
- `docs` plus `zensical.toml`: Zensical source docs and published usage
  guidance.
- `site`: generated static site output. Rebuild it; do not hand-edit it.
- `packaging/homebrew`: release-state inputs consumed by the Rust build and
  release verification.

## Workspace coordination

`workspace.repos.toml` is the source of truth for generated or mirrored
repositories that move with this repo but are not part of this Git history.
Treat those entries as sibling checkouts, not vendored source trees.

- The expected local release mirror layout is `kast/` and `homebrew-kast/` as
  siblings under the same parent directory.
- Run `scripts/workspace-sync-status.sh` before cross-repo release, migration,
  or CLI handoff work. Use `--strict` when automation should fail on a missing
  checkout, remote mismatch, or branch mismatch.
- When the user asks to pull remote main first, sync `main` before planning or
  editing. Preserve unrelated local work with an explicit stash or branch when
  necessary.

## Mandatory Kast routing

Use native `kast_*` tools when the host provides them. The bash fallback is
always the same machine contract:

```console
kast rpc '{"jsonrpc":"2.0","method":"<method>","params":{},"id":1}'
```

The v1 RPC surface has four method families:

- `symbol/*`: name-oriented Kotlin orchestration and refactoring.
- `raw/*`: file, offset, and backend operations.
- `database/*`: Rust-owned source-index SQLite queries.
- system methods: `health`, `runtime/status`, and `capabilities`.

Do not add JVM handlers for operational SQLite reads. Kotlin may hydrate and
write the source index for headless or IDE-backed indexing, but
`database/metrics` and SQLite-backed `symbol/query` are Rust CLI-owned query
paths.

### Search rules

`grep`, `rg`, `ast-grep`, `cat`, and manual parsing must not be used for
Kotlin symbol identity, reference sets, call hierarchy, insertion points, or
rename scope. Use Kast `symbol/*`, `raw/*`, LSP, or source-index routes.

Text tools may be used for exact non-Kotlin paths, generated text, docs,
YAML/JSON/TOML, shell scripts, and final absence checks after the Kast path has
found no semantic candidates.

## Function exposure matrix

The matrix below was refreshed against the latest GitHub release at the time
of this rewrite: `v0.10.1`, published 2026-06-15. The relevant surface files
are unchanged between `v0.10.1` and current `main`:
`cli-rs/resources/kast-skill/references/commands.json`,
`cli-rs/resources/plugin/extensions/kast/_shared/kast-tools.mjs`,
`cli-rs/src/lsp.rs`, `cli-rs/build.rs`,
`cli-rs/resources/plugin/primitive-manifest.json`, and
`cli-rs/resources/plugin/plugin.json`.

Before changing any exposed function, refresh the release tag with
`gh release view --repo amichne/kast --json tagName,publishedAt,url`, compare
the files above against that tag, and update this matrix in the same change.

| Backing RPC method | Copilot tool in v0.10.1 | Custom LSP request in v0.10.1 | Owner | Primary use |
|--------------------|-------------------------|-------------------------------|-------|-------------|
| `symbol/scaffold` | `kast_scaffold` | `kast/symbolScaffold` | backend | Structural Kotlin file or type context. |
| `symbol/discover` | `kast_symbol_discover` | `kast/symbolDiscover` | backend | Rank candidate declarations for an ambiguous name. |
| `symbol/query` | none | `kast/symbolQuery` | Rust SQLite | Query compiler-indexed declarations and graph evidence. |
| `symbol/resolve` | `kast_resolve` | `kast/symbolResolve` | backend | Resolve a named symbol to a declaration. |
| `symbol/references` | `kast_references` | `kast/symbolReferences` | backend | Find usages of a named Kotlin symbol. |
| `symbol/callers` | `kast_callers` | `kast/symbolCallers` | backend | Expand incoming or outgoing call hierarchy by name. |
| `symbol/rename` | `kast_rename` | `kast/symbolRename` | backend | Resolve or target a symbol and apply a rename. |
| `symbol/write-and-validate` | `kast_write_and_validate` | `kast/symbolWriteAndValidate` | backend | Apply generated Kotlin code and validate it. |
| `database/metrics` | `kast_metrics` | `kast/databaseMetrics` | Rust SQLite | Query source-index metrics without JVM passthrough. |
| `health` | none | `kast/health` | backend | Basic daemon health check. |
| `runtime/status` | none | `kast/runtimeStatus` | backend | Runtime readiness and indexing state. |
| `capabilities` | none | `kast/capabilities` | backend | Advertised read and mutation capability set. |
| `raw/diagnostics` | `kast_diagnostics` | none | backend | Kotlin diagnostics for listed files. |
| `raw/file-outline` | `kast_file_outline` | none | backend | Hierarchical symbol outline for one file. |
| `raw/workspace-symbol` | `kast_workspace_symbol` | none | backend | Workspace symbol search by name pattern. |
| `raw/workspace-search` | `kast_workspace_search` | none | backend | Workspace content search by text or regex. |
| `raw/workspace-files` | `kast_workspace_files` | none | backend | Workspace modules and optional file paths. |

Standard `kast lsp --stdio` requests are also exposed when the selected
backend advertises the matching capability:

| Standard LSP request | Backing Kast method | Capability gate |
|----------------------|---------------------|-----------------|
| `textDocument/definition` | `raw/resolve` | `RESOLVE_SYMBOL` read |
| `textDocument/hover` | `raw/resolve` | `RESOLVE_SYMBOL` read |
| `textDocument/references` | `raw/references` | `FIND_REFERENCES` read |
| `textDocument/documentSymbol` | `raw/file-outline` | `FILE_OUTLINE` read |
| `workspace/symbol` | `raw/workspace-symbol` | `WORKSPACE_SYMBOL_SEARCH` read |
| `textDocument/implementation` | `raw/implementations` | `IMPLEMENTATIONS` read |
| `textDocument/prepareCallHierarchy`, `callHierarchy/incomingCalls`, `callHierarchy/outgoingCalls` | `raw/resolve`, `raw/call-hierarchy` | `CALL_HIERARCHY` read |
| `textDocument/prepareTypeHierarchy`, `typeHierarchy/supertypes`, `typeHierarchy/subtypes` | `raw/resolve`, `raw/type-hierarchy` | `TYPE_HIERARCHY` read |
| `textDocument/prepareRename`, `textDocument/rename` | `raw/resolve`, `raw/rename` | `RENAME` mutation |

## Contract sources and generated surfaces

`cli-rs/resources/kast-skill/references/commands.json` is the checked-in source
for method names, request fields, variants, enum values, response types,
Copilot tool exposure, and generated `kast/*` custom LSP routes.

When changing the command catalog or JSON-RPC contract:

- update `commands.json` first;
- regenerate/check `commands.yaml` and request samples with
  `python3 cli-rs/resources/kast-skill/scripts/generate-rpc-contract.py --check`;
- validate request samples with
  `python3 cli-rs/resources/kast-skill/scripts/validate-rpc-request.py --all-samples`;
- update generated docs through `.github/scripts/render-rpc-contract-summary.py`
  or `.github/scripts/test-docs-content-contract.sh`;
- validate the packaged Copilot/LSP primitive with
  `.github/scripts/test-kast-copilot-plugin.sh`.

Before modifying `AnalysisBackend`, the `kast rpc` machine contract surface, or
any packaged artifact manifest, enumerate all consumers:
`docs/openapi.yaml`, `cli-rs/resources/kast-skill/SKILL.md`,
`cli-rs/resources/kast-skill/evals/**/*`,
`cli-rs/resources/kast-skill/references/*`,
`cli-rs/resources/kast-skill/scripts/*`, `evaluation/**/*`,
`cli-rs/resources/plugin/**/*`, `cli-rs/resources/**/*`, and `kast.sh`.

## Copilot/LSP package

`cli-rs/resources/plugin/` is the primary source for Copilot-assisted Kotlin
work. It provides:

- `lsp.json`, which starts `kast lsp --stdio`;
- Kotlin-scoped instructions for Copilot hosts;
- the SDK extension that exposes catalog-backed `kast_*` tools;
- `primitive-manifest.json`, which maps source files into target-repo
  `.github` outputs.

Generated `.github` and `.agents` copies in target repositories are install
outputs. Do not hand-edit them as source. Repair or regenerate them through
`kast install copilot` or `cli-rs/resources/plugin/scripts/install-local.sh`.

Fall back to `cli-rs/resources/kast-skill/SKILL.md` when native host tooling is
unavailable or when exact command shape, request validation, or recovery
guidance is needed.

## Working rules

- Pull shared semantics down into `analysis-api` only when multiple hosts or
  transports need them.
- Keep host-specific dependencies out of `analysis-api` and `analysis-server`.
- Keep headless runtime behavior in `backend-headless` unless another
  surviving runtime genuinely needs it.
- Keep PSI and IDE lifecycle behavior in `backend-idea` or `backend-shared`,
  not in transport or API modules.
- Keep capability gating honest. A transport or backend must not advertise
  support for work it cannot perform.
- Use `kast` in commands, docs, packaging targets, and user-facing examples.
- Treat serialized model changes, descriptor fields, source-index schema,
  capability names, CLI help, installer outputs, and release assets as
  contract changes.
- Prefer repo-root packaging entry points for shipped artifacts:
  `./kast.sh build` builds portable distribution artifacts, and
  `./gradlew buildIdeaPlugin` builds the IDEA plugin zip.
- Treat `docs/` plus `zensical.toml` as documentation source. Rebuild `site/`
  rather than editing it.

## Verification routing

Use the narrowest check that proves the changed surface:

- Kotlin contract model changes: `./gradlew :analysis-api:test`.
- JSON-RPC dispatch or transport changes: `./gradlew :analysis-server:test`.
- SQLite/source-index changes: `./gradlew :index-store:test`.
- Headless runtime changes: `./gradlew :backend-headless:test`.
- IDEA plugin changes: `./gradlew :backend-idea:test` when pinned IDE
  artifacts are available.
- Build logic changes: start with the affected build-logic test and broaden to
  `./gradlew build` for shared convention changes.
- Rust CLI or installer changes: run the focused `cargo test --locked` target
  in `cli-rs`, plus the relevant shell contract script under `.github/scripts`.
- Docs/navigation changes: run `.github/scripts/test-docs-content-contract.sh`
  and `zensical build --clean` when rendered output matters.
- Copilot/LSP package changes: run `.github/scripts/test-kast-copilot-plugin.sh`
  and the LSP config/pivot checks under `.github/scripts`.

If a validation step cannot be run locally, state exactly why and list the
next command that should be run.
