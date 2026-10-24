# Public corpus campaign results

The baseline ran 88 variants on each of four imported repositories with public contract 17 and IntelliJ DEBUG logs.
These results cover declared variants, not complete checklist cases or families.
The campaign kept each request, response, process receipt, source hash, and diagnostic join in private files.
This fragment excludes local paths, exact references, source excerpts, and raw logs.

## Repeat a declared tranche

Use the pinned corpus revision and an imported, indexed temporary copy.
Run this command from `experiments/host-observation` with its committed uv lock:

```sh
uv run --locked python reproduce_semantic_queries.py campaign \
  --repository spring-framework \
  --fixture /path/to/imported/spring-framework \
  --cli "$HOME/.local/share/kast/installation/bin/kast-mcp-complete" \
  --idea-log "$HOME/Library/Logs/JetBrains/IntelliJIdea2026.2/idea.log" \
  --output /tmp/kast-campaign-spring-001 \
  --tranche baseline \
  --max-calls 64 \
  --timeout 60
```

Choose a fresh output directory for each run.
Select `relations` or `sources` for the other tranches.
Use the matching repository name and root for Ktor, kotlinx.coroutines, or Kast.
The exact CLI names are `ktor`, `kotlinx.coroutines`, and `kast`.
The source hash and anchor must match the checked-in corpus witness.

The runner uses the grants described in this report and preserves them through output reads.
`--max-calls` limits each variant's drain, while `--timeout` limits each public call.
Exit code `2` preserves failed or blocked outcomes, including expected campaign gaps.
Only passed selected variants and unchanged final source admission permit exit code `0`.
Keep the requests, replies, process receipts, and diagnostic joins private.

## Baseline results

| Repository | Variants | Passed | Failed | Public calls | Matched joins | Unavailable joins |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| Spring Framework, original run | 88 | 86 | 2 | 89 | 73 | 16 |
| Ktor | 88 | 82 | 6 | 88 | 72 | 16 |
| kotlinx.coroutines | 88 | 87 | 1 | 89 | 73 | 16 |
| Kast | 88 | 88 | 0 | 89 | 85 | 4 |
| Total | 352 | 343 | 9 | 355 | 303 | 52 |

All four source witnesses kept their admitted hashes through each run.
The 52 unavailable joins belong to expected `READ_SOURCE` region rejections.
The campaign did not infer a diagnostic join from nearby timestamps.

The original Spring run contains one incorrect harness expectation for `rowIds: []`.
The public contract preserves qualification for omitted rows, so that query correctly rejected with `ROW_SELECTION_INCOMPLETE`.
A separate corrected trial passed fresh discovery, fresh retention, and the exact rejection oracle.
Its three calls matched diagnostics, and its other 85 variants remain `NOT_RUN`.
The original failed receipt remains unchanged.

Eight baseline failures concern semantic completion.
Spring `CALLEES` kept `build` but rejected `CALLBACK_GRAPH_UNPROVEN` with `EXTERNAL_CALLABLE`.
Ktor `CALLEES` kept `createRouteFromPath` but rejected `CALLBACK_GRAPH_UNPROVEN` with `OUTSIDE_DOMAIN`.
Ktor REFERENCES, CALLERS, TYPE_USES, and two REFERENCES scope controls rejected with `RELATION_INCOMPLETE`.
kotlinx.coroutines `CALLEES` kept `unsafeTransform` but rejected with `UNRESOLVED_ARGUMENT_MAPPING` and `NESTED_CALLBACK_EXECUTION`.
These failures remain distinct from harness failures and successful empty answers.

## Assertion strength

| Oracle | Baseline assertions passed | What a pass proves |
| --- | ---: | --- |
| `COMPLETE_COUNT` | 180 | Complete result with the declared row count |
| `COMPLETE_WITNESS` | 21 | Complete result with the named source witness |
| `COMPLETION_ONLY` | 27 | Complete response, without an independent enumeration oracle |
| `SOURCE_SLICE` | 60 | Returned source bytes match the original UTF-16 range |
| `EXACT_REJECTION` | 55 | Rejected response contains the declared finite reason |

