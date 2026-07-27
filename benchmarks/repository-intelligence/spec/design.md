# Repository Intelligence Benchmark Design

## Category

Kast is a compiler-grounded repository intelligence system for Kotlin-first
codebases. Approximate discovery may select candidates; semantic execution
always uses exact identities, typed directional relations, bounded operations,
and visible evidence.

## Existing authority to compose

- `agent workspace-files` already owns Gradle-aware Kotlin inventory and typed
  coverage limitations.
- `symbol/query` already owns deterministic lexical and structural discovery,
  hard filters, rankings, and match reasons.
- compiler symbol and relationship operations already own exact anchors,
  overload ambiguity, bounded traversal, and source occurrences.
- `raw/semantic-graph` already persists canonical symbols, typed relation
  occurrences, declaration metadata, diagnostics, and source ranges in SQLite.
- `agent graph` already owns generation-pinned topology, SCCs, components, and
  deterministic weighted communities.
- TOON and JSON projections already share Rust result values.

Repository intelligence extends those authorities through the
`repository/query` operation exposed by `kast agent repository`. It does not
create a second graph, identity scheme, discovery index, or transport.

## Result invariants

Every answer carries one frozen graph generation, repository scope, coverage
eligibility, relation filters, traversal limits, ordering rule, truncation, and
continuation state. Every relationship carries a direct occurrence or a
deterministic derivation. Extracted, derived, inferred, and compiler evidence
remain separate closed values.

The default answer is the smallest evidence subgraph that satisfies the query.
Richer node metadata and complete occurrence sets are explicit bounded
projections.

## Kotlin coverage contract

`graph/coverage` joins the existing Gradle-aware `WorkspaceIndexSnapshot` to
the generation-pinned `semantic_files` table. Each expected Kotlin file has
exactly one closed state: `INDEXED`, `EXCLUDED`, `FAILED`, or `STALE`.
Generated sources under Gradle's `build/generated-sources` tree are explicit
`GENERATED_SOURCE` exclusions; missing or non-authoritative semantic rows are
structured failures; content-hash divergence is stale. Complete negative
answers are permitted only when inventory eligibility, module progress,
pending updates, and every eligible file are complete at the same generation.

The frozen snapshot accounts for 1,131 Gradle-associated Kotlin files. Its
599 eligible sources are indexed, and 532 Kotlin-DSL generated accessors are
explicitly excluded. No eligible file is silently omitted.

## Proof-carrying graph projection

`repository/query` projects the existing semantic symbol, type, and edge
occurrence tables without introducing another graph. Resolved nodes retain
canonical keys, signatures, owner and type identities, source ranges, flags,
annotations, module, source set, and evidence class. Typed directional edges
retain compiler occurrence locations and counts. Calls found in a local
declaration are projected to their callable owner only with the explicit
`LIFT_LOCAL_CALL_TO_CALLABLE_OWNER` derivation and its supporting `CONTAINS`
and `CALLS` facts.

Every graph response names the canonical workspace root and one graph
generation. Path planning is deterministic: an exact direct route wins unless
question terms identify a more relevant bounded route, such as the permanent
scope-fingerprint hashing chain. Occurrence samples are ordered by persisted
occurrence id. A truncated edge returns an identity-bound
`evidenceContinuation`; submitting it to the same request pages the remaining
occurrences without replaying or skipping evidence.

## Natural-language discovery

Resolve requests rank the existing compiler symbol rows rather than creating a
second discovery authority. Deterministic lexical tokens cover declaration
names, owner and qualified names, signatures, parameter, receiver, and return
types, annotations, declaration kinds, source paths, Gradle and source-set
scope, and compiler-neighbor names. A small closed vocabulary maps repository
language such as relationship, endpoint, hash, and persist to the compiler
model's terms.

Candidates retain their exact canonical identities and expose stable ranks,
scores, and field-specific match reasons. A supplied `canonicalKey` bypasses
lexical discovery. Bare overloaded names and tied best candidates produce
bounded `AMBIGUOUS` results without selecting an identity. No embeddings,
dependency, or additional persisted index are involved.

In the admitted final capture, all six discovery targets rank first. Both
deliberate ambiguity questions remain ambiguous, while every exact-key
behavior remains unchanged.

## Relation-specific architecture

Architecture requests select one of six closed directed projections:
`RUNTIME_CALLS` uses `CALLS`; `SYMBOL_REFERENCES` uses `REFERENCES`;
`TYPE_DEPENDENCIES` uses field, generic-argument, parameter-type, and
return-type references; `INTERFACE_IMPLEMENTATION` uses case, implementation,
inheritance, override, and sealed-member relations; `MODULE_DEPENDENCIES`
retains cross-module call, reference, and type-relationship edges; and
`CONTAINMENT_OWNERSHIP` uses `CONTAINS` and `METHOD`.

The projections reuse Kast's existing deterministic Tarjan and weighted Leiden
implementations. Findings apply directed fan-in or fan-out, exact-symbol or
boundary SCCs, cross-module counts, community cohesion, cross-community bridge
counts, and public-API consumer boundaries without merging relation types.
Readable names are derived from deterministic membership and do not participate
in the metric.

Every finding names its projection, metric, direction, trigger rule, graph
generation, relation composition, exact representative symbols, and derived
evidence class. Supporting subgraphs retain compiler occurrences or explicit
derivations. Cross-boundary SCC findings return one deterministic directed
cycle, so the bounded evidence subgraph proves the reported connection.

## Repository context

Context relationships are a read-only projection over repository files and the
existing semantic symbol index. Sources are scanned in the closed order
Markdown and ADRs, Gradle scripts, JSON schemas, workflows, and Rust. No second
graph, crawler, embedding store, or persistent context index is introduced.

Every link points from an exact repository path and source location to one
canonical Kotlin identity. Literal names and paths are `extracted`; module,
protocol, workflow, and shared-schema links carry named `derived` rules.
Compiler, extracted, derived, and inferred evidence remain visibly distinct.
The response also retains unresolved and ambiguous references, source-type
counts, evidence distribution, exact-link and orphan rates, and deterministic
documentation-gap findings.

If a context question names no symbol, the existing semantic discovery ranker
supplies at most 200 declaration-model candidates. Exact source paths and path
prefixes outrank incidental prose mentions, and only linked identities enter
the bounded result. This measured on-demand scan is intentionally simpler than
a new ingestion authority; a persistent context index is warranted only if the
200-candidate ceiling or observed latency becomes inadequate.

## Canonical result projections

`repository/query` produces one canonical JSON value marked
`canonicalResultModel`. Explicit JSON preserves the stable integration and
benchmark contract. Captured or agent execution projects the same value through
the existing TOON encoder, while human execution renders a bounded Markdown
answer with exact source references and the complete reproducible query
descriptor.

The benchmark runner retains those canonical RPC results as structured JSON and
derives one aggregate Markdown architecture and repository-context report from
them. Presentation never reruns discovery, traversal, architecture metrics, or
context resolution. No second semantic result hierarchy, export graph, HTML
surface, or compatibility database is introduced.

## Frozen baseline

The benchmark owns two detached worktrees at commit
`2c630d3d156574eb4548fd97df3bd61fe9deb1a6`: one for compiler-backed Kast
indexing and one for Graphify output. Graphify 0.9.22 emitted an undirected
12,936-node, 26,314-edge graph for the admitted capture; that limitation is
recorded rather than normalized away. Kast's baseline indexed all 599
compilation-owned Kotlin files and retained the permanent exact four-hop
`CALLS` path with source occurrences.
