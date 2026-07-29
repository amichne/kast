---
type: Runtime Flow
title: How Kast works
description: End-to-end map from Kast's public CLI through runtime admission, compiler analysis, storage, and proof-carrying output.
resource: file://docs/internal/system-flow.md
tags: [architecture, cli, runtime, okf]
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
  - path: backend-idea/src/main/kotlin/io/github/amichne/kast/idea/backend/KastPluginBackend.kt
    symbols: [KastPluginBackend]
  - path: backend-idea/src/main/kotlin/io/github/amichne/kast/idea/backend/mutation/MutationOperations.kt
  - path: backend-idea/src/main/kotlin/io/github/amichne/kast/idea/workspace/indexing/IdeaProjectIndexer.kt
    symbols: [IdeaProjectIndexer]
  - path: backend-headless/src/main/kotlin/io/github/amichne/kast/headless/runtime/HeadlessRuntime.kt
    symbols: [HeadlessRuntime]
  - path: index-store/src/main/kotlin/io/github/amichne/kast/indexstore/indexing/ReferenceIndexer.kt
    symbols: [ReferenceIndexer]
  - path: index-store/src/main/kotlin/io/github/amichne/kast/indexstore/store/StringInterningCodec.kt
    symbols: [StringInterningCodec]
  - path: index-store/src/main/kotlin/io/github/amichne/kast/indexstore/store/SqliteSourceIndexStore.kt
    symbols: [SqliteSourceIndexStore]
  - path: index-store/src/main/kotlin/io/github/amichne/kast/indexstore/store/sqlite/semantic/SemanticGraphWriter.kt
    symbols: [SemanticGraphWriter]
  - path: .agents/adr/0025-backend-bound-opaque-selector-handles.md
  - path: .agents/adr/0026-proof-carrying-relationship-coverage.md
  - path: .agents/adr/0027-effective-agent-environment-readiness.md
  - path: .agents/adr/0028-exact-root-agent-workspace-leases.md
  - path: .agents/adr/0031-cli-install-and-data-authority.md
  - path: .agents/adr/0032-macos-idea-golden-pathway.md
---

# How Kast works

Kast has one public API: the agent-focused `kast` command-line interface (CLI).
The same executable bytes run the internal `_kastctl` control plane when
invoked under that name. The shell installer and harness hooks adapt external
events into these named entrypoints. IntelliJ IDEA and the headless host
implement the compiler boundary.

This page is an Open Knowledge Format (OKF) `Runtime Flow` concept. Its
`code_sources` point to the current owners of each claim. Use the map to start
an answer, then use Kast to prove Kotlin identities and relationships.

## Public API coverage

The visible `kast --help` surface contains only agent-actionable operations.

| Public family | Commands | Boundary |
| --- | --- | --- |
| Orientation | `kast` | Discover the nearest Gradle root and report readiness, limits, and next actions. |
| Runtime | `kast up` | Start or reuse the exact-root runtime and wait for semantic evidence. |
| Evidence refresh | `kast refresh`, `kast refresh external` | Refresh changed or selected files, or accept eligible failures as external boundaries. |
| Discovery | `kast files`, `kast symbol` | Enumerate Kotlin files, resolve symbols, and traverse compiler relationships. |
| Graph | `kast graph` | Read generation-pinned nodes, neighborhoods, topology, communities, and impact. |
| Diagnostics | `kast check` | Report compiler diagnostics for changed or selected files. |
| Mutations | `kast change`, `kast apply` | Validate a semantic plan, then apply its opaque identifier. |

`_kastctl` preserves the full administrative CLI for setup, runtime control,
raw RPC, release, and developer automation. It is not exposed to agents.
`install.sh` downloads or opens a bundle, delegates activation to
`_kastctl setup`, then invokes the hidden public-resource installer for each
selected harness.

## End-to-end system flow

Process startup selects the grammar from the invoked basename. `kast` enters
the compact public parser. `_kastctl` enters the preserved administrative
parser. An unsupported basename fails before command dispatch.

<kast-view view-id="system-landscape" browser="true"></kast-view>

Select Kast in the diagram to drill into its implicit component view, or use
the explicit runtime map:

<kast-view view-id="runtime-components" browser="true"></kast-view>

