# How Kast works

Kast places a narrow command boundary in front of an isolated IntelliJ and
Kotlin semantic runtime. The caller never needs to open, focus, or coordinate a
foreground IDE project.

## One request keeps one line of authority

The runtime flow below begins at the exact repository root and ends with one
typed outcome. The deeper module map stays available in this page without
interrupting that path.

<script type="module" src="../../architecture/likec4-views.mjs"></script>
<script src="../../javascripts/diagram.js"></script>
<kast-view view-id="runtime-flow"></kast-view>

`kast start` resolves a supported JetBrains installation for matched platform
libraries, then starts or reuses one indexer for the canonical root. The
semantic runtime is a digest-verified release artifact stored separately from
the small control command.

Before the indexer reports readiness, runtime composition proves that all
twelve canonical operations have one implementation. A request is then
admitted by operation identity, capability, effect, scope, cost, and
completeness policy before it can reach compiler or write adapters.

<details class="kast-architecture-details" markdown>
<summary>Explore module ownership</summary>

The module map shows where each proof is owned and keeps the dependency
direction toward host-neutral evidence visible.

<kast-view view-id="module-ownership"></kast-view>

</details>

## Compiler objects stay inside the adapter

IntelliJ PSI, K2 symbols, compiler sessions, and native search scopes remain in
the request-local adapter boundary. Kast projects their results into
host-neutral identities and evidence documents before they travel over the
local wire protocol.

This gives the caller stable facts without pretending compiler objects can be
serialized or reused after their workspace generation changes.

## Effects remain explicit

Most operations are host-neutral or bounded reads. Source writes exist only in
`change.apply` and `change.recover`, after a typed plan has been admitted. A
separate `change.verify` operation establishes the terminal semantic result.

Published workspace and recovery evidence use one SQLite adapter. Runtime
services depend on typed contracts, while the physical store, compiler, and
filesystem remain outside the pure workflow.

Repository topology is also explicit: `topology.build` is the only operation
that can turn complete K2 coverage into a SQLite snapshot. Multi-hop traversal
reads an eligible snapshot and cannot trigger compiler work as a fallback.

## Isolation is part of the result

One exact-root indexer means another checkout, foreground IDE window, or stale
endpoint cannot silently become the authority for this request. When root,
generation, capability, scope, or runtime identity cannot be proven, the
operation fails closed.

[Set up and start Kast](../start.md) follows this path from a clean host.
[Trust the evidence](../concepts/evidence-boundaries.md) explains how to read
the result at the other end.