Each exact selection uses an independent file, source hash, and UTF-16 anchor.
The source-slice oracle does not independently establish every structural region extent.
The seven relation probes use function seeds.
Their empty implementation and inheritor results do not cover positive class or interface hierarchies.
The corrected Spring trial adds one passed assertion in each of `COMPLETE_COUNT`, `COMPLETE_WITNESS`, and `EXACT_REJECTION`.

## Operations and modes

The baseline made 240 `RUN`, 112 `READ_SOURCE`, and three `READ_RESULT` calls.
It requested 239 `SYMBOLS` projections and four `BINDING_ROWS` projections across query calls.
The three retained reads drained workspace REFERENCES results for Spring, kotlinx.coroutines, and Kast.
No baseline call used `RESUME`.

Each repository exercised explicit `BOUNDED`, explicit `RUNNER_CONTROLLED`, omitted execution mode, and null execution mode.
The baseline contained 228 bounded RUN requests and four RUN requests for each other mode selection.
Bounded requests used 15,000 milliseconds, 2,000,000 work units, 128 rows, and 49,152 returned bytes.
Runner-controlled probes omitted the work-unit grant.
Scoped relation probes selected production sources and excluded generated sources.
Spring and Kast used `main`, while Ktor and kotlinx.coroutines used `commonMain`.

The baseline covered named discovery, exact references, location lookup, retained sources, field projections, filters, set operations, joins, and binding projections.
It also covered seven relations, three REFERENCES scopes, four retention selections, and all 28 region/text-mode combinations.
Only declared combinations ran.
This baseline does not complete B, C, E, or F checklist families.

## Relation extension

The separate relation tranche ran eight variants and two fresh bootstrap variants per repository.
It did not replace baseline receipts.

| Repository | Passed | Failed | Public calls | Matched joins |
| --- | ---: | ---: | ---: | ---: |
| Spring Framework | 6 | 4 | 10 | 10 |
| Ktor | 5 | 5 | 10 | 10 |
| kotlinx.coroutines | 5 | 5 | 10 | 10 |
| Kast | 10 | 0 | 10 | 10 |

Both WALK strategies ran with explicit depth one and a source domain.
The fan-out strategy used a limit of 1,000 edges per node.
All 12 empty-source controls passed across WALK and TRACE.
Kast passed the exact callee-occurrence oracle and the depth-one traversal oracle.
Those oracles examine the relation meaning, source, target, provenance, occurrence, and hop depth.
They do not substitute row counts for relation evidence.

The three external corpora kept their earlier callback qualifications across WALK and OCCURRENCES.
Ktor and kotlinx.coroutines TRACE also rejected with `RELATION_INCOMPLETE`.
Spring and Kast TRACE completed.
The tranche does not prove exhaustive traversal, cycles, duplicate paths, or fan-out truncation behavior.

The installed catalog defines ten transform variants.
Saved baseline and extension requests exercised all ten, including eight `PROJECT_BINDING` calls.
They also exercised both terminals and four output variants.
At this stage, `SEARCH_TEXT`, `ALL_DECLARATIONS`, `IMPACT`, `VALUE_PATHS`, `IMPACT_WITNESS`, and `RESUME` remain outside these two tranches.
Other campaign owners record their flows separately.

## Source extension

The source tranche ran four variants and two fresh bootstrap variants per repository.
It used the selected declaration name as the indexed word and an absent word as the negative control.
The selected directories excluded subdirectories.

| Repository | Passed | Failed | Public calls | Matched joins |
| --- | ---: | ---: | ---: | ---: |
| Spring Framework | 6 | 0 | 10 | 10 |
| Ktor | 5 | 1 | 9 | 9 |
| kotlinx.coroutines | 6 | 0 | 8 | 8 |
| Kast | 6 | 0 | 7 | 7 |

