# Kast agent-interface MVP evidence

This report records local evidence at `58a6147d01bd5c3fc80548f17f2cdab37a0e94b1` before publication.
The pull request supplies later pre-push and CI status.

The local candidate implements all three MVP segments. The fixed four-row fixture loses exactly seven compact fields and 185 UTF-8 bytes.
Verbose output keeps identical bytes. The default logger receives the exact measurements through a bounded publisher.
Production admission accepts the revised recipes without a grammar or default change.

## Revisions and ownership

- Baseline: `54fc6390e55f219dc826a9465a01c514e3201d12`.
- Candidate: `58a6147d01bd5c3fc80548f17f2cdab37a0e94b1` on `codex/agent-interface-mvp`.
- Initial checkout: `374308a461ff1b56c2732c60c428d59b39934cb7`, clean and detached, with no unique commits.
- Refresh: `git fetch origin main`, detached checkout of `origin/main`, then `git pull --ff-only origin main`. HEAD matched the fetched main.
- Test-only baseline: `/Users/amichne/.codex/worktrees/agent-mvp-baseline/kast` at `c9075c44c6323b59c0697ef4cb2d92f7d07c7073`.
- That baseline contains only the characterized extraction of `observePage`, plus uncommitted test fixtures and assertions. It contains no measurement publisher or compact omission.
- Logging, Responses, and Requests used separate managed worktrees. Each checkout had one Gradle writer.
- The coordinator owned compatibility versions, generated artifacts, adapter proof, review repairs, and serial integration.
- Publication, release, deployment, and a pull request: **not run**. The candidate remains local.

The three worker contracts fixed ownership before implementation. The supplied field policy remained unchanged.
The retained RED patches preserve the first assertion sources. The request freeze is [requests/freeze.md](requests/freeze.md).
The test-only baseline remains available for reproduction.

## Segment A: logging

Decision: extend the existing `IntellijReadObservation` and `HostedReadDiagnostics` owners.
The original owner counted pages but did not publish the exact seven measurements.
The characterized extraction placed `observePage` after the restartable `readAction` returns.
The production publisher remains outside pure projection.

Changed owners:

- `symbol/intellij/.../IntellijNativeDiscoveryAdapter.kt` offers one event for each returned discovery page.
- `workspace/intellij-read/.../IntellijDiscoveryPageMeasurements.kt` keeps constrained counts, durations, and the finite page outcome.
- `workspace/intellij-read/.../hosted/HostedDiscoveryPageAccounting.kt` owns bounded publication and finite outcomes.
- `HostedReadDiagnosticEncoding.kt` binds the publisher to the default IntelliJ logger.
- `HostedReadDiagnostics.kt` adds publication counts to the existing read receipt.

The independent producer oracle uses distinct values 11 through 17 in the seven named fields.
The default logger test asserts the exact encoded shape, owning read identity, ordinal 1, and outcome `COMPLETE`.
Separate fixtures cover `COMPLETE` and `QUALIFIED` with every measurement equal to 0, 100, or 100000.
These fixtures use no live project, compiler query, network, or filesystem during the assertion.

| Evidence | Status | Result |
|---|---|---|
| Existing diagnostics characterization | **passed** | 9 tests |
| Extracted post-read seam characterization | **passed** | 2 tests, same result identity and page count |
| First measurement assertion on baseline | **failed**, intended RED | 3 selected, 1 failure: expected one logger event, actual zero |
| Final producer and hosted tests | **passed** | 5 producer, 6 measurement, 9 existing diagnostic tests |
| Affected module checks | **passed** | 222 symbol tests and 295 workspace tests, no skips |

```sh
mise exec -- ./gradlew :workspace:intellij-read:test --tests '*HostedReadDiagnosticsTest' :symbol:intellij:test --tests '*ScopedDiscoveryDispatcherObservationTest' --console=plain
mise exec -- ./gradlew :symbol:intellij:spotlessApply :symbol:intellij:test --tests '*IntellijDiscoveryPageObservationTest' --console=plain
mise exec -- ./gradlew :symbol:intellij:test --tests '*IntellijDiscoveryPageObservationTest' --console=plain
mise exec -- ./gradlew :workspace:intellij-read:test --tests '*HostedDiscoveryMeasurementsTest' --tests '*HostedReadDiagnosticsTest' :symbol:intellij:test --tests '*IntellijDiscoveryPageObservationTest' --console=plain
mise exec -- ./gradlew :workspace:intellij-read:check :symbol:intellij:check --console=plain
```