### Installation and control flow

`_kastctl setup` is the persistent installation operation. It validates an
untrusted bundle, stages a complete release, atomically switches `current`,
verifies both entrypoints, and restores the prior release on failure.

The activated bundle contains byte-identical `kast` and `_kastctl`
entrypoints. The installer materializes release-matched Codex, Claude, and
Copilot resources locally. No remote marketplace becomes runtime authority.

### Runtime and readiness flow

`kast` and `kast up` discover the nearest Gradle workspace before selecting a
backend. Runtime inspection reads descriptors, checks process reachability,
validates compatibility, and rejects ambiguous hosts. `kast up` reuses a
servable exact-root runtime before it starts another one.

On macOS, the supported path reuses an open exact-root IDEA project or asks the
sole compatible host to open it. On supported non-macOS hosts, Kast may start
the packaged headless runtime. `INDEXING` proves reachability only. Semantic
commands require `READY`.

<kast-view view-id="macos-runtime" browser="true"></kast-view>

### Compiler read flow

Agent reads first normalize the command into an `AgentEnvelope`. Exact-root
workspace admission then selects a runtime and local socket. The CLI validates
the internal request against the checked command catalog before sending it.

`RpcAnalysisDispatcher` validates the JSON-RPC envelope and applies the request
timeout. `RpcMethodRouter` checks the advertised capability and routes typed
parameters. `SkillRpcOrchestrator` handles the existing typed semantic
workflows.
`AnalysisBackend` defines the shared Kotlin boundary.

`KastPluginBackend` implements that boundary with IntelliJ PSI and the Kotlin
compiler. The headless host starts the same IDEA backend runtime with a
different host bootstrap. Both return the same result contracts.

<kast-view view-id="idea-semantic-pipeline" browser="true"></kast-view>

PSI, Analysis API sessions, K2 frontend state, and FIR are live compiler
objects. Kast retains provider-neutral identities, diagnostics, references,
and semantic graph facts instead of retaining those objects.

<kast-view view-id="compiler-read" browser="true" dynamic-variant="sequence"></kast-view>

### Repository and graph flow

`kast files`, `kast symbol`, and `kast graph` combine typed filesystem, Gradle
model, compiler, and SQLite evidence. Some requests finish in the Rust CLI
from persisted state. Refresh and live relationship requests route through
the backend.

`SqliteSourceIndexStore` owns indexed files, declarations, references,
generations, snapshots, and semantic graph facts. A graph answer is usable
only when its generation and coverage match the requested scope.

<kast-view view-id="indexing-landscape" browser="true"></kast-view>

Use the routing view to open the project-index, SQLite, or refresh sequence:

<kast-view view-id="indexing-guide" browser="true"></kast-view>

Project open after Gradle import is the production full-reference-index
trigger. It replaces the source inventory first, then dependency-prioritizes
modules and persists successful declaration and reference batches through
serialized SQLite transactions.

<kast-view view-id="reference-indexing" browser="true" dynamic-variant="sequence"></kast-view>

The reference index and explicit semantic graph are separate retained
products. `kast refresh` coordinates their supported producer paths for
changed or selected files.

Eligible file-local reference failures remain visible without aborting other
files. `kast refresh external <FAILURE_ID>...` verifies each content-bound
failure, clears unsupported outgoing facts, and records an `UNKNOWN` graph
boundary. Inbound references to retained boundary symbols remain evidence.
Cancellation, corruption, protocol, and infrastructure failures stay
terminal.

<kast-view view-id="retained-evidence" browser="true"></kast-view>

<kast-view view-id="sqlite-pipeline" browser="true" dynamic-variant="sequence"></kast-view>

<kast-view view-id="compiler-evidence" browser="true"></kast-view>

### Mutation flow

`kast change` resolves the target and validates a semantic edit without
applying it. The CLI returns an opaque plan identifier. `kast apply` reloads
that plan, rechecks its root and authority, and applies it with retry-safe
idempotency.

The public result preserves typed failure. A failed apply, stale identity,
incomplete analysis, or missing capability does not become success through
projection.

Ordinary writes refresh live VFS, Kotlin-index, PSI, Analysis API admission,
and focused diagnostics. They do not automatically rebuild retained reference
rows or semantic graph facts.

