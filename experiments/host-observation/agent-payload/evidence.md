# Agent payload evidence

The implementation uses branch `feat/agent-payload` in `amichne/kast`.
The refreshed `origin/main` was `d280e9fb04cfb3d266c84371fe97e103b99ccb59`, which matches the inspected baseline.
The initial refresh needed no reconciliation.
Main later added outcome PR #1112 at `0a2ae931796be401c9906e9e4796b5f3fc208dc1`.
The four task commits now use that fetched integration base.
The integration preserves its call-target reuse, recursive capture definitions, semantic manifest, and native fixtures.
The existing Java exporter resolved the sole generated-schema conflict.
The original checkout remains named by `preserve/agent-payload-original-9269df7` at `9269df7c457252907ec5153f8f6ef54db9caf466`.

The implementation extends public admission, shared query delivery, retained presentation, and existing reference stores.
Exact identity, scope, freshness, original completion, qualifications, omissions, and finite failures remain the protected facts.
A summary reference does not count as delivered evidence.

## Commit and evidence scope

| Segment | Commit | Scope |
| --- | --- | --- |
| 1 | `240c5139aaf1dc49526689f805d92a33009791e9` | Converted baseline, independent oracle, generated topic help |
| 2 | `a801de8891dd00a3cc87006b45918abdcfd80ee2` | Generated COMMON catalog and explicit session selection |
| 3 | `ac0d09aed6082503112784ff684cb5a3d693d77c` | Typed semantic header and exact serialized accounting |
| 4 | Final branch HEAD, recorded with the delivery receipt | Deferred entries and authority-bound detail reads |

This report distinguishes source inspection, focused checks, catalog conversion, and native semantic replay.
An earlier focused pass does not prove the final integrated source gate.
The final delivery receipt records the branch HEAD and the complete source-gate outcome for that exact committed tree.

## Independent acceptance oracle

The authored oracle is [agent-payload-oracle.json](../pi-fixtures/agent-payload-oracle.json).
Its Kotlin source resides in the existing `semantic-fixture/logging` project.
The fixture contains String and Int overloads, an exact caller, and a two-edge trace path.
A same-signature declaration in another file supplies the nearest identity control.
The fixed scope is package `repro.payload`, with `includeSubpackages=false`, with source set `main`.

The existing `QueryDeliveryFixtures` owner supplies qualified and rejected outcomes.
The qualified fixture establishes a known minimum of 43 with `TIME_LIMIT_REACHED`.
The rejected fixture preserves `COMPLETION_UNPROVEN`.
The oversized oracle puts a large entry before a small entry and specifies exact detail content independently.
Stale, foreign, wrong-kind, and mismatched-result controls must reject without semantic replay.

Portable checks exercise production admission, projection, serialization, retained reads, and finite recovery with controlled effects.
They do not establish model success, installed candidate behavior, or the unknown expanding adapter's conversion.

## Adapter and fixture identities

| Item | Recorded identity |
| --- | --- |
| Baseline Kast | Installed version `0.0.3719` |
| Installed manifest SHA-256 | `d8c977d1028aad719009c57a6a7b92cbd463feafb1365d916d4c3f8709c313e5` |
| Installed runtime source revision | UNAVAILABLE |
| Source input schema match | Exact match to `d280e9fb04cfb3d266c84371fe97e103b99ccb59` |
| Pi and pi-ai | `1.4.0` |
| Pi converter SHA-256 | `eed025a4821b1e017dd024b930caf8fa11c7fe053a81efe11bd7d846fdc641d3` |
| Conversion options | `strict=false`, `supportsStrictMode=true`, `supportsOpenAIGrammarTools=false` |
| Serialization | `JSON.stringify`, no whitespace, UTF-8 |
| Native baseline model | `openai-codex`, `gpt-6.1-sol`, effort `high` |
| Native baseline plan SHA-256 | `eb378f29f1939284c8d2cade37b4bbab003c700e696480a863a029982bdfc336` |
| Expanding adapter | UNAVAILABLE: the answer “query” does not identify an adapter, installed path, or conversion options |

The converter path is `/Users/amichne/.pi/agent/install/releases/1.4.0/node_modules/@earendil-works/pi-ai/dist/api/openai-responses-shared.js`.
The complete hashes, compressed catalogs, and declarations reside in `baseline/`.
The catalog capture did not invoke semantic work.
The native baseline report records separate executable, package, `onboardingSha256`, and plan hashes.