All source hashes stayed unchanged.
All `ALL_DECLARATIONS` variants and negative controls passed.
Ktor `SEARCH_TEXT` kept a known minimum of 77 rows but rejected with `DISCOVERY_INCOMPLETE`.
The other three positive word searches completed with their named witnesses.
The source tranche added 24 RUN calls and ten retained reads.

The original receipts label declaration enumeration `B01-all-*` and text queries `B03-text-*`.
The checklist assigns those cases to B03 and B04, respectively.
Future runs use the corrected IDs, and the original receipts remain unchanged.

## Native workload replay

The existing `KAST_SOURCE` profile passed all twelve trials with fixed grants, one warmup, and two measured repetitions per workload.
All 51 public calls matched diagnostics.
Every trial reported complete workload results without unavailable measurements.

| Workload | Rows per trial | Public calls per trial |
| --- | ---: | ---: |
| Exact source | 1 | 1 |
| Exact negative | 0 | 1 |
| Dense references | 194 | 11 |
| Scoped declarations | 67 | 4 |

The initial, start, and end native pins passed against the same IntelliJ process.
They recorded plugin version `0.0.3708`, public contract 17, and 71 class-resource hashes from the plugin class loader.
The imported model contained 156 IntelliJ modules, 58 Gradle projects, and 117 source roots.
The corpus matched all 4,639 archive files for commit `e7075cbb0cb42bede2976136182c728f3ebece82`.
The local Git archive also matched the supplied archive bytes.

The replay made twelve RUN calls and 39 retained reads.
It produced no execution continuation, so `RESUME` remains unexercised in these tranches.
The pins keep runtime/source correspondence explicitly unproven.
This replay supplies native measurements and complete workload results, without an improvement claim.

The first comparator treated fresh row IDs and retained-result IDs as semantic changes.
The repaired comparison admits the complete presentation chain before it normalizes those two handle fields.
It keeps full row payloads, order, ranges, provenance, and retention kind.
All four saved workload pairs now compare as equivalent, with no missing measurements and no work-reduction claim.
The original trial files and failed comparison evidence remain unchanged.

## Transport controls

Spring and Kast passed ten MCP/Tool RPC parity comparisons across positive, negative, retained, callee, and rejected-region cases.
Each transport used fresh discovery and its own issued references.
The 22 public calls produced 18 exact diagnostic joins.
Four expected source-region rejections supplied no semantic join.

Two Tool RPC query sessions each accepted an initial retained RUN and rejected a second RUN with `QUERY_SESSION_TRANSITION_REJECTED`.
Those sessions produced four response frames and two semantic invocations.
Both original source witnesses kept their hashes.

Two DISCARD queries requested eight rows and received automatic retained prefixes.
They supplied no `query-output:v1` token, so the positive RESUME control remains blocked.
The source owner retains a result when its row or byte preview limit needs later presentation.
These controls do not count READ_RESULT pagination as RESUME.

## Harness checks

The fixed-work replay profiles now explicitly select `BOUNDED` with their existing grants.
The drain owner preserves partial grants and the requested output projection across retained reads.
Focused regression checks failed before both fixes and passed after them.
Oracle controls also reject a successful payload with a rejection word and a source range that splits a UTF-16 scalar.
Campaign success now needs final source admission, so source movement after the last call cannot produce exit code zero.
The source-movement regression failed before that fix and passed after it.

The fresh-handle comparison and checklist-ID regressions failed before their repairs and passed after them.
The nearest negative controls preserve semantic changes and reject changed owners or duplicate row IDs.
Ruff, strict Pyright, and 15 focused campaign tests passed.
The 83 semantic comparison tests passed in their repository-owned environment.
Schema admission passed for 32 prepared relation requests against the installed catalog.
Schema admission also passed for 16 prepared source requests after it rejected an invalid scope field.
The failed schema receipt remains separate from product evidence.
These local checks do not prove real-host semantic completion.
