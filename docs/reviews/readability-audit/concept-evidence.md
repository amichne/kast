# Explanation and reference audit

The review examined 44 pages at `dac99b39a0b7b497d5c0e0d5c2a604f5817e5397`.
The JSON inventory records each page's reader, purpose, category, ownership, and selected problem.
This agent review simulates a reader review. It does not prove human comprehension.

## Material changes

- `explanation/value-journey.mdx` now includes the exact displayed Kotlin example before the explorer. The prose explains the customer, draft, and list separately.
- `explanation/value-journey-evidence.mdx` separates conditional library correspondence from observed execution. It links exact result classifications.
- `reference/program-tracing.mdx` introduces the source-only limit before the example. The example now defines formal parameters and maps each owner to code.
- `reference/callback-observations.mdx` defines the named edge before its policy table and links the concrete example.
- `reference/responses.mdx` provides a reading order and contrasts missing presentation rows with unfinished proof. Illustrative counts remain labeled.
- `how-to/complete-engineering-task.mdx` ends the ordinary task after source and project-test evidence. Its benchmark protocol moved to contributor documentation.

The first editorial batch changed no generated page or API contract.
The later orientation batch changed four generated page shells through their manifest and projector. Canonical snippets and API contracts remain unchanged.

## Current source evidence

| Claim | Source and lines | Evidence limit |
| --- | --- | --- |
| The fixed completion model describes compiler-resolved source relationships. | `protocol/contract/src/main/kotlin/io/github/amichne/kast/protocol/contract/QueryCompletionDocument.kt:15-39` | Inspected contract. It makes no runtime reachability claim. |
| Source TRACE keeps callback execution gaps as evidence. | `query/protocol/src/main/kotlin/io/github/amichne/kast/query/protocol/QueryCallbackGraphAdmission.kt:78-104` | Inspected branch between source and invocation-graph proof. |
| Source TRACE and explicit invocation relations have different proof rules. | `query/protocol/src/test/kotlin/io/github/amichne/kast/query/protocol/TraceSourceRelationshipCompletionTest.kt:25-174` | Inspected success and rejection assertions. This suite did not run in this audit. |
| Incomplete execution can keep original evidence without completion. | `query/protocol/src/test/kotlin/io/github/amichne/kast/query/protocol/CompleteOnlyQueryTest.kt:37-81` | Inspected retained-rejection assertions. This suite did not run in this audit. |
| A callback formal means a compiler-mapped callable and parameter position. | `relation/contract/src/main/kotlin/io/github/amichne/kast/relation/contract/CallbackArgumentBinding.kt:7-78` | Inspected constructor admission and negative outcomes. |
| Exact, derived, and unestablished correspondence remain distinct. | `query/contract/src/main/kotlin/io/github/amichne/kast/query/contract/QueryImpactClassification.kt:24-75` | Inspected exhaustive classification. Reviewed library rules differ from caller-supplied models. |
| The static example matches the explorer's displayed source. | `docs/tooling/value-journey/src/trace.test.ts:9-23` | Compared the full example text with the authored expected display. |

## Executed checks

- **Passed:** `mise exec -- kotlinc /tmp/kast-concept-audit/ValueJourney.kt -include-runtime -d /tmp/kast-concept-audit/value-journey.jar`.
- **Passed:** `mise exec -- java -jar /tmp/kast-concept-audit/value-journey.jar`. The fixture asserts one welcome-email result and an empty result for empty input.
- **Passed:** Root Vale over the six changed pages, with zero errors. Two grade advisories remain in `value-journey-evidence.mdx` and `responses.mdx`.
- **Passed:** `git diff --check` after this batch.
- **Passed:** Live Kast discovery of `QueryCompletionDocument` in `protocol/contract/src/main`, source set `main`. The response reported complete coverage, one exact declaration, and no failures or omissions.

The Kotlin example execution proves ordinary Kotlin behavior. It does not prove a Kast value-flow query.
The live query proves declaration discovery for this workspace. It does not exercise callback or value-flow rejection cases.

## Advisory decisions

`Slopsentral.Grade` reports 8.26 for `value-journey-evidence.mdx` and 8.09 for `responses.mdx` at the examined batch.
Both pages need precise names for evidence authority and encoded results. Their paragraphs separate each concept, and their links expose the simpler introduction.
The report keeps these warnings. No vocabulary exception or threshold changed.

## Remaining limits

The generated conceptual pages use OpenWiki content and preserve their recorded evidence qualifications.
The audit did not establish fresh evidence for every Claim in those sources. Their implementation detail remains material for advanced readers.
The authored introduction now supplies the novice conceptual route. No blanket freshness claim applies to the generated material.

Two imported statements needed explicit public-contract orientation:

- `snippets/okf/queries/execution-and-composition.mdx:57` says that TRACE is the last step. Public contract 17 selects TRACE through `terminalStep`.
- `snippets/okf/contracts/source-identity.mdx:99,156` describes older hosted source forms and fresh source reacquisition. Current public `READ_SOURCE` keeps original snapshot checks.

Those snippets preserve provenance notices. Their page introductions now distinguish internal stages and source APIs from current `query_symbols` syntax.
The introductions link the current public action and result references. They do not claim that the imported Claims received new qualification.

## Projected introductions

`docs/okf-public.json` now owns optional reader introductions for query lifecycle, composition, symbol identity, and evidence authority.
`docs/tooling/okf_public.py` rejects an explicit empty, whitespace-only, or null introduction.
It rejects values other than strings and unknown fields.
An absent introduction preserves the old page shape.
The projector puts admitted introductions before anchors, diagrams, and shared concept content.

The focused test first failed because the parser rejected the new introduction field. Eight negative controls passed.
After implementation, all nine focused cases passed. The full projector suite passed with 33 cases.
The complete documentation tooling suite passed with 378 cases.
Strict Pyright and Ruff passed. `git diff --check` passed.

Root Vale found no errors in the four shells. It reported one grade advisory and two missing-example suggestions.
The evidence-authority introduction keeps precise contract terms. The composition and symbol pages link executable request examples rather than duplicate them.
The findings remain visible in `introduction-vale-root.json`.

## Later repository gate

The later full product gate passed on the final documentation tree.
Its `:query:protocol:test` task ran five `CompleteOnlyQueryTest` cases and seven `TraceSourceRelationshipCompletionTest` cases, with no failures or skips.
The earlier source-inspection limits describe the page-edit phase.
Semantic fixture suites remained disabled in the full gate.
