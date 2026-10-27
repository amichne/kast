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
| 3 | `c05f7aab49714ef548d818964b407648400ebbbe` | Typed semantic header and exact serialized accounting |
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
| Installed source labels | Control knowledge: `48584596eebba0b23e6fc79f8e306b8219e9ce76`. Native libraries: `9ff85c5156e6e838e5a2e46e6779db669dce35e6`. These labels do not prove source-to-binary identity |
| Source input schema match | Exact match to `d280e9fb04cfb3d266c84371fe97e103b99ccb59` |
| Pi and pi-ai | `1.4.0` |
| Pi converter SHA-256 | `eed025a4821b1e017dd024b930caf8fa11c7fe053a81efe11bd7d846fdc641d3` |
| Conversion options | `strict=false`, `supportsStrictMode=true`, `supportsOpenAIGrammarTools=false` |
| Serialization | `JSON.stringify`, no whitespace, UTF-8 |
| Native baseline model | `openai-codex`, `gpt-6.1-sol`, effort `high` |
| Native baseline plan SHA-256 | `eb378f29f1939284c8d2cade37b4bbab003c700e696480a863a029982bdfc336` |
| Expanding adapter | Pi 1.4.0 `codemode` renderer. The client identity is resolved. The screenshot measurements are not reproduced |

The converter path is `/Users/amichne/.pi/agent/install/releases/1.4.0/node_modules/@earendil-works/pi-ai/dist/api/openai-responses-shared.js`.
The complete hashes, compressed catalogs, and declarations reside in `baseline/`.
The catalog capture did not invoke semantic work.
The native baseline report records separate executable, package, `onboardingSha256`, and plan hashes.

## Reported figures and reproduced measurements

The supplied screenshot reports 105,401 declaration characters and 102,809 parameter characters.
It also reports 3,251 discovery characters, a roughly 51 KB schema-search result, and agent-side JSON slicing.
These supplied figures are not reproduced measurements.
The installed Pi paths now have direct conversion captures. The supplied screenshot is not reproduced.

The reproduced baseline uses the installed MCP process and actual installed Pi converter before product changes.

| Item | Baseline characters / bytes | COMMON characters / bytes | COMPLETE characters / bytes |
| --- | ---: | ---: | ---: |
| Complete MCP catalog | 935,645 / 935,645 | 898,412 / 898,412 | 943,570 / 943,570 |
| Query input schema | 51,134 / 51,134 | 8,992 / 8,992 | 51,972 / 51,972 |
| Query output schema | 843,990 / 843,990 | 826,800 / 826,800 | 826,800 / 826,800 |
| Pi query declaration | 53,721 / 53,721 | 9,401 / 9,401 | 54,559 / 54,559 |
| All Pi provider declarations | 59,212 / 59,212 | 14,888 / 14,888 | 60,046 / 60,046 |

These candidate values replace the earlier stale captures in `candidate-common/` and `candidate-complete/`.
They use the actual production MCP catalog serializer and installed Pi converter, with controlled preparation effects.
They include every advertised helper declaration.
The capture performs no semantic query and does not prove installed candidate replay or model consumption.
All measured text in this capture uses ASCII, so character and UTF-8 byte counts match.

The COMMON query declaration is 17.50% of the reproduced Pi baseline, within the proposed 20% query target.
All COMMON declarations total 25.14% of their reproduced baseline.
The catalog includes output schemas separately from the converted provider declarations.
These conversion measurements do not establish a task-input reduction, semantic success, or task consumption through Pi.
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
| Installed Pi declaration conversion | PASS | Both installed provider conversion and `codemode` rendering use actual factories. Matched task replay remains unproved |

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
| Native discovery exposes useful typed requests | PASS | Installed Pi renderer emits all COMMON actions and fields without `unknown`. Candidate session and task replay remain unproved |
| Actual converted query declaration meets the 20% target | PASS | Actual Pi conversion reaches 17.50%. This proves provider conversion only, without task replay |
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
| Guarded session chat requests | 6 |
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
The recorded session-chat usage is finalized. It does not prove global usage through the historical nested model APIs.
Backend output-cap enforcement remains unproved.
No matched candidate workload or task-input decrease follows from this failed semantic workload.

