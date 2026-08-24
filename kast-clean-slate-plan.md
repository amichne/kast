# Kast clean-slate architecture and delivery plan

Status: normative durable-topology revision for the PR #633 program.

This document defines the target product architecture, public surface, internal boundaries, and
delivery order. It is deliberately head-independent. The exact merge head is admitted only by the
GATE-001 through GATE-070 evidence chain.

## Product boundary

Kast is a compiler-grounded semantic control plane for Kotlin and Gradle repositories. It has two
runtime processes:

1. `kast`, the Kotlin CLI and transport boundary.
2. `kast-indexer`, one isolated IntelliJ/K2 process for one canonical repository root.

There is one compiler authority, one published workspace generation, one canonical outcome
algebra, and one public operation registry. Persistence stores detached evidence and never creates
semantic truth. A read cannot trigger Gradle import, topology construction, persistence writes, or
source mutation.

## Public surface

The public surface is exactly twelve operations, in canonical order:

<!-- canonical-operations:start -->
```text
workspace.inspect
topology.build
symbol.discover
symbol.resolve
symbol.describe
relation.read
traversal.run
diagnostic.check
change.plan
change.apply
change.verify
change.recover
```
<!-- canonical-operations:end -->

Their CLI projections are:

```text
kast workspace inspect
kast topology build
kast symbol discover
kast symbol resolve
kast symbol describe
kast relation read
kast traversal run
kast diagnostic check
kast change plan
kast change apply
kast change verify
kast change recover
```

No generic topology query, path, cycle, SCC, condensation, or quotient operation is part of this
revision. A future graph read requires a separate bounded contract and pull request.

`relation.read` is a live, one-hop K2 operation. `traversal.run` is bounded composition over an
eligible durable topology snapshot. Missing or stale topology returns `TOPOLOGY_BUILD_REQUIRED`.
Reads never build topology implicitly.

## Proof and capability laws

- Parse and admit boundary primitives once, then pass the stronger type.
- Expected failure is a closed value.
- A service can perform an effect only when it receives that exact capability.
- `Qualified` can never be interpreted as `Complete`.
- Live IntelliJ, K2, Gradle, JDBC, VFS, and PSI values stay inside their adapters.
- `change.apply` produces `AppliedUnverified`; only `change.verify` can produce a verified receipt.
- Unknown, incomplete, corrupt, or stale evidence fails closed where the operation requires it.

## Target Gradle topology

```text
:kernel
:distribution:{contract,managed}
:protocol:{contract,registry,wire}
:workspace:{contract,service,intellij}
:symbol:{contract,service,intellij}
:relation:{contract,service,intellij}
:traversal:{contract,service}
:topology:{contract,build,service,intellij}
:diagnostic:{contract,service,intellij}
:change:{contract,plan,apply,verify,recovery,intellij}
:evidence:{contract,sqlite}
:runtime:{server,composition}
:cli
:indexer
```

Contract modules own immutable values, closed outcomes, and narrow capabilities. Service modules
own pure workflow. IntelliJ modules own compiler and IDE effects. `:evidence:sqlite` alone owns
physical persistence. `:runtime:composition` alone assembles the complete implementation graph.
The CLI owns parsing, exact-root runtime admission, transport, and projection; it owns no semantic
implementation.

## Durable topology boundary

Topology construction is explicit:

```text
PublishedWorkspace
-> topology.build
-> admitted source-root candidates
-> detached file-local K2 facts
-> CompleteTopologyGeneration
-> atomic SQLite publication
-> eligible generation-bound snapshot
```

Only complete candidate coverage may produce `CompleteTopologyGeneration`. Publication accepts
only that type. Failed extraction, workspace movement, storage conflict, incomplete rows, or
corrupt rows cannot advance the published snapshot.

Snapshot reads are separate:

```text
eligible snapshot
-> snapshot content re-admission
-> SQLite-backed one-hop reader
-> bounded traversal
```

`:topology:contract` contains snapshot identities, facts, build states, persistence ports, and
content-read ports. It contains no zero-budget graph algorithm. Existing graph indexing, Tarjan,
condensation, quotient, reachability, and cycle code remains internal to `:topology:service`.