## Reported figures and reproduced measurements

The supplied screenshot reports 105,401 declaration characters and 102,809 parameter characters.
It also reports 3,251 discovery characters, a roughly 51 KB schema-search result, and agent-side JSON slicing.
These supplied figures are not reproduced measurements.
The adapter-specific comparison remains BLOCKED until its identity and options become available.

The reproduced baseline uses the installed MCP process and actual installed Pi converter before product changes.

| Item | Baseline characters / bytes | COMMON characters / bytes | COMPLETE characters / bytes |
| --- | ---: | ---: | ---: |
| Complete MCP catalog | 935,645 / 935,645 | 899,179 / 899,179 | 943,570 / 943,570 |
| Query input schema | 51,134 / 51,134 | 9,759 / 9,759 | 51,972 / 51,972 |
| Query output schema | 843,990 / 843,990 | 826,800 / 826,800 | 826,800 / 826,800 |
| Pi query declaration | 53,721 / 53,721 | 10,168 / 10,168 | 54,559 / 54,559 |
| All Pi provider declarations | 59,212 / 59,212 | 15,655 / 15,655 | 60,046 / 60,046 |

These candidate values replace the earlier stale captures in `candidate-common/` and `candidate-complete/`.
They use the actual production MCP catalog serializer and installed Pi converter, with controlled preparation effects.
They include every advertised helper declaration.
The capture performs no semantic query and does not prove installed candidate replay or model consumption.
All measured text in this capture uses ASCII, so character and UTF-8 byte counts match.

The COMMON query declaration is 18.93% of the reproduced Pi baseline, within the proposed 20% query target.
All COMMON declarations total 26.44% of their reproduced baseline.
The catalog includes output schemas separately from the converted provider declarations.
These conversion measurements do not establish a task-input reduction, semantic success, or the unknown adapter's behavior.
No improvement percentage follows from a static source-schema size.

The baseline capture used legacy `initialize` and `tools/list` on one stdio process.
The initial direct `tools/list` control rejected with `missing-request-meta`.
The successful capture preserved every advertised provider declaration.

```sh
mise exec -- node experiments/host-observation/capture_agent_payload.mjs \
  /Users/amichne/.pi/agent/install/releases/1.4.0/node_modules/@earendil-works/pi-coding-agent \
  /tmp/kast-payload-baseline-tools-list.json \
  "$PWD/experiments/host-observation/agent-payload/baseline"
```

## Segment 1 criteria

The generator owns the index, six common topics, advanced addresses, examples, enum lists, and defaults.
The authored schema remains the grammar authority.
Every topic has a 4,096-byte UTF-8 cap.
The final common examples include `readRetainedDetail`.

| Release criterion | Status | Evidence and limit |
| --- | --- | --- |
| Refresh baseline and preserve existing work | PASS | Exact base and preservation branch recorded at report start |
| Independent source and adverse fixtures before product changes | PASS | Authored oracle and existing public fixture owners |
| Actual converted baseline declarations | PASS | Installed MCP catalog and Pi converter, complete compressed artifacts |
| Every common example admits | PASS | `PublicCommonCatalogTest` admits all seven authored cases in the final focused selection |
| The enum/default mutations update topic output | PASS | Segment 1 passed 28 generation tests. The final generation suite passes 30 |
| Bounded topics and direct topic lookup | PASS | Six common topics, 30 total topic files, largest 1,568 bytes. Index 2,395 bytes |
| Source-size and expanded-equivalence guards remain active | PASS | Thirty passing generator tests preserve guards. Optional detail and three descriptions have explicit comparison normalization |
| Original expanding-adapter replay | BLOCKED | Adapter identity and conversion options unavailable |

The topic-generation RED failed because the named outputs did not exist.
The same generation command then passed all 28 segment 1 cases.
The segment 1 Kotlin admission selection passed 13 tests.
Introductory material checks passed 17 tests.
These historical counts do not replace the final integrated counts.

## Segment 2 criteria

The canonical schema owns the closed COMMON policy and generates one query projection.
COMMON supports the specified discovery sources, relation expansion, SYMBOLS or OCCURRENCES emission, TRACE, and follow-up reads.
COMPLETE preserves the advanced grammar.
Startup captures one immutable catalog, includes it in schema identity, and rejects a mismatched admitted request before native work.
Eager sessions default to COMMON. Pi's deferred registration explicitly selects COMPLETE.