The third command identifies the logging RED selector. Native XML records distinguish the earlier two-test characterization from the three-test RED.
The final integrated focused run appears in the command log later in this report.

Publication keeps at most 16 records per read and obeys a lower configured diagnostic count.
A 19-offer case publishes 16 events and suppresses three. A finished read publishes no more events.
A configured count of two saturates accounting with an explicit qualifier, while each published measurement remains exactly 100000.
A logger exception yields `FAILED`, with one logger call and no retry.
An unavailable publisher yields `UNOBSERVED`. Neither case reports successful publication or changes the semantic page.
Pre-batch rejection and cancellation produce no fabricated measurement event.

The retry case scripts two calculations inside the read effect and one returned page.
It proves post-return placement and one publication. It does not reproduce a real IntelliJ restart.
Default logger substitution proves binding and encoded calls, not production log retention.

| Measurement in each field | Complete JSON bytes | Qualified JSON bytes | Complete prefixed bytes | Qualified prefixed bytes |
|---|---:|---:|---:|---:|
| 0 | 276 | 277 | 296 | 297 |
| 100 | 290 | 291 | 310 | 311 |
| 100000 | 311 | 312 | 331 | 332 |

The prefix is `kast_discovery_page `. Sizes exclude a logger timestamp, category decoration, and newline.
The sizes describe these fixtures, not a latency or heap result.
[Encoded event examples](integration/encoded/discovery-log-2.json) contain only fixture read identities and admitted measurements.

## Segment B: compact responses

Decision: add one CLI discovery DTO and extend the existing compact/full projection.
The internal discovery document remains unchanged.
The private constructor admits complete typed observations, and `compact()` removes only the seven measurements.
The serializer preserves meaningful arrays and semantic evidence.

Changed owners are `CanonicalQueryCliDocuments.kt` and `QueryDiscoveryObservationCliDocument.kt` in `protocol/wire`.
The owning tests are `CompactDiscoveryPresentationTest` and `QueryProgressSchemaTest`.
The case-owned `AgentInterfaceDiscoveryFixture` supplies two declarations and two occurrences with distinct ranges and row IDs.
Its handles have admitted test syntax. The fixture does not claim live issuance or compiler execution.

The independent assertions name exact identities, signatures, source ranges, occurrence multiplicity, authority, discovery scope, progress, stops, and recovery data.
Complete, qualified, rejected, and evidence-only outcomes use fixed rows and grants.
Only the seven measurements vary across 0, 100, and 100000.
Compact bytes remain identical across those variants. Verbose values remain exact.

| Evidence | Status | Result |
|---|---|---|
| Existing compact characterization | **passed** | 4 tests |
| First response oracle on baseline | **failed**, intended RED | 5 selected, 2 failures: leaked fields and measurement-dependent compact bytes |
| First response GREEN | **passed** | The same 5 tests |
| Final response protection | **passed** | 8 discovery tests plus 4 existing compact tests |
| Output schema proof | **passed** | 2 tests, actual compact/full documents and invalid-shape controls |
| Existing empty exact-symbol matches | **passed** | `QueryTextMatchProjectionTest`, 1 test |

```sh
mise exec -- ./gradlew :protocol:wire:test --tests '*CompactToolPresentationTest' --console=plain
mise exec -- ./gradlew :protocol:wire:test --tests '*CompactDiscoveryPresentationTest' --console=plain
mise exec -- ./gradlew :protocol:wire:test --tests '*CompactDiscoveryPresentationTest' --tests '*CompactToolPresentationTest' :cli:test --tests '*QueryProgressSchemaTest' :query:protocol:test --tests '*QueryTextMatchProjectionTest' --console=plain
```

A later guard repair extracted assertion helpers without changing the independent expected values.
It replaced assembled empty JSON arrays with assertions against parsed lists.
Negative schema cases serialize deliberate mutations of real documents. No legacy allowance changed.

| Same nonzero fixture | Baseline compact bytes | Candidate compact bytes | Baseline verbose bytes | Candidate verbose bytes |
|---|---:|---:|---:|---:|
| Complete, four rows | 5081 | 4896 | 5110 | 5110 |
| Qualified, four rows | 5467 | 5282 | 5566 | 5566 |
| Evidence-only, zero rows | 1545 | 1360 | 1574 | 1574 |
| Rejected | 123 | 123 | 123 | 123 |

