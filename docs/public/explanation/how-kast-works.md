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

<ol class="kast-request-flow" aria-label="One symbol request through Kast">
  <li class="kast-flow-step kast-tone-discovery">
    <span class="kast-flow-number">01</span>
    <strong class="kast-flow-title">CLI call</strong>
    <code>kast symbol describe</code>
    <span>Parses one public command at the repository root.</span>
  </li>
  <li class="kast-flow-step kast-tone-identity">
    <span class="kast-flow-number">02</span>
    <strong class="kast-flow-title">Kotlin executable</strong>
    <code>SymbolDescribeRequestDocument</code>
    <span>Refines arguments into a typed request document.</span>
  </li>
  <li class="kast-flow-step kast-tone-discovery">
    <span class="kast-flow-number">03</span>
    <strong class="kast-flow-title">Local wire RPC</strong>
    <code>Unix-domain socket</code>
    <span>Sends one bounded JSON frame to the exact-root indexer.</span>
  </li>
  <li class="kast-flow-step kast-tone-evidence">
    <span class="kast-flow-number">04</span>
    <strong class="kast-flow-title">K2 adapter</strong>
    <code>Request-local compiler state</code>
    <span>Resolves the symbol and projects host-neutral evidence.</span>
  </li>
</ol>

The result returns through the same local boundary as complete or qualified
evidence, or as a typed rejection. Compiler objects stay inside the adapter.

`kast start` resolves a supported JetBrains installation for matched platform
libraries, then starts or reuses one indexer for the canonical root. The
semantic runtime is a digest-verified release artifact stored separately from
the Kotlin control executable.

Before the indexer reports readiness, runtime composition proves that all
twelve canonical operations have one implementation. A request is then
admitted by operation identity, capability, effect, scope, cost, and
completeness policy before it can reach compiler or write adapters.

## Follow one symbol request

Assume that discovery and resolution already returned an exact selector. A
description request starts with the current CLI shape:

```console
kast symbol describe --selector '<exact-selector>'
```

The Kotlin control executable parses that command as `symbol.describe` and
constructs a `SymbolDescribeRequestDocument`. `UnixDomainWireClient` sends the
generated document as one bounded JSON frame over the exact root's Unix-domain
socket.

`RuntimeServer` accepts the local RPC request. `CanonicalProtocolAuthority`
admits the operation and routes it to the installed symbol service. The
`IntellijSymbolExactCompilerAdapter` resolves the exact selector with
request-local PSI and K2 state, then projects the compiler object into a
host-neutral `SymbolDescription`.

The same RPC returns one closed outcome. The control executable writes one JSON
document to standard output for complete or qualified evidence, or one JSON
diagnostic to standard error for rejection.

PSI and K2 objects never cross the wire.

<details class="kast-architecture-details" markdown>
<summary>Trace the implementation owners</summary>

The request path is owned by these Kotlin sources:

- [`KastCli`](https://github.com/amichne/kast/blob/main/cli/src/main/kotlin/io/github/amichne/kast/cli/KastCli.kt)
  parses the public command.
- [`SymbolDescribeRequestDocument`](https://github.com/amichne/kast/blob/main/protocol/wire/src/main/kotlin/io/github/amichne/kast/protocol/wire/serialization/CanonicalReadDocuments.kt)
  carries the typed wire request.
- [`UnixDomainWireClient`](https://github.com/amichne/kast/blob/main/cli/src/main/kotlin/io/github/amichne/kast/cli/UnixDomainWireClient.kt)
  owns the client side of the local RPC.
- [`RuntimeServer`](https://github.com/amichne/kast/blob/main/runtime/server/src/main/kotlin/io/github/amichne/kast/runtime/server/RuntimeServer.kt)
  owns the server side.
- [`CanonicalProtocolAuthority`](https://github.com/amichne/kast/blob/main/runtime/composition/src/main/kotlin/io/github/amichne/kast/runtime/composition/protocol/core/CanonicalProtocolAuthority.kt)
  admits and routes the operation.
- [`IntellijSymbolExactCompilerAdapter`](https://github.com/amichne/kast/blob/main/symbol/intellij/src/main/kotlin/io/github/amichne/kast/symbol/intellij/exact/IntellijSymbolExactCompilerAdapter.kt)
  confines the request-local K2 work.

</details>

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
