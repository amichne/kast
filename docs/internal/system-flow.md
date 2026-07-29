---
type: Runtime Flow
title: How Kast works
description: End-to-end map from Kast's public CLI through runtime admission, compiler analysis, storage, and proof-carrying output.
resource: file://docs/internal/system-flow.md
tags: [architecture, cli, runtime, okf]
code_sources:
  - path: cli-rs/src/interface/cli/root.rs
    symbols: [Cli, Command]
  - path: cli-rs/src/interface/entrypoint/dispatch.rs
    symbols: [run, run_agent, run_runtime]
  - path: cli-rs/src/operations/install/bundle_entrypoint.rs
  - path: cli-rs/src/execution/runtime/backend/workspace.rs
    symbols: [workspace_ensure, workspace_status, workspace_stop]
  - path: cli-rs/src/agent/core/dispatch/commands.rs
    symbols: [run, execute]
  - path: cli-rs/src/agent/core/request.rs
    symbols: [execute_request]
  - path: cli-rs/src/interface/codex/hook/runtime.rs
    symbols: [session_start, post_tool_use]
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
  - path: backend-headless/src/main/kotlin/io/github/amichne/kast/headless/runtime/HeadlessRuntime.kt
    symbols: [HeadlessRuntime]
  - path: index-store/src/main/kotlin/io/github/amichne/kast/indexstore/store/SqliteSourceIndexStore.kt
    symbols: [SqliteSourceIndexStore]
  - path: .agents/adr/0025-backend-bound-opaque-selector-handles.md
  - path: .agents/adr/0026-proof-carrying-relationship-coverage.md
  - path: .agents/adr/0027-effective-agent-environment-readiness.md
  - path: .agents/adr/0028-exact-root-agent-workspace-leases.md
  - path: .agents/adr/0031-cli-install-and-data-authority.md
  - path: .agents/adr/0032-macos-idea-golden-pathway.md
---

# How Kast works

Kast has one public API: the `kast` command-line interface (CLI). The shell
installer and Codex hooks adapt external events into CLI commands. IntelliJ
IDEA and the headless host implement the compiler boundary. None of these
adapters defines a second public contract.

This page is an Open Knowledge Format (OKF) `Runtime Flow` concept. Its
`code_sources` point to the current owners of each claim. Use the map to start
an answer, then use Kast to prove Kotlin identities and relationships.

## Public API coverage

The visible `kast --help` surface contains these command families.

| Public family | Commands | Boundary |
| --- | --- | --- |
| Orientation | `kast help`, `kast version`, `kast context` | Read command or release identity and return in-process output. |
| Workspace configuration | `kast config` | List, set, or unset fields in the effective workspace configuration. |
| Installation | `kast setup` | Validate one complete bundle and switch the active release transactionally. |
| Readiness and lifecycle | `kast ready`, `kast start`, `kast status`, `kast stop` | Resolve one exact workspace and inspect, reuse, start, or stop its selected backend. |
| Semantic entry points | `kast demo`, `kast rpc` | Run the guided story or send one typed machine request through the canonical route. |
| Developer operations | `kast developer` | Inspect, validate, package, generate, or control development runtimes. |
| Agent operations | `kast agent` | Run pipe-friendly discovery, navigation, diagnostics, graph, and mutation commands. |

The hidden `kast doctor` command is a compatibility alias for `kast ready`.
It supports `kast-action` and is not a second public family. `install.sh` is
also a bootstrap adapter: it downloads or opens a bundle, then delegates the
persistent change to `kast setup`.

The `kast agent` family contains every agent-facing operation:

- Workspace control: `kast agent lease`, `kast agent verify`, and
  `kast agent workspace-files`.
- Repository evidence: `kast agent graph`, `kast agent repository`, and
  `kast agent impact`.
- Compiler navigation: `kast agent symbol`, `kast agent references`,
  `kast agent callers`, `kast agent callees`, `kast agent implementations`,
  `kast agent hierarchy`, and `kast agent diagnostics`.
- Compiler mutations: `kast agent rename`, `kast agent add-file`,
  `kast agent add-declaration`, `kast agent add-implementation`,
  `kast agent add-statement`, and `kast agent replace-declaration`.

