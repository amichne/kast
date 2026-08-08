---
type: Runtime Flow
title: How Kast works
description: End-to-end map from the public CLI through indexer admission, compiler analysis, storage, and typed output.
resource: file://docs/internal/system-flow.md
tags: [architecture, cli, indexer, runtime, okf]
code_sources:
  - path: cli-rs/src/main.rs
  - path: cli-rs/src/interface/cli/agent/agent_surface.rs
    symbols: [KastCli, KastCommand]
  - path: cli-rs/src/agent/adapter/mod.rs
  - path: cli-rs/src/interface/cli/root.rs
    symbols: [Cli, Command]
  - path: cli-rs/src/interface/entrypoint/dispatch.rs
    symbols: [run, run_agent, run_runtime]
  - path: cli-rs/src/operations/install/bundle_entrypoint.rs
  - path: cli-rs/src/operations/install/agent_resources.rs
  - path: cli-rs/resources/kast/codex/hooks.json
  - path: cli-rs/resources/kast/claude/hooks.json
  - path: cli-rs/resources/kast/copilot/hooks.json
  - path: cli-rs/src/execution/runtime/backend/workspace.rs
    symbols: [workspace_ensure, workspace_status, workspace_stop]
  - path: cli-rs/src/execution/runtime/backend/workspace_admission.rs
  - path: cli-rs/src/agent/core/dispatch/commands.rs
    symbols: [run, execute]
  - path: cli-rs/src/agent/core/request.rs
    symbols: [execute_request]
  - path: cli-rs/protocol/source/commands.json
  - path: analysis-api/src/main/kotlin/io/github/amichne/kast/api/contract/backend/AnalysisBackend.kt
    symbols: [AnalysisBackend]
  - path: analysis-server/src/main/kotlin/io/github/amichne/kast/server/dispatch/RpcAnalysisDispatcher.kt
    symbols: [RpcAnalysisDispatcher]
  - path: analysis-server/src/main/kotlin/io/github/amichne/kast/server/dispatch/RpcMethodRouter.kt
    symbols: [RpcMethodRouter]
  - path: analysis-server/src/main/kotlin/io/github/amichne/kast/server/skill/SkillRpcOrchestrator.kt
    symbols: [SkillRpcOrchestrator]
  - path: indexer/src/main/kotlin/io/github/amichne/kast/indexer/KastIndexerRuntime.kt
    symbols: [KastIndexerRuntime]
  - path: indexer/src/main/kotlin/io/github/amichne/kast/idea/backend/KastIndexerBackend.kt
    symbols: [KastIndexerBackend]
  - path: indexer/src/main/kotlin/io/github/amichne/kast/idea/workspace/indexing/IdeaProjectIndexer.kt
    symbols: [IdeaProjectIndexer]
  - path: index-store/src/main/kotlin/io/github/amichne/kast/indexstore/indexing/ReferenceIndexer.kt
    symbols: [ReferenceIndexer]
  - path: index-store/src/main/kotlin/io/github/amichne/kast/indexstore/store/codec/StringInterningCodec.kt
    symbols: [StringInterningCodec]
  - path: index-store/src/main/kotlin/io/github/amichne/kast/indexstore/store/SqliteSourceIndexStore.kt
    symbols: [SqliteSourceIndexStore]
  - path: index-store/src/main/kotlin/io/github/amichne/kast/indexstore/store/sqlite/semantic/SemanticGraphWriter.kt
    symbols: [SemanticGraphWriter]
  - path: .agents/adr/0025-indexer-bound-opaque-selector-handles.md
  - path: .agents/adr/0026-proof-carrying-relationship-coverage.md
  - path: .agents/adr/0027-effective-agent-environment-readiness.md
  - path: .agents/adr/0028-exact-root-agent-workspace-leases.md
  - path: .agents/adr/0031-cli-install-and-data-authority.md
  - path: .agents/adr/0033-exact-root-indexer-authority.md
---

# How Kast works

Kast has one public API: the agent-focused `kast` command-line interface. The
same executable bytes run the internal KastCTL control plane when invoked as
release-local `libexec/kastctl`. The installer and harness hooks adapt external
events into these entrypoints. One exact-root indexer owns compiler work.

This page is an Open Knowledge Format (OKF) `Runtime Flow` concept. Its
`code_sources` identify the current owners of each claim.

## Public API coverage

The visible `kast --help` surface contains only agent-actionable operations.
Its command families are `kast workspace`, `kast file`, `kast symbol`,
`kast relation`, `kast graph`, `kast diagnostic`, and `kast change`.

| Family | Commands | Boundary |
| --- | --- | --- |
| Orientation | `kast` | Discover the nearest Gradle root and report readiness and next actions. |
| Indexer | `kast workspace ensure` | Reuse or create the exact-root indexer and await evidence. |
| Refresh | `kast workspace refresh` | Refresh changed or selected files. |
| Discovery | `kast file`, `kast symbol`, `kast relation` | Enumerate files, resolve symbols, and traverse relationships. |
| Graph | `kast graph` | Read generation-pinned topology, communities, and impact. |
| Diagnostics | `kast diagnostic check` | Report compiler diagnostics. |
| Mutations | `kast change plan`, `kast change apply` | Validate a plan, then apply its opaque identifier with an exact-root lease. |

`libexec/kastctl` preserves the full administrative CLI for setup, process
control, raw RPC, release, and developer automation. It is private, is not on
`PATH`, and is not exposed to agents.

