# Kast Repository Intelligence Design

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

Repository intelligence extends those authorities through `kast rpc`. It does
not create a second graph, identity scheme, discovery index, or transport.

## Result invariants

Every answer carries one frozen graph generation, repository scope, coverage
eligibility, relation filters, traversal limits, ordering rule, truncation, and
continuation state. Every relationship carries a direct occurrence or a
deterministic derivation. Extracted, derived, inferred, and compiler evidence
remain separate closed values.

The default answer is the smallest evidence subgraph that satisfies the query.
Richer node metadata and complete occurrence sets are explicit bounded
projections.

## Frozen baseline

The benchmark owns two detached worktrees at commit
`2c630d3d156574eb4548fd97df3bd61fe9deb1a6`: one for compiler-backed Kast
indexing and one for Graphify output. Graphify 0.9.22 emitted an undirected
13,476-node graph after its code-only refresh; that limitation is recorded
rather than normalized away. Kast's baseline indexed all 599
compilation-owned Kotlin files and retained the permanent exact four-hop
`CALLS` path with source occurrences.