The current task-workspace query for `QueryStateStore` succeeded through reachable Kast tools.
That success proves neither readiness of the original fixture nor candidate workflow acceptance.
A current installed-MCP recheck used the original fixture root, exact package scope, source set, budgets, and retention.
It again returned `BOUNDARY_REJECTED` with `daemon-operation-lifecycle-import-failed`.
The complete response resides in `baseline/fixture-recheck.jsonl.gz`.
No model inference occurred in that recheck.
Its rejection uses 154 canonical UTF-8 bytes and 517 complete MCP bytes, including the LF frame.
Those control bytes do not measure a successful semantic workflow.

The two import failures came from the absent declared `:noise0` directory.
The existing fixture producer creates that empty module without a build script.
The repair created only that directory and preserved every tracked fixture byte.
Normal import then reached admission and completion.
The same installed baseline MCP workflow passed without model inference or a runtime installation.

| Baseline MCP case | Independent expected result | Actual result | Canonical UTF-8 bytes | Complete MCP bytes with LF |
| --- | --- | --- | ---: | ---: |
| Overload lookup | String and Int in `PayloadFixture.kt` | PASS, two exact rows, complete | 3,724 | 8,070 |
| Exact String selection | Exact file and parameter type | PASS | Included in lookup | Included in lookup |
| Direct callers | `payloadCaller` only | PASS, complete, no omissions | 4,733 | 10,130 |
| TRACE | `payloadTarget`, `payloadCaller`, `payloadOuter` | PASS, complete, no omissions | 17,000 | 35,700 |
| READ_RESULT | The same immutable trace rows | PASS, complete, no omissions | 17,173 | 36,058 |

The repaired records reside in `baseline/repaired-*`.
The package, source set, root, budgets, retention, original failure evidence, and payload source remain unchanged.
IDE import created four wrapper files through its Tooling API wrapper task.
Those exact files were archived outside the checkout and removed after replay.
The generated empty `noise0` directory remains as local fixture state.
The diagnosis and tracked-byte preservation receipts distinguish preparation effects from product changes.

| Matched candidate case | Status | Unmet acceptance claim |
| --- | --- | --- |
| Overload lookup and exact selection | NOT RUN | No candidate Pi session and model task |
| Callers or references | NOT RUN | No matched candidate task |
| Small trace | NOT RUN | No matched candidate task |
| Retained finding and detail read | NOT RUN | Candidate runtime and detail delivery remain unproved in Pi |
| Incomplete coverage | NOT RUN | Qualified or rejected conclusion remains unproved in matched real-client tasks |
| Stale or wrong-kind reference | NOT RUN | Finite rejection and no replay remain unproved in matched tasks |
| Oversized entry followed by a small entry | NOT RUN | Both findings and exact detail retrieval remain unproved in Pi |
| Same signature in another file | NOT RUN | Correct exact binding remains unproved in a matched candidate task |

Pi supplies both the provider conversion and `codemode` declaration paths.
The earlier unidentified-second-client blocker is withdrawn.
The repaired direct MCP workflow proves installed baseline behavior, not candidate behavior or a Pi agent task.
Candidate task bytes, tokens, cache accounting, discovery calls, recovery calls, and successful semantic invocations remain unavailable.
The original representative workflows still lack the plan's decisive matched acceptance.

The built `6e324671` frontend stopped before MCP initialization because its Gradle distribution lacks the installed one-shot marker.
No marker was manufactured and no installation occurred.
Its actual binary contract uses schema digest `98889530758600ba2f99b218cb179091743a1adce778343cd9d5c2edbc3c8049`.
The packaged digest of the loaded runtime is `87e5df4e52ed7ef590d3095dd9fd9441024cccbbfcc5db6c03980c83a71b0de8`.
Their operation registries match, but their wire schemas differ.
That binary-to-metadata comparison does not claim an actual candidate native admission result.
A supported candidate installation and a compatible candidate host load remain prerequisites for matched replay.
The comparison and artifact digests reside in `validation/candidate6e-*`.

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
| Portable JavaScript selection | 116 | 0 / 1 |
| Public-query generation Python suite | 33 | 0 / 0 |
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
| Focused checks and affected consumers | PASS | 176 focused Kotlin cases and 116 portable cases pass. One optional portable case skips |
| Generated parity and topic caps | PASS | Generation suite passes 33 cases. 30 topics fit 4,096 bytes |
| JSON contracts and architecture | PASS | Focused integrated guards pass. No new baseline allowances |
| Knowledge and glossary checks | PASS | Guards pass. All 13 existing Claims remain accurate. New-feature wiki prose remains a coverage limitation |
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