| Release criterion | Status | Evidence and limit |
| --- | --- | --- |
| Common requests lower to independently expected canonical inputs | PASS | Authored canonical expectations pass in `PublicCommonCatalogTest` |
| COMPLETE preserves advanced requests | PASS | Existing contract cases and explicit COMPLETE controls pass |
| Unknown fields, unsupported variants, wrong reference kinds reject | PASS | Public admission controls pass before native work |
| One advertised query projection and immutable session choice | PASS | Direct arguments, production MCP catalog, RPC session, and catalog-mismatch controls pass |
| Advertised schema and callable detail request agree | PASS | Actual produced-header request admits both catalogs with exact selector authority |
| Pure plan dispatch honors startup catalog without installation | PASS | `QueryCostPlanTest` proves default COMMON rejection and explicit COMPLETE acceptance |
| Native discovery exposes useful typed requests | NOT RUN | No final candidate native discovery replay |
| Actual converted query declaration meets the 20% target | PASS | Actual Pi conversion reaches 18.93%. The unknown adapter remains BLOCKED |
| Every advertised helper and replacement declaration counts | PARTIAL | Complete catalogs count every advertised declaration. The native baseline records repeated replacements. Candidate task replacement loading remains unproved |

## Segment 3 criteria

The shared typed header separates original semantic outcome, delivery stop, presentation progress, and deferred detail.
Original evidence summaries come from full raw metadata before preview selection, without new reference issuance.
Captured summaries survive completion rejection and retention failure.
A legacy `Uncaptured` summary remains unknown through wire, presentation, and public output.
Its public counts remain null. Visible failures still preserve their finite codes.
A captured summary keeps all three counts. Empty reason arrays use typed defaults.
Nonempty reason sets keep their exact closed enum values.
One protocol constant defines the 4,096-byte supported minimum.

| Release criterion | Status | Evidence and limit |
| --- | --- | --- |
| Original outcome, coverage, known minimum, blockers, and omissions survive overflow | PASS | Typed header and independent qualified/rejected fixtures pass focused assertions |
| Legal next read or finite unavailability survives overflow | PASS | Canonical, MCP, and shared delivery controls pass |
| Canonical response fits actual encoded limit | PASS | Hosted response and header fixtures assert actual serialized bytes |
| Entire MCP response fits transport allowance | PASS | Five MCP cases count escaped text, structured content, JSON-RPC fields, and framing |
| Aggregate presentation fits its separate allowance | PASS | Shared delivery and renderer cases pass actual serialized-size assertions |
| UTF-8 and escaped content fits each limit | PASS | Exact serialization controls pass in focused hosted, MCP, and JavaScript cases |
| No-match, incomplete search, and omitted page remain distinct | PASS | Focused status and original-summary controls pass |
| Impossible budgets reject before semantic work | PASS | Typed minimum rejection matrix and adapter controls pass |
| Text and structured projection preserve conclusions | PASS | Portable consumer assertions pass. Real-client exposure remains NOT RUN |
| Native grants and completion semantics remain unchanged | NOT RUN | Focused consumers pass. Complete source gate and native matched replay remain pending |

Canonical response bytes, complete MCP response bytes, and aggregate presentation bytes have separate owners and limits.
The report must not add these figures or treat one measurement as proof of another.
A fixed overhead estimate does not establish a serialized limit.
The public schema describes the 4,096-byte minimum explicitly. Semantic admission enforces it before native work.

The raw-summary RED selected `AutomaticSymbolQueryEvidenceTest` and failed two assertions with expected `Captured` versus actual `Uncaptured`.
That run exercised four tests without a compiler failure.
The final cases now span `AutomaticSymbolQueryEvidenceTest` and `AutomaticOriginalEvidenceSummaryTest` after quality-only extraction.

## Segment 4 criteria

Existing retained-result storage owns full detail, freshness, and authority.
A selector binds the retained result, section, target, ordinal, kind, SHA-256, and offset.
Selector publication follows successful storage. Detail reads do not rerun semantic work.
Offsets, ends, and totals count UTF-16 code units in the exact canonical serialized entry.
The SHA-256 covers the complete entry encoded as UTF-8.

