# ADR 0026: Proof-carrying relationship coverage

Status: Accepted

Date: 2026-07-16

## Reason this record remains

A result count and proof that a semantic search was complete are different
claims. Conflating them can turn a bounded or degraded search into false
negative evidence.

## Decision

Every compiler-backed relationship result carries explicit search coverage in
addition to returned rows and cardinality. Counts are exact only when the
indexer proves that the required compiler and index work completed for the
anchored subject and scope.

Truncation, timeout, cancellation, provider degradation, stale continuation,
or incomplete index coverage remains visible. None may be projected as an
empty complete result.

Continuation tokens bind the original selector and query options. Resuming a
page does not refresh the selector, widen the scope, or upgrade coverage. The
CLI may omit redundant detail, but it must not synthesize completeness.

## Source and proof

- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/contract/result/`
- `analysis-server/src/main/kotlin/io/github/amichne/kast/server/skill/`
- `indexer/src/main/kotlin/`
- `cli-rs/src/agent/navigation/projection.rs`
- `cli-rs/tests/agent_relationship_navigation_smoke/main.rs`

Any change that weakens the distinction between returned data and proven
coverage must update this record.