A parsed comparison removes only the seven fields from the baseline and equals the candidate document.
The verbose strings match byte for byte. The primary fixture stays below existing delivery limits.
[Baseline compact](integration/encoded/baseline-compact.json), [candidate compact](integration/encoded/candidate-compact.json), and [verbose](integration/encoded/candidate-verbose.json) preserve fixture provenance.

Required `items`, observation arrays, exact-symbol `connections`, progress collections, and omission samples preserve empty values.
Existing omission of empty optional `failures`, `omissions`, and `matches` remains characterized.
Nonempty failures and omissions preserve their finite reasons and evidence.
Unknown progress variants, missing semantic fields, invalid ordering, and negative counts reject under the advertised schema.

## Segment C: request guidance

Decision: reuse production admission and correct conflicting authored guidance.
No runtime grammar, loading policy, tool identity, default field set, or follow-up behavior changed.
The current `search.mdx` walkthroughs already use the correct request surface and remain unchanged.

Source comparison established these discrepancies:

| Guidance claim | Current source and correction |
|---|---|
| Lowercase actions or five intent tools | The authored schema owns uppercase actions and four intent tools. |
| Required RUN steps | Steps are optional. Null and empty steps preserve their declared behavior. |
| Row selectors unavailable | Result sources accept issued `rowIds`, including a meaningful empty selection. |
| RESUME continues semantic execution | Public `RESUME` delivers produced output with `query-output:v1`. |
| `completeImpactFindings` selects `COMPLETE_ONLY` | Public completion policy is fixed. The recipe does not select it. |

Changes affect `tools.schema.json`, `public-query-contract.md`, the query `AGENTS.md`, and `query-patterns.md`.
Two happy-path recipes omit redundant defaults: `includeSubdirectories: true` and `strategy: BREADTH_FIRST`.
Scope restrictions, explicit fields, presentation limits, intentional retention, and step order stay unchanged.
Deliberate default, null, empty-value, and rejection examples remain intact.
Illustrative handles remain distinct from issued authority and anchored source selectors.

The independent canonical oracle names the orders directory, descendant scope, main source set, function kind, and ordered visibility/name predicates.
It also names caller direction, depth two, breadth-first strategy, retained seed scope, and traversal-record output.
Production schema and typed admission accept all 29 valid recipes and reject all 28 invalid examples.

| Evidence | Status | Result |
|---|---|---|
| Existing admission characterization | **passed** | 24 tests |
| New recipe characterization before authored edits | **passed** | 6 tests |
| Candidate admission and recipe tests | **passed** | 30 tests |
| Generated copies | **passed** | Authored and generated examples agree |
| Runtime RED for prose corrections | **not run** | Reused correct admission needs no manufactured failure |

```sh
mise exec -- ./gradlew :app-server:test --tests '*PublicToolContractTest' --tests '*PublicToolSchemaTest' --tests '*PublicToolContinuationTest' --tests '*PublicToolCompletionPolicyTest' --console=plain
mise exec -- ./gradlew :app-server:test --tests '*PublicToolRecipeTest' --console=plain
```

Worker runs temporarily used `-x :app-server:verifyPublicQueryGeneration` after authored edits and before coordinator generation.
The integrated run restored that guard and passed.
The compact request JSON shrank from 514 to 485 bytes for `publicFunctionInventory` and from 278 to 242 for `boundedCallerWalk`.
All other example arguments match baseline. These counts describe recipes, not the request schema.

## Adapter and compatibility proof

Current production projections pass through the real MCP and RPC result adapters.
A strict invocation counter permits one call for each case and rejects more calls.
Each MCP response contains one measured text block and the identical structured document.
This wire contract does not establish duplicate model input.

| Compact response | MCP baseline bytes | MCP candidate bytes | RPC baseline bytes | RPC candidate bytes |
|---|---:|---:|---:|---:|
| Complete | 10985 | 10601 | 5112 | 4927 |
| Qualified | 11803 | 11419 | 5499 | 5314 |
| Evidence-only | 3481 | 3097 | 1576 | 1391 |
| Rejected | 450 | 450 | 163 | 163 |