| Release criterion | Status | Evidence and limit |
| --- | --- | --- |
| Large entry receives a useful bounded summary | PASS | Oversized row, witness, and rejection fixtures pass |
| Later presentation reaches the small entry | PASS | Ordered large-then-small oracle and retained-read controls pass |
| Detail fragments reconstruct exact expected content | PASS | Unicode and escape fixtures assert independent exact content |
| Detail retrieval causes no semantic replay | PASS | Focused invocation-count assertions pass |
| Summary presentation never counts as delivered detail | PASS | Separate presentation cursor and deferred evidence-count assertions pass |
| Retention failure publishes no usable selector | PASS | Finite unavailability and original-summary preservation assertions pass |
| Expired, stale, foreign, wrong-kind, or mismatched selectors reject | PASS | Existing store rules and new finite selector controls pass |
| Produced next read admits and advances | PASS | Four header cases pass. Actual next request admits both catalogs without conflicting cursors |
| Shared-table expansion preserves exact evidence | PASS | Existing consumer assertions preserve exact identity, order, multiplicity, and evidence. No new shared table was necessary |

## Native replay and task consumption

The historical native Pi session used `codemode` and the installed MCP process.
Its request used `maxResults=20`, `maxReturnedBytes=65536`, and retention `RETAIN` with the fixed fixture scope.
The first query returned `BOUNDARY_REJECTED` with reason `daemon-operation-lifecycle-import-failed` at overload discovery.
The harness returned `COMPLETED` and exit code zero, but the semantic acceptance case did not pass.
The exact report and compressed events reside in `baseline/native-*`.

| Historical baseline metric | Actual value |
| --- | ---: |
| Provider requests | 6 |
| Tool calls, including discovery/`codemode` calls | 6 |
| Semantic query calls | 1 |
| Successful semantic queries | 0 |
| Retries / compactions | 0 / 0 |
| Input tokens without cache | 10,516 |
| Cache-read / cache-write tokens | 22,144 / 0 |
| Output tokens | 517 |
| Total reported tokens | 33,177 |
| Provider request payload bytes | 167,974 |
| Recorded tool-output bytes | 15,547 |
| All provider declaration occurrences | 34,920 UTF-8 bytes |

Declaration occurrences include repetition across requests and differ from the unique catalog size.
Provider usage is finalized. Backend output-cap enforcement remains unproved.
No matched candidate workload or task-input decrease follows from this failed semantic workload.

The current task-workspace query for `QueryStateStore` succeeded through reachable Kast tools.
That success proves neither readiness of the original fixture nor candidate workflow acceptance.
A current installed-MCP recheck used the original fixture root, exact package scope, source set, budgets, and retention.
It again returned `BOUNDARY_REJECTED` with `daemon-operation-lifecycle-import-failed`.
The complete response resides in `baseline/fixture-recheck.jsonl.gz`.
No model inference occurred in that recheck.
Its rejection uses 154 canonical UTF-8 bytes and 517 complete MCP bytes, including the LF frame.
Those control bytes do not measure a successful semantic workflow.

| Matched workflow | Native Pi `codemode` | Actual expanding adapter | Unmet acceptance claim |
| --- | --- | --- | --- |
| Overload lookup and exact selection | BLOCKED | BLOCKED | Original fixture rejects before semantic discovery. Adapter identity unavailable |
| Callers or references | NOT RUN | BLOCKED | No successful exact seed from the fixture |
| Small trace | NOT RUN | BLOCKED | Expected destinations and completion unproved in real clients |
| Retained finding and detail read | NOT RUN | BLOCKED | Immutable result and evidence retrieval unproved in real clients |
| Incomplete coverage | NOT RUN | BLOCKED | Qualified or rejected conclusion unproved in real clients |
| Stale or wrong-kind reference | NOT RUN | BLOCKED | Finite rejection and no replay unproved in real clients |
| Oversized entry followed by a small entry | NOT RUN | BLOCKED | Both findings and exact detail retrieval unproved in real clients |
| Same signature in another file | NOT RUN | BLOCKED | Correct exact declaration binding unproved in real clients |

The current fixture rejection limits that fixture's semantic acceptance claim.
It does not establish that every Kast workspace or native read is unavailable.
Candidate task bytes, tokens, cache accounting, discovery calls, recovery calls, and successful semantic invocations remain unavailable.
The original representative workflows therefore do not yet meet the plan's decisive acceptance condition.

## Commands and integrated gate handoff

These commands identify the prescribed focused checks.
The completed focused Kotlin selections cover 176 cases without failures, errors, or skips.
The original 167 cases have nine more encoding, schema, and wire controls.
The selected-output regression adds two cases to the earlier 165-case selection.
The valid evidence read first failed with `RESULT_CURSOR_OUT_OF_RANGE`, while the nearest invalid cursor still rejected.
The repair uses the selected witness-section count, and both cases pass.
No zero-test selection counts as a pass.