## Topology prerequisite states

The system preserves these distinct states:

| Condition | `traversal.run` | required traversal in `change.plan` |
| --- | --- | --- |
| Workspace not ready | `WORKSPACE_NOT_READY` | `WORKSPACE_NOT_READY` |
| Selector belongs to an older lease | `SELECTOR_STALE` | `SYMBOL_RESOLVE_REQUIRED` |
| Snapshot absent or rejected | `TOPOLOGY_BUILD_REQUIRED` | `TOPOLOGY_BUILD_REQUIRED` |
| Snapshot belongs to an older workspace identity | `TOPOLOGY_BUILD_REQUIRED` | `TOPOLOGY_BUILD_REQUIRED` |
| Any traversal bound is reached | `Qualified` | `REQUIRED_TRAVERSAL_INCOMPLETE` |
| Public request cannot form a valid plan | `PLAN_REJECTED` | never a topology prerequisite |

The pure `:change:plan` service receives only admitted detached evidence. The public installed
admission in `:runtime:composition` may use narrow workspace, symbol, relation, traversal,
diagnostic, source-observation, and IntelliJ intent-refinement ports to convert a boundary request
into that evidence. Neither layer may receive or recover topology build, topology publication,
combined snapshot-store, or source-write authority.

## Delivery order

The machine-readable authority is [kast-clean-slate-task-graph.json](kast-clean-slate-task-graph.json).
Its durable-topology ordering is:

```text
KCS-007 Published workspace ----+--> KCS-023 Durable topology --+
KCS-010 Exact selectors --------+                              |
KCS-011 Live one-hop relations -------------------------------+--> KCS-012 Pure traversal
KCS-012 Pure traversal ----------------------------------------+--> KCS-015 Change planning
KCS-023 Durable topology --------------------------------------+--> KCS-021 Installed product
KCS-021 Installed product ------------------------------------------> KCS-022 Enterprise acceptance
```

The broad waves remain:

1. Architecture firewall.
2. Legacy product deletion.
3. Kernel, protocol, runtime server, and Kotlin CLI substrate.
4. Workspace, symbol, relation, durable topology, traversal, and diagnostics.
5. Planned, applied, recovered, and verified mutation.
6. Closed intent expansion and legacy aggregate deletion.
7. Installed and enterprise acceptance.

## Installed-system acceptance

One installed-product journey proves the state transition:

```text
workspace ready
-> traversal and change planning require topology.build
-> first topology build publishes G0/D0
-> second topology build reuses G0/D0
-> traversal observes firstCaller
-> stop and restart
-> traversal returns the same semantic result without rebuilding or K2 fallback
-> change plan/apply/verify adds secondCaller and publishes workspace G1
-> old selector returns selector-stale
-> fresh G1 selector returns topology-build-required
-> explicit rebuild publishes G1/D1
-> traversal observes firstCaller and secondCaller
```

Focused structural proof also requires:

- no topology build or publication reference in change planning or snapshot traversal;
- no IntelliJ, K2, Gradle, or filesystem fallback on the snapshot traversal route;
- incomplete extraction cannot publish;
- exact and stale SQLite candidates are fully re-admitted before use;
- corrupt, incomplete, dangling, or occurrence-mismatched rows reject; and
- the compiled topology-contract API exactly matches its checked manifest.

## Terminal state

The clean-slate target is admitted only when:

- the installed registry and schema contain exactly the twelve operations above;
- `CanonicalOperationDefinitions` is the sole production operation authority;
- registry JSON is encoded by `:protocol:wire` from the registry-owned typed projection and copied
  byte-for-byte into the installed product;
- workspace and topology publication remain distinct explicit transitions;
- topology publication accepts only complete generation-bound evidence;
- snapshot traversal survives restart and never calls K2;
- missing topology, stale topology, stale selector, and bounded incompleteness remain distinct;
- stable topology contract ABI contains no zero-budget graph algorithm;
- no follow-on topology read operation enters this revision;
- public documentation shows explicit topology construction before snapshot-backed planning;
- the full clean-checkout merge-candidate aggregate passes; and
- GATE-070 proves successful CI for the exact open PR head.