## Knowledge disposition

`knowledgeImpact` identified source citations, and `verifyKnowledgeBase` passed the structural guard.
Independent inspection found all 13 existing Claims on public admission, retained presentation, and harness delivery accurate and unchanged.
Root `AGENTS.md` conditions lifecycle refresh on changed Claims.
That conditional obligation does not trigger for these unchanged Claims.
New-feature wiki prose remains a coverage limitation.

The earlier lifecycle expanded three intended concepts into 48 jobs and assigned an unrelated page first.
No factual wiki page or Claims sidecar changed.
The interrupted run `7546a389-8686-49e0-8666-2bda11840c21` remains archived outside the checkout.
The earlier classification of missing new-feature prose as a necessary blocked refresh was too broad.
No wiki refresh completion is claimed.

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

The generated callable reference was stale after the new result contract.
`./gradlew :cli:generateMintlifyCallableReference` regenerated it through its source owner.
The byte comparison and pinned Mintlify 4.2.841 `mint validate` both pass.
The documentation, glossary, delivery-schema, and knowledge guards also pass.
These checks do not prove client replay or reader comprehension.

## Current fixture formats and historical costs

The current schema needs `original_evidence_summary`, which accepts `null`. Older serialized controls omitted that field.
The existing typed exporters now produce current synthetic documents and delivery fixtures.
A separate current native view preserves every original receipt field and grant.
Only explicit unknown summaries and the historical `BOUNDED` mode were added.
The raw native receipts and historical client/canonical pins remain unchanged.
Unknown legacy mode discriminators still reject. No original counts come from presented rows.
The host-observation suite passes all 332 tests after this repair.

The original presentation byte measurements remain historical.
New row windows have independent expected result identities and exact 43-row positions.
The current complete response uses 9,626 bytes, including 278 bytes for added syntax and asserted window facts.
The frozen response measurement remains 9,348 bytes.
Current `JSON.stringify` work uses 6,757 bytes, including 369 added bytes, and still uses 51 calls.
The frozen `JSON.stringify` measurement remains 6,388 bytes.
The tests charge actual current bytes and preserve semantic, evidence, and no-replay assertions.
A test-only historical view supports the explicit cost comparison. It removes no current output or captured facts.
A new negative case preserves captured summaries and rejects an unproved 99-row window.
These figures do not measure a real-client task improvement.

## Actual Pi `codemode` typing and final projection repair

Pi 1.4.0 supplies separate provider and `codemode` representation paths.
The capture uses installed MCP factories, registered tool names, `codemode` render functions, and provider converters.
It does not substitute a local reference expander.

The first actual COMMON rendering produced `{ request: unknown; verbose?: boolean; }`.
The installed renderer exhausts its shared 32-reference budget at the third action.
A union with an unknown branch collapses to `unknown`.
The first lost reference is `ReadResultAction`, after RUN and RESUME consume 32 expansions.
The same result reference resolves alone, which excludes an unresolved local definition as the cause.
The source, hashes, ordering control, and original captures reside in `validation/pi-before-projection-*`.

The repair substitutes 17 reference-free definitions, each with exactly one syntactic inbound reference.
It rejects reference siblings rather than discard constraints.
Shared and recursive definitions remain references.
COMPLETE remains unchanged.
Both strict and non-strict expanded contracts remain exactly equal before and after substitution.
The unchanged 10,000-byte COMMON guard passes at 8,992 bytes.

The actual installed-renderer regression first failed for request typing, then passed under the same command.
It asserts every expected action, source, terminal, selection field, selector hash, and budget field.
Its missing-Action control still renders `request: unknown`.
All 33 generator cases pass.
The affected Kotlin consumer selection passes 36 cases, with 17 App Server cases and 19 CLI cases.
Five delivery-schema cases pass.