<kast-view view-id="semantic-mutation" browser="true" dynamic-variant="sequence"></kast-view>

### Harness hook flow

Codex, Claude, and Copilot session hooks invoke `~/.local/bin/kast` from the
active workspace. They receive the same compact readiness result an agent gets
from direct invocation. Hook failures add advisory context; they do not run
setup or claim compiler proof.

`kast refresh` with no path selects changed Kotlin files. An explicit path set
refreshes only those files through the supported compiler and persistence
routes.

<kast-view view-id="refresh-lifecycle" browser="true" dynamic-variant="sequence"></kast-view>

### Work with the diagrams locally

Install the pinned CLI once, then use the repository scripts:

```shell
npm install
npm run diagrams:dev
npm run diagrams:validate
npm run diagrams:embed
```

`diagrams:dev` serves every view with hot reload. `diagrams:embed` refreshes the
checked-in Web Component bundle used by this page.

## Durable system invariants

The retained architecture decision records describe six constraints that span
the whole graph. Their durable meaning is:

| Invariant | Meaning in the flow | Decision source |
| --- | --- | --- |
| One installation authority | The active CLI receipt owns installation identity and all derived Kast paths. Setup is the only persistent installer. | [ADR 0031](https://github.com/amichne/kast/blob/main/.agents/adr/0031-cli-install-and-data-authority.md) |
| Readiness composes evidence | A reachable process is insufficient. The active release, compatible backend, exact workspace, and capability evidence must agree. | [ADR 0027](https://github.com/amichne/kast/blob/main/.agents/adr/0027-effective-agent-environment-readiness.md) |
| The semantic workspace is exact | Runtime reuse, descriptors, indexes, and commands bind one canonical root. No fallback may silently select another project. | [ADR 0032](https://github.com/amichne/kast/blob/main/.agents/adr/0032-macos-idea-golden-pathway.md) |
| Selector handles are identity proofs | A handle binds workspace, backend instance, semantic generation, declaration, and allowed operation families. Clients keep it opaque. | [ADR 0025](https://github.com/amichne/kast/blob/main/.agents/adr/0025-backend-bound-opaque-selector-handles.md) |
| Data and completeness are separate | Returned rows do not prove a complete search. Coverage, truncation, timeout, cancellation, and index limits remain visible. | [ADR 0026](https://github.com/amichne/kast/blob/main/.agents/adr/0026-proof-carrying-relationship-coverage.md) |
| Cleanup follows ownership | A lease may stop only the matching runtime it started. Borrowed IDEA and headless runtimes remain running. | [ADR 0028](https://github.com/amichne/kast/blob/main/.agents/adr/0028-exact-root-agent-workspace-leases.md) |

These constraints are more important than the individual class layout. When
implementation moves, the system still needs one authority, exact identity,
proof-carrying coverage, and ownership-safe lifecycle behavior.

## Answering "How does this part work?"

The guarantee is an answer shape, not a promise that every index is healthy.
For every supported public CLI family, a useful answer contains:

1. The public command or adapter that starts the flow.
2. The route through dispatch, admission, transport, backend, storage, and
   projection.
3. The invariant that constrains that route.
4. The concrete source paths and symbols that own the behavior.
5. Kast coverage, limitations, or a typed blocker.

Start by inspecting and preparing the exact workspace:

```shell
kast
kast up
```

For a broad Kotlin architecture question, inspect graph shape:

```shell
kast graph topology
kast graph communities
```

For an exact Kotlin boundary, resolve identity first:

```shell
kast symbol find io.github.amichne.kast.api.contract.backend.AnalysisBackend
kast symbol show <symbol>
```

Then follow the returned exact selector with `kast symbol callers`,
`kast symbol callees`, or `kast symbol implementations`. Preserve the returned
coverage and continuation evidence.

Kast repository intelligence currently proves Kotlin semantics. The Rust CLI,
shell installer, schemas, and workflows use the OKF `code_sources` above as
their deterministic route into source. If Kast returns `AMBIGUOUS`, `EMPTY`,
`QUALIFIED_EMPTY`, incomplete coverage, or a typed readiness blocker, report
that state and use the cited source owner. Do not fill the gap with an inferred
edge.
