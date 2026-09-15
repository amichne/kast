# Reference surface: first API-only iteration

## Target invariant

Every callable returning a semantic entity returns a reference, and every supported follow-up accepts that same reference unchanged. An agent must not decode a handle, reconstruct a selector, repeat discovery, or receive a full semantic description simply to continue.

The target public families are `CandidateRef`, `ExactSymbolRef` and `ContinuationRef`. The target common symbol projection is `SymbolSummary { ref, name, kind, location }`. These are API representations, not a replacement for the existing IDE and compiler authority.

## Implemented in this slice

`query_symbols.source.symbol_refs` uses the named `ExactSymbolRef` schema. Its optional `continuation` uses the named `ContinuationRef` schema, retaining the existing nullable start control. Generated Kotlin syntax has distinct value types; both still serialize as the original strings. Lowering unwraps each token once and forwards it to the same canonical owner without parsing, refreshing or reissuing it.

`search_classes`, `search_functions` and `search_declarations` request names and locations by default. Exact reference, kind and completeness evidence remain mandatory. The existing output shape is retained: unrequested `signature` is null rather than removed. No compiler-work or latency reduction is claimed by this projection change.

Retrieve a signature by starting a new reference-only query:

```json
{
  "source": {
    "type": "symbol_refs",
    "symbol_refs": ["<copy the returned symbol_ref verbatim>"]
  },
  "steps": null,
  "return_fields": ["signature"],
  "continuation": null
}
```

The placeholder is not an issued reference. Copy `symbol_ref`, which currently equals `ref.token`, from an actual result. This query does not rediscover the declaration or introduce an inspect step. It returns the requested signature in the existing result shape; names and locations need not be repeated. Do not attach an earlier search continuation to this new detail query: continuation ownership includes the original source, steps and projection.

## Preserved boundaries

Input JSON field names and accepted schema shapes are unchanged after resolving local schema references. Value types express the requested reference role, not proof of a valid capability. Wrong-family, malformed, stale, foreign and non-issued references still reach the canonical authority's existing rejection behavior; surface type construction does not establish semantic validity.

Reference equality is capability equality, not universal declaration equality. Existing handles retain scope and snapshot, so the same declaration can have different references under different admitted scopes. This slice does not change the canonical identity used by explicit `distinct_symbols`.

Existing compact hosted handles, source-anchor admission, relation and traversal inputs, budgets, qualification, continuation ownership, effects, approvals and tool loading remain unchanged. This slice does not introduce a reference store, new callable or alias, change compiler behavior, or alter installation or IDE lifecycle.

## Remaining iterations

1. Wire `CandidateRef` into candidate-producing and candidate-consuming public surfaces. Normalize every exact consumer around a single public `ref` field, including source-region addressing, while preserving compatibility deliberately.
2. Introduce the shared `SymbolSummary` output together with its installed schemas and consumers. Move `symbol_id`, canonical selector details and duplicated `ref.token`/`symbol_ref` representations out of the default projection; retain explicit detailed/debug evidence. This slice intentionally does not claim that output migration is complete.
3. Normalize query, relation and traversal collections around that summary. Deduplicate symbol descriptions by reference without collapsing distinct relation occurrences, changing explicit query step order or losing coverage/progress evidence.
4. Make full compiler evidence progressively retrievable, then audit the eager catalog. Rename or consolidate tools only where callable intent, rather than implementation mechanics, justifies the change.

## Acceptance and verification

For this slice, test opaque string serialization and round-trip admission; distinct exact-reference and continuation inputs; name/location defaults for all three searches; explicit signature selection from an exact reference without discovery or refinement; unchanged source-anchor and relation/traversal token reuse; and retention of existing budget, empty-projection and invalid-authority behavior.

The end-to-end target remains broader: start with one actually issued symbol and exercise every supported follow-up by copying one short typed reference, with no reconstructed identity and no repeated semantic summary. Deterministic surface fixtures do not prove live issuance, stale-reference handling in a running IDE, token savings or latency improvements.

Focused repository checks:

```bash
python3 packaging/generate-public-query.py --check
./gradlew :app-server:test --tests '*PublicReferenceSurfaceTest' --tests '*PublicToolContractTest' --tests '*PublicToolSchemaTest' --tests '*PublicExecutionBudgetTest'
./gradlew verifyJsonContracts knowledgeImpact verifyKnowledgeBase
```

Run the normal repository CI gates before marking the iteration ready for merge.
