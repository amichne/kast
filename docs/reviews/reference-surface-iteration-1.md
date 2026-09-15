# Query reference surface

## Implemented boundary

Query CLI/model result items and per-item failures emit the existing opaque token
as a scalar `ref`. Exact query items no longer repeat it as `symbol_ref` or project
`symbol_id`. Canonical domain and wire query documents retain identity and evidence.
Candidate/exact discriminators, ordering, requested projections, qualifications,
finite failures, and explicit deduplication remain unchanged.

Reusable installed output schemas name `CandidateRef`, `ExactSymbolRef`, and the
query-scoped `ContinuationRef`. These names do not issue authority. Server projection
12 rejects catalogs from projection 11; CLI invocation grammar remains 3.

## Reference reuse and equality

| Consumer | Request location |
| --- | --- |
| `query_symbols` | `source.symbol_refs[]`, source type `symbol_refs` |
| `read_relations` | `exactSelector` |
| `source_read` | `anchor.selector`, anchor type `symbol` |
| `change_plan` | `intent.exactTarget` |

Copy tokens verbatim. Candidate references still require explicit refinement for
exact-only operations. Syntax does not prove host, workspace, scope, or epoch admission.

The public API has no `symbol_id` or equality-key accessor. Canonical identity stays
internal; use explicit `distinct_symbols` for declaration deduplication. Opaque
reference equality is not declaration equality across scopes or epochs. The enum
acceptance regression now tests canonical deduplication of original and restored
references through this operation, alongside separate reference and signature checks.

## Scope

This implements the reference-projection slice from `kast-api-surface-draft.zip`
and migrates its native consumers, fixtures, and current documentation. Historical
receipts retain their recorded contracts. Shared minimal symbol summaries, direct
reference facade inputs, and relation/traversal collection normalization remain
separate API iterations.

## Verification boundaries

`QueryReferenceSurfaceTest` covers scalar references, candidate/exact separation,
legacy shape rejection, verbatim follow-up admission, finite failures, named
schemas, and preserved duplicate order. Its synthetic tokens prove representation
and admission behavior, not live IDE authority. Native regression source and fixtures
are migrated; running their unit checks does not establish live native acceptance.

Required checks are CLI projection/schema tests, affected provider and Python
regressions, `verifyJsonContracts`, `knowledgeImpact`, `verifyKnowledgeBase`, and
regeneration/verification of the Mintlify callable reference.