Verbose MCP and RPC bytes remain identical between baseline and candidate.
The adapter comparison used the same test source and fixture semantics in both checkouts.
Fixture token comparison found only whitespace and optional-comma differences.
The baseline adapter selector ran one test with eight outcome/detail cases.

```sh
mise exec -- ./gradlew :cli:test --tests '*AgentInterfaceAdapterTest.MCP and RPC*' --console=plain
```

Bundled Pi and Copilot presentation functions consume fresh production documents.
The canonical and bundled functions produce identical compact text and preserve verbose text through repeated renders.
Complete, qualified, evidence-only, and rejected presentation text sizes are 4478, 4743, 975, and 163 bytes.
The functions receive no transport, clock, logger, or semantic capability. No import request occurred.
These numbers measure presentation text. They do not count a Copilot SDK message envelope.

The installed Pi package `@earendil-works/pi-coding-agent` version `1.0.2` passed pure provider conversion.
Eight fresh outcome/detail cases each produced one `function_call_output` with exactly the expected text.
The conversion retained local SDK details and excluded them from provider input.
The report keeps converter source digests in [adapter metrics](integration/adapter-metrics.log).
No provider request or model inference occurred.

Existing delivery tests preserve byte caps, page counts, finite stops, output-only continuation, stale authority, and failed detail reads.
Their pinned native fixtures keep their original provenance. This run does not relabel them as current native captures.
Repeated projection has no discovery publisher. Retained-output tests permit only the existing delivery requests and reject semantic replay.
This proves the tested interfaces, not a measured decrease in compiler work or restoration across a live workload.

Compatibility changes:

- Installed server projection advances from 18 to 19. The producer and broker agree, and tests reject version 18 catalogs.
- The generated output schema makes only the seven measurements optional within discovery observations.
- A consumer that needs those measurements in compact output must use the matched schema or request verbose output.
- Hosted diagnostic schema advances from 14 to 15 for publication counts and typed page events.
- Public tool contract version 14 and the host-wire contract stay unchanged.

## Integrated commands and outcomes

The central baseline replay selected eight tests and reproduced three intended failures.
The five semantic controls passed. A dependency error, compiler error, timeout, or empty selection did not count as RED.

```sh
mise exec -- ./gradlew :protocol:wire:test --tests '*CompactDiscoveryPresentationTest' :symbol:intellij:test --tests '*IntellijDiscoveryPageObservationTest' --continue --console=plain
```

Generation ran only after authored changes converged:

```sh
python3 packaging/generate-public-query.py
mise exec -- ./gradlew -I /tmp/kast-agent-mvp-evidence/integration/export-schemas.gradle :cli:mvpSchemaExport :cli:generateMintlifyCallableReference --no-configuration-cache --console=plain
```

Generation: **passed**. The existing Java exporter supplied the Pi schemas from actual serializers.
The retained init script supplies its query-contract dependency. The schema and generated Mintlify reference agree with production output.

The final integrated focused run selected 77 tests, with no failures or skips:

```sh
KAST_PI_PACKAGE=/Users/amichne/.local/share/config/dependencies/pi-1.0.2/node_modules/@earendil-works/pi-coding-agent mise exec -- ./gradlew :protocol:wire:test --tests '*CompactDiscoveryPresentationTest' --tests '*CompactToolPresentationTest' :symbol:intellij:test --tests '*IntellijDiscoveryPageObservationTest' :workspace:intellij-read:test --tests '*HostedDiscoveryMeasurementsTest' --tests '*HostedReadDiagnosticsTest' :cli:test --tests '*AgentInterfaceAdapterTest' --tests '*QueryProgressSchemaTest' --tests '*PiQuestionSchemaParityTest' --tests '*InstalledServerProjectionTest' :app-server:test --tests '*PublicToolRecipeTest' --tests '*KastProviderTest' --tests '*KastPublicQueryProviderTest' --console=plain
```

After test-helper repairs, 12 focused tests, Detekt, and the JSON guard passed:

```sh
mise exec -- ./gradlew :protocol:wire:test --tests '*CompactDiscoveryPresentationTest' :cli:test --tests '*QueryProgressSchemaTest' --tests '*PreferredReadProjectionTest' :protocol:wire:detekt :cli:detekt verifyJsonContracts --continue --console=plain
```