```sh
mise exec -- python3 packaging/test-public-query-generation.py
mise exec -- python3 packaging/generate-public-query.py --check
mise exec -- node --experimental-vm-modules --test cli/src/test/js/query-delivery-contract.test.mjs cli/src/test/js/query-presentation.test.mjs experiments/host-observation/agent_efficiency_session.test.mjs
mise exec -- ./gradlew :app-server:test --tests '*PublicToolContractTest' --tests '*PublicToolCatalogTest' --tests '*PublicToolTerminalContractTest' --tests '*PublicCommonCatalogTest' --tests '*PublicDetailSelectorContractTest' --tests '*PublicOutputMinimumTest' --tests '*ExistingIdeQueryPresentationHeaderTest'
mise exec -- ./gradlew :runtime:hosted:test --tests '*HostedQueryResponseTest' --tests '*HostedAutomaticQueryPreviewTest' --tests '*HostedQueryEntryDetailTest' --tests '*HostedRetainedEntryDetailTest' --tests '*HostedOversizedWitnessTest' --tests '*HostedQueryPresentationHeaderTest' --tests '*HostedOriginalEvidenceSummaryTest' --tests '*HostedCompletionRejectionPublicationTest' --tests '*HostedDeferredRejectionPublicationTest' --tests '*HostedQueryRetainedPresentationTest'
mise exec -- ./gradlew :query:protocol:test --tests '*QueryEvidenceOutputOwnershipTest' --tests '*QueryOutputOwnershipTest' --tests '*QueryPresentationWindowMappingTest' --tests '*AutomaticSymbolQueryEvidenceTest' --tests '*AutomaticOriginalEvidenceSummaryTest' --tests '*AutomaticSymbolQueryTest' --tests '*QueryProducerPayloadReleaseTest' --tests '*RetainedWitnessEvidenceDetailTest'
mise exec -- ./gradlew :protocol:contract:test --tests '*QueryRetainedPresentationWindowTest' --tests '*QueryEvidenceWindowDocumentTest' --tests '*ImpactFindingAccountingTest' --tests '*QueryOriginalEvidenceSummaryEncodingTest'
mise exec -- ./gradlew :protocol:wire:test --tests '*QueryOriginalEvidenceSummaryWireTest'
mise exec -- ./gradlew :cli:test --tests '*McpOutputBoundaryTest' --tests '*QueryReferenceSurfaceTest' --tests '*DirectCatalogArgumentsTest' --tests '*QueryCostPlanTest' --tests '*McpAgentPayloadCatalogTest' --tests '*KastToolRpcQuerySessionTest' --tests '*KastToolRpcConstraintAdmissionTest' --tests '*McpDirectConstraintAdmissionTest' --tests '*KastToolRpcBridgeTest' --tests '*InstalledQueryMetadataSchemaTest'
```

| Completed focused selection | Passed | Failed / skipped |
| --- | ---: | ---: |
| `:protocol:contract:test` | 14 | 0 / 0 |
| `:protocol:wire:test`, new summary controls | 2 | 0 / 0 |
| `:app-server:test` | 28 | 0 / 0 |
| `:cli:test` | 60 | 0 / 0 |
| `:runtime:hosted:test` | 42 | 0 / 0 |
| `:query:protocol:test` | 30 | 0 / 0 |
| Portable JavaScript selection | 115 | 0 / 1 |
| Public-query generation Python suite | 30 | 0 / 0 |
| Delivery schema Python suite | 5 | 0 / 0 |

The portable skip concerns the optional Pi provider-exposure case. The separate spawned-Kotlin bridge selection passes.
The portable log is `/tmp/kast-payload-js-final.log`.
The focused Kotlin log is `/tmp/kast-payload-integrated-final.log`.
Its 165 tests pass, but that invocation fails the `LargeClass` guard.
The later `/tmp/kast-payload-final-size-guard.log` passes the affected query-protocol selection and `detekt` after structural extraction.
The final query selection and guards reside in `/tmp/kast-payload-final-query-and-guards.log`.
The cursor RED and GREEN reside in `/tmp/kast-witness-detail-cursor-red.log` and `/tmp/kast-witness-detail-cursor-green.log`.
Earlier failing compile, JSON-contract, architecture, and assertion logs remain historical evidence rather than final passes.