```sh
KAST_PI_SDK_ROOT=/Users/amichne/.pi/agent/install/releases/1.4.0/node_modules/@earendil-works/pi-coding-agent mise exec -- node --test experiments/host-observation/pi_codemode_projection.test.mjs
mise exec -- node experiments/host-observation/capture_pi_codemode_payload.mjs /Users/amichne/.pi/agent/install/releases/1.4.0/node_modules/@earendil-works/pi-coding-agent "$PWD/experiments/host-observation/agent-payload" /tmp/kast-payload-pi-codemode-after-repair
```

| Actual installed Pi representation, UTF-8 bytes | Baseline | COMMON | COMPLETE |
| --- | ---: | ---: | ---: |
| All provider declarations with registered names | 59,267 | 14,943 | 60,101 |
| Query `codemode` sample | 3,132 | 5,918 | 3,132 |
| All five `codemode` samples with single LF separators | 11,012 | 23,004 | 20,218 |
| Deferred startup `codemode` description | 1,234 | 1,234 | 1,234 |
| Query input type | 336 | 5,254 | 336 |

Pi adds 11 bytes to each registered tool name, which explains the 55-byte provider aggregate difference.
COMMON now exposes the complete finite input type through the installed renderer.
Baseline and COMPLETE still contain `request: unknown` in that renderer.
Useful input typing increases the COMMON `codemode` sample and aggregate sample sizes.
No aggregate `codemode` improvement or task-wide decrease follows from these measurements.
The query provider declaration reaches 17.50% of the reproduced provider baseline.
That result does not reproduce the supplied screenshot or prove the same ratio for `codemode`.

`pi-codemode-measurements.json` records each offered declaration, optional helper, conversion option, and source hash.
Each catalog folder contains compressed signatures, input types, samples, provider declarations, and description variants under `pi-codemode/`.
Optional helper measurements do not claim actual helper loading.
The existing native baseline counts repeated provider declaration occurrences separately.
Final candidate task loading, replacement declarations, model tokens, and complete task consumption remain NOT RUN.

Fresh Pi sessions reject nested calls without a real assistant-message context.
A raw tool execute call bypasses outer validation, permission hooks, and MCP-readiness hooks.
No synthetic assistant context or raw-call task-success substitute was used.
The provider-free feasibility report is preserved in `validation/`.

The final delivery receipt records new exact-head source, product, and hosted CI outcomes.
Earlier gate success on `6e324671` remains historical evidence after this projection repair.

The historical [CI run 40510017204](https://github.com/amichne/kast/actions/runs/40510017204) passed on `6e324671`.
Its product, source, documentation, offline packaging, source feedback, and aggregate CI jobs all passed.
The separate CodeQL run passed too.
The compressed structured receipts reside in `validation/kast-payload-ci6e-*`.
These receipts do not qualify the later projection repair.

## Bounded replay preparation

The historical driver inherited Pi's default `models` capability.
Nested classifier and image calls bypass its session request guard and assistant-only token totals.
Historical records remain unchanged, and their six requests describe guarded session chat only.

The driver now explicitly uses `{mode:"on",models:false}` for both matched arms.
One frozen option value supplies both the production factory and the report metadata.
The report also records the adapter SHA-256.
The actual installed-SDK sandbox regression first failed because `models` was present.
After the change, `typeof models` returns `undefined` and a classifier call rejects before registry access.
The nearest enabled control still exposes an object without a registry call.
All 12 focused cases pass, including two installed-SDK cases and ten driver or limit cases.
No model, provider, native query, credentials change, or runtime installation occurs in those local cases.
This unit proves capability exposure. It does not replace agent-pipeline or task acceptance.

The prepared plans still use sequential states of the sole user installation.
The supported installer rejects alternate roots and session installations.
A replaced host needs ordinary IDEA restart.
No supported second installation or isolated daily-IDE profile was established.
The current host receipt identifies version `0.20261026.5903` and matches its installed inventory.
The exact original host archive was not found in the scoped outputs.
A release pair for control `0.0.3719` does not restore that exact host.
The rollback audit remains in `validation/`, with post-success restoration explicitly unqualified.

The local chat guard caps requests and serialized payload bytes before dispatch.
It measures token totals after responses, so its token ceiling can overshoot by one response.
The backend output-token cap remains requested, with enforcement unproved.
The worker requests cancellation at 180 seconds, then uses a five-second grace and a one-second termination interval.
These controls do not establish a hard dollar cap or immediate provider billing cessation.
No provider replay or installation approval follows from this preparation.
