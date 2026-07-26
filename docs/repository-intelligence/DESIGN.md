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
names, qualified names, signatures, parameter, receiver, and return types,
annotations, module and source-set scope, compiler neighbors, the existing
trigram FTS table, and bounded declaration or KDoc text. A small closed
vocabulary maps repository language such as relationship, endpoint, hash, and
persist to the compiler model's terms.

Candidates retain their exact canonical identities and expose stable ranks,
scores, and field-specific match reasons. A supplied `canonicalKey` bypasses
lexical discovery. Bare overloaded names and tied best candidates produce
bounded `AMBIGUOUS` results without selecting an identity. No embeddings,
dependency, or additional persisted index are involved.

On the frozen Phase 3 corpus, all six discovery targets occur within the top
five at ranks 1, 1, 4, 5, 1, and 1. Both deliberate ambiguity questions remain
ambiguous, while every Phase 2 exact-key behavior remains unchanged.

## Frozen baseline

The benchmark owns two detached worktrees at commit
`2c630d3d156574eb4548fd97df3bd61fe9deb1a6`: one for compiler-backed Kast
indexing and one for Graphify output. Graphify 0.9.22 emitted an undirected
13,476-node graph after its code-only refresh; that limitation is recorded
rather than normalized away. Kast's baseline indexed all 599
compilation-owned Kotlin files and retained the permanent exact four-hop
`CALLS` path with source occurrences.
