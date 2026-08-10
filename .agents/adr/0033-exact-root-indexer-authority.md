# ADR 0033: Exact-root Kast indexer authority

Status: Accepted

Date: 2026-08-01

## Reason this record remains

The indexer uses IntelliJ and Kotlin libraries, but an editor process is not a
Kast authority. This boundary spans routing, packaging, setup, and lifecycle
code.

## Decision

Semantic demand is the only lifecycle authority. There is no public start,
stop, restart, ensure, repair, or lease transition. The sole lifecycle command,
`kastctl developer inspect lifecycle`, observes an exact root and reports
`Absent`, `Epoch`, or `Blocked`; inspection cannot advance state.

Each admitted workspace and runtime is an immutable epoch. The allowed graph is:

```text
Demand<C> -> WorkspaceAdmitted -> OwnershipObserved
Absent/ProvenDead -> LaunchPermit -> StartingEpoch -> RuntimeAvailable
ExactOwned -> RevalidatedEpoch -> RuntimeAvailable
RuntimeAvailable -> ModelReady -> SourceReady<N>
SourceReady<N> -> ReferenceReady<N>
SourceReady<N> -> GraphReady<N>
```

Every node may terminate in a closed typed blocker. Automatic recovery removes
only proven-dead owned evidence and attempts one replacement epoch. Conflict,
ambiguity, unsupported roots, identity movement, and failed replacement are
terminal for that demand.

Source, reference, and graph lanes advance independently within the same
workspace epoch. Source revision N remains usable if graph revision N is
blocked. Graph operations fail closed until graph N commits and never expose
graph N-1 as current.

Authenticated requests and server-held continuations own internal capability
leases. When the registry becomes empty, a fixed five-minute grace begins.
Only the matching still-empty registry may issue a one-shot stop permit, and
shutdown revalidates process, registration, descriptor, and socket identity.
Demand during grace supersedes the permit. Later demand creates a new epoch;
the graph never cycles an old epoch.

Kast reuses an eligible healthy exact-root indexer or creates an isolated
indexer with its own configuration, system, log, descriptor, socket, and VFS
paths. On macOS, a supported IntelliJ IDEA or Android Studio installation
supplies compatible runtime libraries. Its foreground process is not inspected
or controlled.

The internal indexer payload stays inside the release and is never installed
into a foreground application. Releases contain no public IntelliJ extension,
update feed, signing task, verification task, archive, or publication job.

Runtime availability, model readiness, source readiness, reference readiness,
and graph readiness are separate proof-carrying facts. No aggregate Boolean or
call order may stand in for one of these states.

## Source and proof

- `cli-rs/src/execution/runtime/backend/workspace_admission.rs`
- `indexer/`
- `analysis-server/src/main/kotlin/io/github/amichne/kast/server/`
- `index-store/src/main/kotlin/io/github/amichne/kast/indexstore/store/`
- `scripts/smoke-macos-indexer.sh`

Any new semantic process, editor lifecycle edge, endpoint owner, or source
index writer must replace this decision explicitly.