| Required final evidence | Status | Completion evidence |
| --- | --- | --- |
| Focused checks and affected consumers | PASS | 176 focused Kotlin cases and 115 portable cases pass. One optional portable case skips |
| Generated parity and topic caps | PASS | Generation suite passes 30 cases.  30 topics fit 4,096 bytes |
| JSON contracts and architecture | PASS | Focused integrated guards pass. No new baseline allowances |
| Knowledge and glossary checks | PARTIAL | Knowledge guard passes. The OpenWiki lifecycle blocks the narrow refresh described in this report |
| Clean committed complete source gate | RECEIPT | Final delivery records the outcome from `build/reports/ci/source/source-execution.json` on the exact committed tree |
| Publication product gate | RECEIPT | The normal pre-push hook records its exact outcome. It remains separate from real-client acceptance |
| Final prose assessment | ASSESSED | Pinned local procedure preserves grade advisories. The final reports accompany the evidence artifacts |

```sh
mise exec -- python3 .github/scripts/ci/source_validation.py
```

The source gate needs the final clean committed tree and uses stable version `0.0.0`.
Source success does not prove installed candidate behavior or model task success.
An unavailable client blocks its acceptance claim, while independent implementation and source checks continue.

## Prose assessment

The pinned Vale 3.24.0 procedure and repository assessment processed this report.
The assessment returned ASSESSED with a grade-8 advisory.
The report keeps exact protocol terms and finite outcome distinctions.
The glossary loaded, but this artifact is outside its declared terminology scope.
No reader-comprehension claim follows from these metrics.

The local reports reside at `/tmp/kast-agent-payload-report-vale.json` and `/tmp/kast-agent-payload-report-assessment.json`.
The final delivery records the latest assessment outcome.

## Knowledge refresh limit

The pre-integration `knowledgeImpact` receipt reports 22 affected concepts.
`verifyKnowledgeBase` passes the existing structural guard.
The OpenWiki lifecycle accepted three task concepts: public admission, retained presentation, and harness delivery.
It expanded that plan into 48 jobs and assigned `openwiki/architecture/functional-packages.md` first.
That assignment needs an unrelated stale Claim review about `runtime/hosted/build.gradle.kts`.
No factual wiki page or Claims sidecar changed.
The interrupted run `7546a389-8686-49e0-8666-2bda11840c21` resides at `/tmp/kast-agent-payload-openwiki-interrupted`.
The task-owned lifecycle metadata was removed from the source checkout after that copy.
The [OpenWiki skill](/Users/amichne/.agents/skills/openwiki/SKILL.md) says, “write exactly the assigned Markdown page.”
The task scope excludes the unrelated first page.
`openwiki_next_page` accepts only a run ID, so it cannot select the three intended concepts.
Those concepts are public admission, retained presentation, and harness delivery.
The narrow concept refresh remains BLOCKED because no supported lifecycle operation isolates those three jobs.
The remaining documentation claim is source-current wiki prose for catalogs, semantic headers, and deferred detail.
This limit does not weaken the source contracts or convert the wiki guard into semantic proof.

## Integration and consumer repairs

The source gate exposed an active trace manifest with a stale schema digest.
Only that binding and its checksum changed. Its eleven requests and grants remain unchanged.
The broader consumer checks also exposed the unchanged 1 MiB catalog cap and the 16 KiB aggregate-headroom assertion.
Exact canonical local references now reuse query questions, original summaries, and detail selectors.
No schema field, constraint, byte allowance, or semantic result was removed.
Typed empty reason sets omit redundant arrays and decode to the same empty sets.
Fresh production captures contain 27 pages and 53 rows in each graph case.
Compact occurrence replies use 226,534 UTF-8 bytes. Compact symbol replies use 244,445 UTF-8 bytes.
Both cases fit the unchanged allowances. The symbol case keeps the necessary 16 KiB headroom.
The final consumer repair log is `/tmp/kast-payload-integrated-final-repairs.log`.

The new wire controls independently specify original counts of 1, 2, and 43 with exact finite reasons.
They preserve those facts across an empty page without a claim of delivered evidence.
The nearest unknown-state control preserves null counts and the `UNCAPTURED` state.
The complete wire suite also preserves existing identity, scope, qualification, and row-window assertions.

The first post-merge schema export used stale project archives. The parity guard rejected it.
The existing exporter then used current compiled classes before those archives.
The final parity assertion compares those generated assets with the current canonical serializer owners.
The exact exporter classpath resides at `/tmp/kast-payload-current-schema-export-classpath.txt`.
