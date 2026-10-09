# Native call work accounting: first checkpoint

Status: local source and production-rule evidence. Native qualification remains open.

Baseline: `345ed17566eceab7b8975e9b3c3e079b32c4a3b4` (`origin/main` at task start).
Task branch: `codex/native-work-accounting`.
The sanitized timeout audit supplied the investigation scope. Its private incident fixture was unavailable.

## Decision

Measure invocation counts before using elapsed time to explain work. Keep counts in their declared units. One compiler analysis, one provider callback, and one stream read remain different observations. Do not add them into an unexplained score.

Each measured synchronous boundary records entry, return, cancellation, failure, and unfinished work. Counts retain the enclosing boundary kind. A typed, bounded receipt declares the complete supported call vocabulary and includes explicit zero rows. Zero means no invocation under that row's declared parent kind. It does not prove that all IntelliJ work was observed.

Entry and exit clock observations provide inclusive duration estimates. Durations can overlap. They cannot establish CPU cost, deterministic work, or a reduction in work. The replay comparator uses invocation counts for that comparison.

The private diagnostic schema advances from version 6 to version 7. Existing public operation schemas and semantic budgets are unchanged.

Sources: [call vocabulary](../../workspace/intellij-read/src/main/kotlin/io/github/amichne/kast/workspace/intellij/read/IntellijReadCall.kt), [accounting](../../workspace/intellij-read/src/main/kotlin/io/github/amichne/kast/workspace/intellij/read/hosted/HostedReadCallAccounting.kt), [receipt](../../workspace/intellij-read/src/main/kotlin/io/github/amichne/kast/workspace/intellij/read/hosted/HostedReadDiagnostics.kt).

## Covered boundaries

| Work | Evidence now recorded | Coverage limit |
| --- | --- | --- |
| Relation preparation | Read attempts, scope compilation, subject restoration, callback fact preparation | Read-action scheduling and retries outside the attempt remain distinct work |
| Dependency capture | Capture, module inventory and dependencies, facets, source roots, SDK and recursive classpath roots | Native root resolution internals remain opaque |
| Dependency file inspection | VFS lookups and child inventories, document checks, stream opens and reads | Stream close and individual PSI/VFS accessor calls are not exhaustively counted |
| Dependency hashing | Tree entries, bytes actually read, completed hashes, per-capture memo hits | Counts do not prove hashing dominated the incident |
| Relation searches | Every reference and definition query execution and every callback, including excluded sites | Native executor work before or between callbacks remains opaque |
| Relation K2 analysis | All analysis entry points in the relation adapter | K2 internals and analysis calls in other domain adapters remain separate coverage obligations |
| Exact-name supplemental scan | Scoped file and package indexes, file callbacks, PSI file lookup, declaration scan and visited nodes | Primary short-name indexes and fuzzy contributors do not yet have equivalent call accounting |
| Exact symbol revalidation | K2 analysis in compiler symbol lookup | Other restoration/accessor work is not exhaustively covered |

Reference and definition searches share one production effect boundary. The architecture bytecode scanner classifies both native search APIs. Only that boundary may invoke them. Calls from another class or module fail admission, including calls whose source uses an alias or a fully qualified name.

The guard establishes confinement for these two declared APIs. It does not claim to discover every expensive IntelliJ API.

Sources: [observed searches](../../relation/intellij/src/main/kotlin/io/github/amichne/kast/relation/intellij/ObservedRelationSearch.kt), [effect classification](../../build-logic/src/main/kotlin/support/architecture/policy/JvmEffectRules.kt), [module policy](../../build-logic/src/main/kotlin/support/architecture/policy/KastCleanSlateModules.kt).

## First justified avoidance of work

The baseline relation adapter prepared optional callback facts before compiling scope and restoring the subject. The revised adapter admits scope and subject first. Preparation still executes inside the same native read action after admission.

| Admission result | Baseline preparation invocations per attempt, from source order | Revised preparation invocations, production-rule test |
| --- | ---: | ---: |
| Scope rejected | 1 | 0 |
| Stale subject selector | 1 | 0 |
| Subject outside scope | 1 | 0 |
| Compiler identity unavailable | 1 | 0 |
| Admitted subject | 1 | 1 |

The measured reduction is one optional preparation invocation for each rejected attempt. Because preparation is not entered, its dependency capture and hashing cannot run through that path. The table does not assign a fixed number of avoided native reads or bytes: those depend on the workspace and cache state.

The production-rule tests preserve each admission rejection. The admitted control preserves the admitted identity, preparation capability, and evaluation result. These are detached observations of the production sequencing rule. They are not a matched native before/after replay.

The ordering is justified by an invariant: optional preparation cannot make a rejected scope or subject admissible. Avoiding it removes work that the request cannot use. This proves a local avoidance rule. It does not establish a globally optimal ordering or justify narrowing dependency authority.