The final affected checks and repository guards passed.
Later prose edits adjusted heading placement and named the diagnostic owner. The prose assessment then ran again.
Production code and test sources stayed unchanged:

```sh
KAST_PI_PACKAGE=/Users/amichne/.local/share/config/dependencies/pi-1.0.2/node_modules/@earendil-works/pi-coding-agent mise exec -- ./gradlew :protocol:wire:check :cli:check :app-server:check verifyJsonContracts verifyKastArchitecture knowledgeImpact verifyKnowledgeBase --continue --console=plain
```

| Final suite or guard | Status | Evidence |
|---|---|---|
| Protocol wire | **passed** | 205 tests |
| CLI | **passed** | 421 tests |
| CLI native adapter cases | **passed** | 4 tests: wire deadlines and existing IDE sockets |
| App Server | **passed**, 3 **skipped** | 637 selected, 634 ran |
| Workspace IntelliJ read | **passed** | 295 tests in logging worktree |
| Symbol IntelliJ | **passed** | 222 tests in logging worktree |
| JavaScript delivery/session and transport suites | **passed** | 44 and 55 tests, no skips with installed Pi |
| JSON contracts | **passed** | 631 fingerprints, zero violations, unchanged allowance policy |
| Architecture | **passed** | `verifyKastArchitecture` |
| Public generation and Mintlify reference parity | **passed** | Included affected guards |
| Knowledge citations | **passed** | 28 concepts, five reserved files, zero issues |
| Knowledge impact | **passed** | Seven affected concepts against the task baseline |
| Diff whitespace | **passed** | `git diff --check` |

The baseline adapter characterization passed eight Kotlin tests and 43 of 44 JavaScript cases before the Pi path was supplied.
The optional Pi case then passed in a separate 29-case presentation run with the installed package.
The final suites include that conversion case.

Earlier integration runs failed on test imports, test style, deliberate handwritten negative JSON, and one stale version assertion.
Those failures did not establish product RED. Later focused and affected runs passed.
The first baseline adapter invocation selected zero tests before its copied test source entered compilation.
The corrected invocation selected one test and passed. Both logs remain available.

## Prose, review, and limits

The pinned Vale 3.24.0 procedure returned `ASSESSED` for the three changed prose files.
It reported three grade advisories and 19 terminology findings, with no 25-word finding.
The terminology findings concern existing provisional glossary terms. The source glossary reserves adoption for a separate pass.
Technical vocabulary remains precise. This is not a clean report or proof of reader comprehension.

```sh
python3 /Users/amichne/.codex/plugins/cache/slopsentral/repository-knowledge/1.2.0/skills/controlled-technical-writing/scripts/assess_text.py --vale-bin /Users/amichne/.local/share/mise/installs/vale/3.24.0/vale --glossary docs/GLOSSARY.md app-server/docs/public-query-contract.md app-server/src/main/kotlin/io/github/amichne/kast/appserver/query/AGENTS.md agent-tools/skills/kast/references/query-patterns.md
```

This report also received an `ASSESSED` result with one grade advisory and one terminology finding.
Its named concepts preserve the technical meaning. Reader comprehension remains unassessed.

Independent read-only reviews found no remaining production or assertion defect.
Review identified an unmeasured content-block metric in the JavaScript harness. The coordinator removed it before the final run.

Explicit limits:

- Installed Codex acceptance: **skipped** in three cases because `KAST_CODEX_ALIAS_ACCEPTANCE_EXECUTABLE` and `KAST_CODEX_SCHEMA_DIRECTORY` were absent.
- Copilot SDK conversion: **skipped**. Local resolution of `@github/copilot-sdk` returned `ERR_MODULE_NOT_FOUND`. Bundled presentation proof passed.
- Live IDEA workload, real read-action restart, production log retention, paid API calls, and live model behavior: **not run**.
- Mintlify site validation: **not run**. No authored MDX changed. Generated callable-reference parity passed.
- Generated OpenWiki refresh: **not run**. Repository policy assigns generated-page upkeep to its scheduled workflow. The impact report names the seven concepts.
- Token counts, billing, latency, heap, model fidelity, and enterprise performance: **not run**. Byte reduction does not establish those claims.

The candidate introduces no TOON format, new query tool, global signature default, or delivery-cap increase.
The clean task checkout and preserved baseline provide rollback and reproduction points.