## End-to-end system flow

Every public command starts in the same parser and typed dispatcher. The
dispatcher chooses a local control path, a local source-index path, or an
exact-root compiler path.

<kast-view view-id="system-landscape" browser="true"></kast-view>

Select Kast in the diagram to drill into its implicit component view, or use
the explicit runtime map:

<kast-view view-id="runtime-components" browser="true"></kast-view>

### Local control flow

`kast help`, `kast version`, and `kast context` return directly after command
dispatch. `kast config` resolves the canonical workspace, merges configuration
sources, and returns typed effective state.

`kast setup` is the only persistent installation operation. It validates an
untrusted bundle, stages a complete release, atomically switches `current`,
verifies the new CLI, and restores the prior verified release on failure.

`kast developer` dispatches to existing runtime, inspection, release, and
Codex subcommands. It does not create another runtime or protocol boundary.

### Runtime and readiness flow

`kast ready`, `kast start`, `kast status`, and `kast stop` resolve the exact
workspace before selecting a backend. Runtime inspection reads descriptors,
checks process reachability, validates compatibility, and rejects ambiguous
hosts. Start reuses a servable exact-root runtime before it starts another
one.

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
parameters. `SkillRpcOrchestrator` handles public agent workflows.
`AnalysisBackend` defines the shared Kotlin boundary.

`KastPluginBackend` implements that boundary with IntelliJ PSI and the Kotlin
compiler. The headless host starts the same IDEA backend runtime with a
different host bootstrap. Both return the same result contracts.

<kast-view view-id="compiler-read" browser="true" dynamic-variant="sequence"></kast-view>

### Repository and graph flow

`kast agent workspace-files`, `kast agent impact`, `kast agent graph`, and
`kast agent repository` combine typed filesystem, Gradle model, compiler, and
SQLite evidence. Some requests can finish in the Rust CLI from persisted
state. Compiler refresh and relationship requests route through the backend.

`SqliteSourceIndexStore` owns indexed files, declarations, references,
generations, snapshots, and semantic graph facts. A graph answer is usable
only when its generation and coverage match the requested scope.

<kast-view view-id="compiler-evidence" browser="true"></kast-view>

### Mutation flow

Mutations require an exact declaration identity or opaque selector handle.
The CLI validates exact-root mutation admission before it opens a reusable RPC
session. The server checks capabilities, plans the semantic operation, applies
it in the backend, and returns terminal validation evidence.

The public result preserves typed failure. A failed apply, stale identity,
incomplete analysis, or missing capability does not become success through
projection.

<kast-view view-id="semantic-mutation" browser="true" dynamic-variant="sequence"></kast-view>

### Codex hook flow

The Codex `SessionStart` hook invokes `kast developer runtime up` for the exact
workspace and accepts `INDEXING` as bootstrap reachability. The `PostToolUse`
hook checks runtime health and invokes `kast agent diagnostics` only for
successful Kotlin writes. Hook failures add advisory context; they do not run
setup or claim compiler proof.

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

Start by verifying the exact workspace:

```shell
kast agent verify --workspace-root "$PWD"
```

For a broad Kotlin architecture question, use persisted compiler evidence:

```shell
kast agent repository \
  --workspace-root "$PWD" \
  --question "How does this compiler-backed path work?" \
  --intent architecture \
  --projection runtime-calls \
  --metric bridges \
  --explain
```

For an exact Kotlin boundary, resolve identity first:

```shell
kast agent symbol \
  --workspace-root "$PWD" \
  --query io.github.amichne.kast.api.contract.backend.AnalysisBackend \
  --kind interface \
  --explain
```

Then follow the returned exact selector with `kast agent callers`,
`kast agent callees`, or `kast agent implementations`. Preserve the returned
coverage and continuation evidence.

Kast repository intelligence currently proves Kotlin semantics. The Rust CLI,
shell installer, schemas, and workflows use the OKF `code_sources` above as
their deterministic route into source. If Kast returns `AMBIGUOUS`, `EMPTY`,
`QUALIFIED_EMPTY`, incomplete coverage, or a typed readiness blocker, report
that state and use the cited source owner. Do not fill the gap with an inferred
edge.