Sources: [adapter](../../relation/intellij/src/main/kotlin/io/github/amichne/kast/relation/intellij/IntellijRelationCompilerAdapter.kt), [production sequencing rule](../../relation/intellij/src/main/kotlin/io/github/amichne/kast/relation/intellij/AdmittedRelationPreparation.kt), [admission tests](../../relation/intellij/src/test/kotlin/io/github/amichne/kast/relation/intellij/AdmittedRelationPreparationTest.kt).

## Mechanical evidence

- Changing clock observations preserves the call-count table for the same effect sequence. Nested callbacks retain their parent search boundary.
- Returned, cancelled, failed, and unfinished calls remain distinct. Finishing a receipt does not turn a late completion into completed evidence.
- Counts satisfy `entered = returned + cancelled + failed + unfinished` when qualified as exact. Saturation is explicit. Storage is bounded by the closed call vocabulary and parent vocabulary; no invocation history or source payload is retained.
- Hashing `abc` executes two stream reads, including EOF, consumes three bytes, and completes one independently checked SHA-256 hash.
- Two captures with a one-unit work grant retain two reads and 16,384 consumed bytes, with zero completed hashes. A throwing stream retains one failed read and zero completed hashes.
- Every native inventory callback checks cancellation and elapsed admission before examining an excluded site's PSI. Excluded callbacks do not consume eligible candidate capacity.
- Compiled native-search bypasses fail architecture admission. Both supported native search APIs are tested.
- The existing replay comparator admits declared, balanced, exact call counts. Missing vocabulary, duplicate rows, undeclared parents, saturation, unfinished calls, and interrupted calls reject a complete work-reduction claim. Timing-only changes do not qualify as less work.

Tests: [accounting](../../workspace/intellij-read/src/test/kotlin/io/github/amichne/kast/workspace/intellij/read/hosted/HostedReadCallAccountingTest.kt), [hashing](../../topology/intellij/src/test/kotlin/io/github/amichne/kast/topology/intellij/SemanticInputWorkAccountingTest.kt), [callback admission](../../relation/intellij/src/test/kotlin/io/github/amichne/kast/relation/intellij/RelationCallbackAdmissionTest.kt), [bytecode confinement](../../build-logic/src/test/kotlin/support/architecture/ObservedRelationSearchBoundaryTest.kt), [replay comparison](../../experiments/host-observation/test_native_call_accounting.py).

## Remaining checkpoint obligations

The accounting foundation and local avoidance rule are reviewable. Complete per-operation coverage is not yet proven.

1. Extend the declared inventory through document saves, EDT scheduling, VFS refresh, read-action acquisition, primary indexes, fuzzy contributors, source and diagnostic adapters, and traversal dispatch. In particular, readiness work before the hosted executor cannot be inferred from its semantic receipt. Preserve explicit operation ownership across that earlier lifecycle.
2. Distinguish a submitted native effect from its asynchronous completion. The synchronous wrapper must not span coroutine suspension or transfer its parent stack across threads.
3. Capture matched installed IntelliJ receipts using the existing host-observation replay harness. Use an instrumented baseline with the same call vocabulary as the candidate; legacy receipts cannot supply missing call counts. Pin source and artifact identity, fixture, request, cache regime, budgets, and concurrency. Compare full semantic results and continuation coverage before admitting a reduction in declared call counts.
4. Retain native search internals as opaque until stronger evidence exposes them. One observed search invocation is not a bound on native CPU work or a guarantee of cooperative cancellation.
5. Use each newly covered boundary to justify another avoidance rule. Require no increase in other measured dimensions and preserve semantic qualifications. Do not infer zero work from missing evidence.

Dependency closure narrowing, cache changes, timeout increases, coefficient fitting, and vector analysis remain outside this checkpoint.

## Validation record

The focused accounting, hashing, and admission selectors passed. Final validation passed 791 JVM tests across the four affected modules and the hosted consumer, plus 222 replay-harness tests. All four module quality checks passed, including formatting, static analysis, and file-length checks. The native-search bytecode confinement test passed separately. The JSON contract guard reported 636 fingerprints and zero violations. Architecture verification and knowledge citation checks passed.

Commands:

```sh
mise exec -- ./gradlew :workspace:intellij-read:check :relation:intellij:check \
  :symbol:intellij:check :topology:intellij:check :runtime:hosted:test \
  verifyKastArchitecture verifyJsonContracts hostObservationTest --console=plain
mise exec -- ./gradlew :build-logic:test --tests '*ObservedRelationSearchBoundaryTest' --console=plain
mise exec -- ./gradlew knowledgeImpact verifyKnowledgeBase --console=plain
```

No installed host was replaced or restarted. No live latency, incident recovery, or whole-system minimum-work claim is established.

Final knowledge impact reports eight affected concepts. Generated OpenWiki pages remain owned by their refresh lifecycle. Citation validation does not establish that their prose Claims describe the revised call accounting.

The shared writing assessment returned `REJECTED`, stage `VERSION`, condition `VALE_UNAVAILABLE`. Vale 3.24.0 was unavailable. This remains an unavailable check, not a clean prose assessment.