## End-to-end system flow

Process startup selects the grammar from the invoked basename. `kast` enters
the compact public parser. `kastctl` enters the administrative parser. An
unsupported basename fails before command dispatch.

<kast-view view-id="system-landscape" browser="true"></kast-view>

<kast-view view-id="runtime-components" browser="true"></kast-view>

### Installation and control

`libexec/kastctl setup` validates an untrusted bundle, stages a complete
release, atomically switches `current`, verifies both entrypoints, and restores
the prior release on failure.

The bundle contains byte-identical `bin/kast` and `libexec/kastctl`
entrypoints. Standard and snapshot setup link only `kast` onto `PATH`.
Development setup links both commands. The install receipt records each exact
PATH projection and supplies the only authority to replace or remove the
developer `kastctl` link. Setup also materializes release-matched Codex,
Claude, and Copilot resources locally.

### Indexer admission

`kast workspace ensure` discovers the nearest Gradle workspace. It reuses an eligible healthy
indexer bound to that canonical root. If none exists, Kast creates an isolated
indexer. Descriptor, process, endpoint, release, health, and capability
evidence must all match.

On macOS, a supported JetBrains installation supplies compatible libraries.
Kast does not install into, open, close, or route through the foreground
application. `INDEXING` proves reachability; semantic commands require
`READY`.

<kast-view view-id="indexer-runtime" browser="true"></kast-view>

### Compiler reads

Agent commands become bounded request envelopes. Exact-root admission selects
the indexer and local socket. `RpcAnalysisDispatcher` validates the envelope,
`RpcMethodRouter` checks capabilities, and `SkillRpcOrchestrator` runs the
semantic workflow.

`io.github.amichne.kast.api.contract.backend.AnalysisBackend` remains the typed
Kotlin contract. `KastIndexerBackend` is its sole production implementation,
and `KastIndexerRuntime` is its process owner. PSI and Analysis API objects do
not cross that contract.

<kast-view view-id="indexer-pipeline" browser="true"></kast-view>

<kast-view view-id="compiler-read" browser="true" dynamic-variant="sequence"></kast-view>

<kast-view view-id="compiler-evidence" browser="true"></kast-view>

### Indexing and graph reads

The indexer builds source inventory, declarations, references, and semantic
graph facts. `SqliteSourceIndexStore` retains those products in one
workspace-scoped WAL database. Each answer keeps generation and coverage
evidence.

<kast-view view-id="indexing-landscape" browser="true"></kast-view>

<kast-view view-id="indexing-guide" browser="true"></kast-view>

<kast-view view-id="reference-indexing" browser="true" dynamic-variant="sequence"></kast-view>

<kast-view view-id="retained-evidence" browser="true"></kast-view>

<kast-view view-id="sqlite-pipeline" browser="true" dynamic-variant="sequence"></kast-view>

`kast workspace externalize --failure-id <FAILURE_ID>` verifies a content-bound failure,
clears unsupported outgoing facts, and records an `UNKNOWN` graph boundary.
Cancellation, corruption, protocol, and infrastructure failures remain
terminal.

### Mutations and hooks

`kast change plan` consumes an exact selector and persists a proof-carrying plan.
`kast change apply` owns the workspace lease, revalidates the plan, and journals
exact recovery authority. It applies the write and verifies the compiler
postcondition. `kast change recover` either completes verification or restores the
exact pre-state after an interrupted apply. A stale identity or incomplete
analysis remains a failure.

<kast-view view-id="semantic-mutation" browser="true" dynamic-variant="sequence"></kast-view>

Codex, Claude, and Copilot hooks invoke the release-matched private control
bridge for activation checks. Codex and Claude can reject startup. Copilot
denies tool use at its first blocking hook when activation is incompatible.
Hooks do not run setup or claim compiler proof.

<kast-view view-id="refresh-lifecycle" browser="true" dynamic-variant="sequence"></kast-view>

### Work with diagrams locally

Use the checked scripts:

```shell
npm install
npm run diagrams:dev
npm run diagrams:validate
npm run diagrams:embed
```

`diagrams:embed` regenerates the checked-in Web Component module used here.

## Durable system invariants

| Invariant | Meaning |
| --- | --- |
| One installation authority | The active CLI receipt owns installation identity and Kast data paths. |
| Exact-root readiness | A process is reusable only when release, root, identity, health, and capabilities match. |
| One indexer | One process owns compiler work and the persistent writer for each admitted root. |
| Opaque selector identity | Handles bind exact workspace, indexer, generation, declaration, and operation family. |
| Proof-carrying coverage | Returned rows do not imply a complete search. |
| Ownership-safe cleanup | A lease can stop only the matching process it started. |

These constraints are more stable than the individual class layout.

## Answering "How does this part work?"

A useful answer contains the public command, route through admission and
storage, constraining invariant, concrete source owners, and returned coverage
or typed blocker.

Start from the exact root:

```shell
kast
kast workspace ensure
kast graph topology --scope symbol
kast graph communities --scope symbol
```

Resolve exact Kotlin identity before navigation:

```shell
kast symbol resolve --query io.github.amichne.kast.api.contract.backend.AnalysisBackend
kast symbol show --selector <SELECTOR>
```

Then use `kast relation calls incoming`, `kast relation calls outgoing`, or
`kast relation implementations`, each with `--selector <SELECTOR>`. If Kast
returns ambiguity, incomplete coverage,
or a typed readiness blocker, report it. Do not fill the gap with an inferred
edge.
