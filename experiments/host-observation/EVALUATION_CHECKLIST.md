# Kast evaluation checklist across real codebases

This checklist defines repeatable evaluations for Kast consumers, maintainers, and release reviewers. It covers public contract 17 and the installed-host paths that support it.

The [installed-repository report](https://chatgpt.com/space/page_6adcb2ba8434819183a1878064ad321a) records the current baseline and unresolved failures.

**Status: planned coverage, with a separate recorded baseline.** An unchecked case is not run for this checklist. Each listed variant needs a separate result before its case passes.

The protected invariant is exact, scoped compiler evidence with honest completion and recovery. A missing edge, failed read, or incomplete empty result cannot prove absence.

The checklist does not claim exhaustive Kotlin support, runtime execution, or a performance improvement. Product changes, release publication, and destructive installation exercises remain separate work.

## Evidence contract

For each case, record these fields before the run:

- Case ID, parameter values, corpus, commit, source hashes, exact directory, source sets, source policy, and generated-source policy.
- Installed Control and Host versions, artifact digests, receipt identity, loaded plugin class digests, IDEA build, Kotlin plugin, JBR, and host process identity.
- Public catalog digest, schema version, transport, execution mode, requested grants, admitted grants, and caller timeout.
- Exact declarations or occurrences from an independent source oracle, expected qualification, nearest negative control, and permitted effects.
- Exact request, canonical response, process receipt, issued handles, output pages, diagnostic joins, source diff, and assertion results.
- Outcome: `PASSED`, `FAILED`, `BLOCKED`, `NOT_RUN`, or `NOT_APPLICABLE`. Record a specific reason for the final three outcomes.

A `PASSED` case meets its declared oracle. An expected typed rejection can pass a safety case while the corresponding user task remains incomplete.

Record product behavior, harness failures, prerequisite failures, and model answers separately. Do not turn an unexpected exception into an expected rejection after the run.

Keep raw logs, source payloads, host paths, and opaque handles in private evidence storage. Publish a bounded summary with source revisions and qualifications.

## Coverage tiers and corpus assignment

| Tier | Mechanism | Admission and limit |
|---|---|---|
| T0 | Schemas and pure domain owners | No IDEA or model. Proves input, result, and invariant rules only. |
| T1 | Native SDK fixtures | Exact SDK and controlled native effects. Does not prove the installed plugin. |
| T2 | Installed Control, Host, and exact workspace | Real import and compiler behavior. Uses independent source witnesses. |
| T3 | Agent integration and model behavior | Exact adapter, model, prompts, context, budgets, and provider receipts. Distinct from deterministic replay. |
| T4 | Lifecycle, stress, and performance | Owned disposable installation or profile where needed. Correctness and settlement precede timing claims. |

| Corpus | Revision for the recorded baseline | Main purpose | Minimum eligible families |
|---|---|---|---|
| Spring Framework v7.0.10 | `33a85b0bec67adb759aa898e3b80d603786be082` | Kotlin DSLs, overloads, Java interoperability, source-library crossings | A, B, C, D, E, F, H, I, K |
| Ktor 3.6.0 | `111c5807d0a9004c8e0f17ae01e12a71aae56a29` | Large multiplatform imports, receiver DSLs, nested callbacks | A, B, C, D, E, F, G, H, I, K |
| kotlinx.coroutines 1.11.0 | `8564f65764d3d05893cec026c6e94250e2b23874` | Suspend functions, Flow, aliases, inline and nested callback paths | A, B, C, D, E, F, G, H, I, K |
| Kast release source | `e7075cbb0cb42bede2976136182c728f3ebece82` | Tool self-evaluation, sealed contracts, many modules, production query tasks | A, B, C, D, E, F, G, H, I, K, M |
| Existing synthetic Kotlin fixture | Pin its source manifest before each campaign | Exact positive and negative controls, dense output, invalid states | All families within their stated tier |
| Disposable copy of each real corpus | Same pinned commit, recorded local patch | Diagnostics, controlled writes, freshness, and recovery | H, I, J, K |

These assignments select mechanisms. They do not assert that every listed family has a suitable natural witness in every repository.

Select one primary corpus and one contrasting corpus for each case. Use a synthetic control when a real source lacks the necessary construct.

For multiplatform cases, select exact imported source-set names. Distinguish `commonMain`, platform source sets, tests, and generated roots.

A Java-only corpus cannot establish Kotlin semantics. Add a small consumer application when source-library behavior differs from framework-source behavior.

Pin that application's revision and dependency binaries before inclusion. Repository size alone does not justify another corpus.

## A — Installation, admission, and public surfaces

**Setup:** Pin the release, owned installation, intended workspace, and actual catalog. **Oracle:** Compare receipts, loaded bytes, root identity, and a known symbol.

**Negative control:** Use a mismatched root or contract in a disposable setup. **Evidence:** Installation receipt, catalog, process identity, preparation result, and first semantic response.

- [ ] **A01 — Published installation.** Exercise the standard installer from outside a Kast checkout. Record channel, selected release, source identity, Control, Host, and adapter digests.
- [ ] **A02 — Loaded installation.** Restart the selected IDEA instance, then compare loaded plugin bytes with the installed receipt. A version string alone cannot pass.
- [ ] **A03 — Workspace admission.** Run a useful scoped query in the exact clone. Exercise trust, import, saved-document, committed-PSI, and smart-mode prerequisites separately.
- [ ] **A04 — Preparation recovery.** Resolve one declared prerequisite, then issue fresh demand. Keep the first failure and its recovery evidence. Never infer readiness from registration.
- [ ] **A05 — MCP and Tool RPC parity.** Run equivalent positive, negative, and rejected cases through both installed transports. Compare canonical facts and qualifications.
- [ ] **A06 — Session capability.** Exercise MCP initialization, catalog discovery, Tool RPC one-shot calls, and the advertised `querySession` path. Respect absent capabilities.
- [ ] **A07 — Agent registration.** Exercise each installed Codex, Pi, or Copilot adapter selected for the campaign. Prove catalog delivery and exact workspace binding independently.
- [ ] **A08 — Health and lifecycle separation.** Exercise `health_check` as a distinct readiness contract. Exercise hosted `workspace_lifecycle` only where its catalog exposes it.
- [ ] **A09 — Compatibility rejection.** Exercise a mismatched schema, unsupported Host, missing plugin, and stale registration in owned fixtures. Keep the specific failed stage.

## B — Discovery, identity, source sets, and source reads

**Setup:** Freeze exact declarations, overloads, offsets, whole-word sites, and expected scopes. **Oracle:** Source locations, owner, receiver, signature, kind, and full scoped inventory.

**Negative control:** Same name in another owner or source set, plus an absent exact name. **Evidence:** Discovery rows, exact selection, scope, coverage, and source ranges.

- [ ] **B01 — Declaration search.** Exercise `SEARCH_DECLARATIONS` with `EXACT`, `FUZZY`, omitted match mode, and case differences. Cover `CLASS`, `FUNCTION`, `PROPERTY`, and `TYPE_ALIAS`.
- [ ] **B02 — Exact overload selection.** Distinguish receiver, owner, parameters, return type, local ownership, and location. Equal names cannot select the first result silently.
- [ ] **B03 — Scoped inventory.** Exercise `ALL_DECLARATIONS`, `DIRECTORY`, and `PACKAGE`. Cover descendant switches, package-directory differences, root scope, and exact source-set names.
- [ ] **B04 — Text and location seeds.** Exercise `SEARCH_TEXT` whole-word selection and `AT_LOCATION` offsets. Contrast comments, strings, nested declarations, and an invalid offset.
- [ ] **B05 — Issued identities.** Exercise `SYMBOL_REFS` and retained `RESULT`, including selected `rowIds`. Preserve handles byte-for-byte and canonical identity across duplicate references.
- [ ] **B06 — Cheap exclusions.** Place many excluded names, kinds, files, or modules beside one eligible symbol. Excluded inputs must not exhaust eligible discovery capacity.
- [ ] **B07 — Scope rejection.** Exercise absolute paths, files as directories, globs, parent traversal, duplicate arrays, empty arrays, and unknown declaration kinds.
- [ ] **B08 — Symbol projection.** Exercise omitted output, null output, explicit `SYMBOLS`, `fields: []`, and each `NAME`, `LOCATION`, `SIGNATURE`, and `SOURCE` field.
- [ ] **B09 — Source structure.** Exercise `READ_SOURCE` with exact references, candidates, and anonymous selectors. Cover all seven `region` variants and strict enclosing-owner behavior.
- [ ] **B10 — Source text.** Exercise `COMPLETE`, `NONE`, `WINDOW`, and `EXCERPT` independently of structural regions. Keep actual ranges, truncation, and unavailable text explicit.

The seven source regions are `ANCHOR`, `CALLABLE_BODY`, `CLASS_BODY`, `FILE`, `ENCLOSING_DECLARATION`, `ENCLOSING_CALLABLE_BODY`, and `ENCLOSING_CLASS_BODY`.

## C — Relations and traversal

**Setup:** Freeze declaration and occurrence witnesses across two modules, tests, and one library dependency. **Oracle:** Hand-enumerated edges with each edge's actual meaning.

**Negative control:** Unused declaration, same-name overload, excluded destination, and lexical containment without invocation. **Evidence:** Rows, occurrences, owner records, depth, omissions, and completion.

- [ ] **C01 — All seven relations.** Parameterize `EXPAND_RELATION` over `REFERENCES`, `CALLERS`, `CALLEES`, `IMPLEMENTATIONS`, `INHERITORS`, `OVERRIDES`, and `TYPE_USES`.
- [ ] **C02 — Scope at destinations.** Compare `WORKSPACE`, `RETAINED_SEED`, and `SOURCE_DOMAIN`. Cover production, test, both policies, generated inclusion, and generated exclusion.
- [ ] **C03 — Scoped callback isolation.** Put a direct caller in module A and an unrelated unresolved callback in module B. Restrict destinations to A.
- [ ] **C04 — Relevant external dependency.** Make B contribute to A's answer. Demand a bounded causal chain or an explicit incomplete result, never an unexplained complete answer.
- [ ] **C05 — Source-library crossings.** Exercise Kotlin-to-Java calls, Java uses of Kotlin, annotations, light elements, unavailable sources, and a source-library parameter crossing.
- [ ] **C06 — Occurrence completeness.** Include imports, aliases, companion qualifiers, constructors, callable references, and multiple sites in one owner. Reconcile unmatched sites before an exhaustive claim.
- [ ] **C07 — Walk strategies.** Exercise `WALK` with `BREADTH_FIRST` and `BOUNDED_FAN_OUT`. Cover explicit depth, exhaustive depth, cycles, duplicate paths, and fan-out exclusions.
- [ ] **C08 — TRACE contract.** Use terminal `TRACE` with its own destination scope. Compare source-relationship completion with explicit `CALLERS` callback obligations.
- [ ] **C09 — Traversal projection.** Compare `SYMBOLS`, `OCCURRENCES`, and `TRAVERSAL_RECORDS` against their distinct oracles. A row count cannot substitute for an edge count.

## D — Callback ownership, activation, and retained direct edges

**Setup:** Pair one ordinary direct call with each callback construct. **Oracle:** Separate lexical container, anonymous owner, supplier, formal binding, and actual invocation owner.

**Negative control:** Keep a callback value that is never invoked. **Evidence:** Direct-edge witness, callback observations, mapping, scan extent, and typed unresolved obligations.

- [ ] **D01 — Direct edge beside an unknown callback.** Use `fun wrapper(f: () -> String) { sink(f()) }` beside a plain direct-call control. Keep the confirmed `sink` edge on rejection.
- [ ] **D02 — Invocation forms.** Cover `block()`, `block.invoke()`, callable references, receiver callbacks, suspend callbacks, and named formal ownership.
- [ ] **D03 — Arguments and defaults.** Cover named, positional, default, explicit replacement, and unused callback arguments. A default expression alone cannot create an invocation.
- [ ] **D04 — Aliases and forwarding.** Cover immutable aliases and multiple forwarding hops. Change one binding in the counterexample and reject the disconnected route.
- [ ] **D05 — Nested ownership.** Cover nested lambdas, anonymous functions, local named functions, and callbacks returned without invocation. Lexical containment cannot create a named caller.
- [ ] **D06 — Inline policy.** Cover supported inline routes, `noinline`, `crossinline`, declared calls-in-place kinds, missing effects, and conflicting effects.
- [ ] **D07 — Deferred dependency.** Exercise reviewed `kotlin.lazy` evidence with matching and changed dependency digests. Do not invent property reads, initializer calls, or call counts.
- [ ] **D08 — Escapes and cycles.** Cover stored callbacks, mutable aliases, returned callbacks, cyclic forwarding, and exhausted scans. Keep a specific obligation for each unsupported proof.
- [ ] **D09 — Source-less targets.** Distinguish compiler-resolved excluded library targets from requested library source that is unavailable. Missing PSI alone cannot prove exclusion.
- [ ] **D10 — First-response evidence.** Assert known direct edges in the initial retained preview where the product promises them. Later evidence pages remain a separate delivery case.

## E — Filters, composition, row kinds, and request admission

**Setup:** Prepare sets A and B with one common identity, one unique identity each, and equal names for distinct declarations.

**Oracle:** Independently compute ordered rows, canonical set membership, and evidence provenance. **Negative control:** An incomplete right input cannot prove absence.

**Evidence:** Original retained inputs, canonical identities, row kinds, result schemas, and every output qualification.

- [ ] **E01 — Predicates.** Exercise `WHERE` with `VISIBILITY` values and `PRIMITIVE` fields `NAME`, `KIND`, and `FILE` across every supported operator.
- [ ] **E02 — Set operations.** Exercise `CONCAT`, `UNION`, `INTERSECT`, `DIFFERENCE`, and `DISTINCT_SYMBOLS`. Assert order and duplicates where the contract preserves them.
- [ ] **E03 — Joins.** Exercise `JOIN` modes `INNER`, `SEMI`, and `ANTI`. Keep named cells and original evidence. Reject absence claims from incomplete right inputs.
- [ ] **E04 — Binding projection.** Exercise `BINDING_ROWS` and `PROJECT_BINDING` before later symbol steps. Reject unknown bindings and incompatible row transitions.
- [ ] **E05 — Retained row families.** Reuse symbol, occurrence, binding, and value-path results with matching projections. Reject unavailable fields and unsupported transformations.
- [ ] **E06 — Terminal rules.** Exercise one required `EMIT` or `TRACE`. Reject missing terminals, `TRACE` inside `steps`, `RUN.output`, and incompatible TRACE outputs.
- [ ] **E07 — Finite syntax.** Cover 1000 EMIT steps, 999 pre-TRACE steps, excess steps, unknown fields, unknown variants, invalid numbers, and absent-versus-null defaults.
- [ ] **E08 — Wire presentation.** Compare compact and `verbose: true` documents. Preserve identity, qualification, continuation, recovery, and canonical result-schema admission.

The primitive operators are `EQUALS`, `NOT_EQUALS`, `STARTS_WITH`, and `ENDS_WITH`. Visibility values are `PUBLIC`, `PROTECTED`, `INTERNAL`, `PRIVATE`, and `LOCAL`.

## F — Output progress, budgets, retention, and freshness

**Setup:** Use the existing 1001-occurrence and paged-function fixtures. Freeze source, scope, output selection, and execution grants.

**Oracle:** Full ordered identities and evidence, with no lost or duplicated positions. **Negative control:** A complete final page cannot repair an incomplete producer.

**Evidence:** Every `RUN`, `RESUME`, and `READ_RESULT`, row cursor, evidence cursor, admitted grant, and native work counter.

- [ ] **F01 — Execution modes.** Exercise default and explicit `RUNNER_CONTROLLED`, plus `BOUNDED`. Reject `maxWorkUnits` in runner-controlled mode.
- [ ] **F02 — Independent limits.** Exercise time, work, results, bytes, and operator clamps separately. A larger allowance in one dimension cannot erase another limit.
- [ ] **F03 — Output continuation.** Drain issued `RESUME` tokens without a new semantic RUN. Assert unchanged question, authority, and producer qualification.
- [ ] **F04 — Retained pages.** Exercise `RETAIN`, `DISCARD`, omitted retention, and independent row/evidence cursors. Include evidence-only pages with zero rows.
- [ ] **F05 — Rejection evidence.** Drain retained evidence after `COMPLETION_UNPROVEN`. Keep `POLICY_REJECTED_EVIDENCE`, original coverage, obligations, and the original rejection.
- [ ] **F06 — Capacity and lifetime.** Exercise bounded storage limits, expired results, eviction, and unavailable retention in controlled fixtures. Keep original result facts on publication failure.
- [ ] **F07 — Handle separation.** Reject a cursor from another result, exchanged token families, a changed workspace, and an unrelated source owner.
- [ ] **F08 — Source-state movement.** Change source, imports, dependencies, or roots in a disposable copy. Reject stale retained output and stale `READ_SOURCE` authority.
- [ ] **F09 — Exact reacquisition.** Use a fresh RUN to reacquire an unchanged named declaration. Contrast changed identity and changed local-declaration files.
- [ ] **F10 — Owner retirement.** Restart an owned host or retire its project. Old handles cannot gain authority from a matching path or version.
- [ ] **F11 — Fact reuse.** Compare unchanged dependency snapshots with changed source, SDK, classpath, or compiler inputs. Distinguish reused facts from stale retained answers.

## G — Producer impact and representation evidence

**Setup:** Freeze exact producer invocations, enclosing and callable identities, whole-invocation ranges, requested sites, and reviewed models.

**Oracle:** Independent value paths and explicit forbidden paths. **Negative control:** Identical-looking calls and different object fields must keep separate provenance.

**Evidence:** `VALUE_PATHS`, each `IMPACT_WITNESS` section, model digests, source transfers, terminals, findings, and conserved site accounting.

- [ ] **G01 — Producer admission.** Exercise `IMPACT` with `KOTLIN_FORWARD_V1`. Contrast the complete invocation range with a called-name-only range and mismatched declaration evidence.
- [ ] **G02 — Value transfers.** Cover arguments, receivers, named locals, immutable constructor fields, property reads, nested calls, and matching returns.
- [ ] **G03 — Alternatives and termination.** Cover interface implementations, try/catch normal results, throws, and `finally` limitations. Static alternatives cannot select runtime behavior.
- [ ] **G04 — Invoked callback values.** Cover immutable captures, callback returns, discarded results, Unit coercion, and `Unit?`. Keep distinct wrapper invocations separate.
- [ ] **G05 — Unsupported transfers.** Cover mutable captures, unrelated anonymous owners, container payloads, collection selection, and opaque external calls. Keep obligations instead of invented paths.
- [ ] **G06 — Reviewed models.** Exercise `BOUNDARY` rules `CONTINUATION` and `TERMINAL`. Exercise `REPRESENTATION` rules `CONSUMER_EXPECTATION`, `ORIGIN`, `TRANSFER`, and `TRANSFORMATION`.
- [ ] **G07 — Model controls.** Contrast missing, matching, changed, and conflicting models. Cover `CONTRACT_COMPATIBLE`, `REPRESENTATION_PRESERVED`, domain limits, and model provenance.
- [ ] **G08 — Requested sites.** Cover `ARGUMENT`, `EXPRESSION_RESULT`, `LOCAL_BINDING`, `LOCAL_READ`, `PROPERTY_ASSIGNMENT`, and `RETURN`. Site identity cannot prove producer membership.
- [ ] **G09 — Witness completeness.** Read `PRODUCERS`, `MODELS`, `NATIVE_READS`, `READ_REJECTIONS`, `FINDINGS`, and `SITE_ACCOUNTING`. Preserve unmodeled representation and incomplete investigation status.

Model site kinds are `SERIALIZATION`, `PERSISTENCE`, and `EXTERNAL_SYSTEM`. Terminal meanings are `REVIEWED_DISPOSAL`, `REVIEWED_EXTERNAL_SINK`, and `REVIEWED_RETENTION`.

Representation positions are `ARGUMENT` and `RESULT`. Declaration-basis forms `LIVE` and `PUBLISHED` need their own admitted evidence, including rejected mismatches.

## H — Compiler diagnostics

**Setup:** Use a disposable corpus copy with one independently compiled clean file and one intentional Kotlin error.

**Oracle:** Exact diagnostic site, severity, message category, and declared coverage. **Negative control:** Empty incomplete output cannot pass the clean-file case.

**Evidence:** Exact `check_diagnostics` request, canonical response, continuation pages, source hashes, and separate compiler/build results.

- [ ] **H01 — Path scopes.** Exercise a file, directory, and workspace root. Reject absolute paths, traversal, globs, and package names used as paths.
- [ ] **H02 — Positive and empty controls.** Find the intended error, remove it in the disposable copy, and repeat. Keep scope and source-state changes explicit.
- [ ] **H03 — Diagnostic progress.** Exercise `maxDiagnostics`, execution grants, and same-basis `continuation`. Reject a changed path or stale continuation.
- [ ] **H04 — Coverage limits.** Contrast imports, generated roots, unsupported files, and incomplete enumeration. IDEA diagnostics do not prove a successful project build.

## I — Concurrency, cancellation, disconnect, and recovery

**Setup:** Use case-owned callers and controlled barriers. **Oracle:** Actual dispatch, cancellation, drainage, and owner settlement events.

**Negative control:** A timeout cannot count as cancellation settlement. **Evidence:** Connection IDs, read IDs, transport phases, native events, and subsequent admitted work.

- [ ] **I01 — MCP concurrency.** Hold one runner-controlled call, then issue another on that connection. Expect `MCP_REJECTED` with `INVOCATION_IN_PROGRESS`.
- [ ] **I02 — Workspace lanes.** Exercise hosted App Server queue admission, queue timeout, same-workspace serialization, and independent workspace lanes.
- [ ] **I03 — Controlled native waits.** Exercise dumb-mode and write-action waits through existing native fixtures. Cancel each wait and prove drainage before a fresh successful request.
- [ ] **I04 — Peer disconnect.** Disconnect a caller after native entry. Prove operation cancellation and connection release with actual owner evidence.
- [ ] **I05 — Source movement during reads.** Change the controlled prerequisite at a known stage. Keep the failed stage and reject stale evidence without a silent new query.
- [ ] **I06 — Uncertain writes.** Lose the response after write dispatch in an owned fixture. Keep recovery evidence and the write fence until source effects settle.
- [ ] **I07 — Process and session isolation.** Reject handles from another root, host generation, or unrelated result owner. Preserve separate simultaneous read correlations.

## J — Controlled source changes

**Setup:** Use a disposable copy, explicit exact targets, independent preimages, and a clean diff. Production framework checkouts remain read-only during this family.

**Oracle:** Exact intended source diff, receipt phases, resulting identity, and scoped diagnostics. **Negative control:** Rejected pre-write inputs preserve source bytes.

**Evidence:** `add_declaration` or `replace_body` request, preimage, change receipt, source effect, fresh reference, diff, and project assertions.

- [ ] **J01 — Add one declaration.** Insert a valid declaration into the selected existing file. Assert insertion ownership and unchanged surrounding source.
- [ ] **J02 — Replace one body.** Replace a named non-inline Kotlin function's complete block body. Keep its signature and all source outside the body.
- [ ] **J03 — Reject unsupported forms.** Cover expression bodies, inline functions, contracts, unsupported owners, malformed blocks, and trailing input.
- [ ] **J04 — Stale preimages.** Change the target after discovery. Reject the stale planned source effect or return precise recovery evidence for a later failure.
- [ ] **J05 — Follow-up authority.** Use returned `freshRef` after success. Contrast old source selectors and unrelated exact targets.
- [ ] **J06 — Source-effect recovery.** Exercise each supported failure phase through existing owning tests. Never infer rollback, no-write, or retry safety from a missing response.

The public write tools own their internal plan and authority sequence. The catalog does not expose a separate dry-run or multi-file transaction API.

## K — Logs, correlation, and debug visibility

**Setup:** Use the [log visibility scripts](LOG_VISIBILITY.md) with explicit installation, IDEA instance, and owned evidence directory. Record effective logger settings before a change.

**Oracle:** One known semantic request and one controlled rejection produce attributable events. **Negative control:** Missing or ambiguous records remain explicit gaps.

**Evidence:** Control logs, IDEA plugin logs, bounded exception frames, capture cursors, logger settings, hashes, and correlation results.

- [ ] **K01 — Log discovery.** Examine conventional Control and IDEA paths against the selected process. Reject ambiguous IDEA instances or unproved process-to-path mappings.
- [ ] **K02 — Debug activation.** Enable only source-backed Kast logger categories through the supported mechanism. Record the effective setting and restart need, if present.
- [ ] **K03 — One-request capture.** Mark the log position before the call. Capture only the bounded appended interval after it, including process stderr.
- [ ] **K04 — Exact joins.** Join `kast_transport_read` by `connectionId` and `readId`. Preserve distinct retries under one connection and never join by time proximity alone.
- [ ] **K05 — Semantic diagnostics.** Keep `kast_semantic_read`, transport phases, effective grants, counters, termination reasons, and bounded unexpected-failure frames.
- [ ] **K06 — Loss and rotation.** Exercise rotation, truncation, overflow, missing files, permission failures, and delayed writes. Keep valid prefixes and explicit gap reasons.
- [ ] **K07 — Live visibility.** Exercise bounded follow mode, user interruption, and multiple files. Separate stream health from semantic completion.
- [ ] **K08 — Restore and export.** Restore only settings changed by the campaign. Keep raw receipts private and export a bounded summary without source payloads or credentials.

A diagnostic `completed` outcome means the hosted operation returned. It does not prove complete semantic coverage.

The existing replay collector already owns bounded appended-log capture and timing joins. The log scripts must reuse that evidence model rather than infer a second authority.

## L — Management, recovery, and integration lifecycle

**Setup:** Use an owned disposable installation or declared local scope. **Oracle:** Exact ownership, source preservation, process identity, fence state, and finite lifecycle outcomes.

**Negative control:** An unrelated process or path cannot become an owned deletion target. **Evidence:** Management JSON, stderr stages, before/after inventories, and recovery paths.

- [ ] **L01 — Status and connections.** Exercise `kast status`, connect, disconnect, and client restart. Registration, loaded adapter, daemon state, and semantic readiness remain separate facts.
- [ ] **L02 — Stop and reinstall.** Exercise active-call retirement, live-Host rejection, shutdown fences, recorded-version reinstall, and subsequent fresh sessions.
- [ ] **L03 — Upgrade variants.** Exercise matching Control/Host upgrade, Control-only compatibility, Host-only replacement, and failed replacement recovery in an owned installation.
- [ ] **L04 — Force and uninstall controls.** Exercise dry-run and invalid-target rejection first. Run destructive success cases only in an explicitly owned disposable installation.
- [ ] **L05 — Workspace lifecycle.** Exercise supported present, inspect, release, and project-closure actions only through an admitted hosted catalog and authorized project scope.

## M — Model behavior and complete engineering tasks

**Setup:** Use the existing Pi evaluation controller, exact adapter digest, unchanged schemas, named model, effort, prompts, and fixed per-case budgets.

**Oracle:** External source witnesses and a rubric authored before inference. **Negative control:** A confident unsupported answer fails even when tool calls succeed.

**Evidence:** Proposed calls, admitted calls, real replies, final answer, qualifications, provider usage, and exact private transcript.

- [ ] **M01 — Selection discipline.** Give an ambiguous overloaded task. Assess scoped discovery, exact selection, preservation of references, and qualified handling of ambiguity.
- [ ] **M02 — Complete-use answer.** Ask for affected uses. Assess imports, aliases, companion qualifiers, unmatched sites, and explicit limits before an exhaustive claim.
- [ ] **M03 — Typed rejection answer.** Supply a callback rejection with known direct edges. Demand the useful facts and unresolved obligations without invented completeness.
- [ ] **M04 — Output recovery.** Supply a multi-page result. Assess only issued output continuations, no redundant RUN, fixed scope, and preservation of the initial outcome.
- [ ] **M05 — Received-result interpretation.** Resume an admitted saved result with zero new semantic calls. Keep provider delivery separate from native completeness.
- [ ] **M06 — Complete change task.** On a disposable copy, connect identity, scoped relations, exact change, diagnostics, and project assertions. Reject unsupported edits.
- [ ] **M07 — Tool visibility.** Exercise the selected Pi exposure and installed host extensions. A listed plugin does not prove discoverable tools or a successful semantic call.
- [ ] **M08 — Resource and stop policy.** Assert request counts, payload limits, cancellation, and rejection stops. Token estimates cannot prove a tokenizer or spend bound.

Static schemas, scripted providers, and native fixtures do not establish model behavior. A model sentence cannot establish independent source correctness.

## N — Scale and performance without weaker evidence

**Setup:** Freeze one source archive, oracle, host, artifact pair, grants, workload, and concurrency. Keep every warmup and repetition.

**Oracle:** Equal complete results, all qualifications, source ranges, omissions, and failures. **Negative control:** Run the same exhaustive exact-negative workload in each trial.

**Evidence:** Existing comparison receipts, wall time, first usable result, completion time, native counters, output bytes, and diagnostic capture costs.

- [ ] **N01 — Real-source workloads.** Reuse `replay-workloads --workload-profile KAST_SOURCE`. Admit artifact and source correspondence before comparison.
- [ ] **N02 — Dense output.** Reuse the frozen 1001-occurrence and paged-function controls. Include full output and evidence drainage in completion time.
- [ ] **N03 — Excluded noise.** Vary unrelated modules, names, and kinds one dimension at a time. Keep admitted source scope unchanged.
- [ ] **N04 — Cache and contention.** Label first-in-session, warm, and concurrent activity explicitly. Do not call the first invocation a cold-cache measurement.
- [ ] **N05 — Work versus latency.** Compare semantic work independently from elapsed time. Do not add overlapping native-stage clocks or include log waits as semantic work.

## Public contract inventory

This inventory binds the checklist to the installed catalog captured from Kast 0.0.3708. Refresh it when the catalog changes.

| Contract family | Exact variants | Checklist owners |
|---|---|---|
| Tools | `query_symbols`, `check_diagnostics`, `health_check`, `add_declaration`, `replace_body` | A–J |
| Query actions | `RUN`, `RESUME`, `READ_RESULT`, `READ_SOURCE` | B, F |
| Query sources | `SEARCH_DECLARATIONS`, `SEARCH_TEXT`, `ALL_DECLARATIONS`, `AT_LOCATION`, `SYMBOL_REFS`, `RESULT`, `IMPACT` | B, G |
| Transformations | `WHERE`, `EXPAND_RELATION`, `WALK`, `DISTINCT_SYMBOLS`, `PROJECT_BINDING`, `CONCAT`, `INTERSECT`, `UNION`, `DIFFERENCE`, `JOIN` | C, E |
| Terminals | `EMIT`, `TRACE` | C08, E06, E07 |
| Non-null outputs | `SYMBOLS`, `OCCURRENCES`, `TRAVERSAL_RECORDS`, `BINDING_ROWS`, `VALUE_PATHS`, `IMPACT_WITNESS` | B08, C09, E04, G |
| Discovery scopes | `DIRECTORY`, `PACKAGE`, omitted/null scope | B03, B07 |
| Expansion and impact domains | `WORKSPACE`, `RETAINED_SEED`, `SOURCE_DOMAIN` | C02, G07 |
| Source policies | `PRODUCTION_ONLY`, `TEST_ONLY`, `PRODUCTION_AND_TEST` | B03, C02 |
| Generated-source policies | `EXCLUDE`, `INCLUDE` | C02, H04 |
| Execution modes | `BOUNDED`, `RUNNER_CONTROLLED`, omitted/null mode | F01, F02 |
| Retention | `DISCARD`, `RETAIN`, omitted/null retention | F04–F07 |
| Relations | All seven named in C01 | C01–C09 |
| Walk strategies | `BREADTH_FIRST`, `BOUNDED_FAN_OUT` | C07 |
| Predicates | `VISIBILITY`, `PRIMITIVE` with their declared fields/operators | E01 |
| Join modes | `INNER`, `SEMI`, `ANTI` | E03, E04 |
| Source text | `COMPLETE`, `NONE`, `WINDOW`, `EXCERPT` | B09, B10 |
| Impact flow | `KOTLIN_FORWARD_V1` | G01–G09 |
| Impact models | `BOUNDARY`, `REPRESENTATION`, and all rule variants | G06, G07 |
| Impact witness sections | All six named in G09 | G09 |

A new discriminator, enum value, response variant, or lifecycle command needs an assigned case before the campaign claims contract coverage.

The inventory covers the public tool catalog. Lower-level operations need separate selection from the installed operation registry and their owning tests.

## Recorded baseline and open holes

The attached real-repository report records 46 core queries: 31 complete results and 15 explicit rejections.

Ten diagnostic queries added three complete discoveries and seven incomplete relations. The combined total is 56 queries, with 34 complete results and 22 rejections.

Twenty-two artifact assertions passed: eleven direct witnesses, four exhaustive empty controls, two direct-call controls, and five overload inventories.

These results support selected B, C, D, and K cases. They do not complete those families or the checklist.

| Open hole | Recorded symptom | Next decisive case |
|---|---|---|
| Source-library K2 search | Ktor route caller/reference queries and selected Ktor/coroutines traces returned `INCOMPLETE_EXECUTION` with `KaBaseIllegalPsiException` | C05 with a minimal crossing, contrasting source-only control, and K04/K05 correlation |
| Unsupported call owners | Spring `RouterFunctionDsl.build` and Kast `RefinementDefinition.zipAccumulating` kept partial evidence with `RELATION_CALL_OWNER_UNSUPPORTED` | C01 and D05 with explicit lexical-versus-direct ownership |
| Full native pin admission | The Spring pin rejected linked source entries | A02/A03 with an admitted source inventory or an explicitly narrower capture claim |
| Agent reload | No Codex restart occurred in the recorded campaign | A07 with a fresh client and its actual loaded adapter |
| Public flows without evidence | No full inventory of composition, impact, diagnostics, mutation, lifecycle, or model tasks ran in that campaign | Parameterized cases E–N |

The original source oracle misclassified one deferred lambda call as a direct named callee. Its correction remains separate from the recorded original.

Do not use an edited oracle to erase a failed expectation. Review each oracle against source ownership before the next campaign.

## Existing runners and authoritative sources

Reuse the existing owners. Add new case manifests and narrow assertions to them when implementation becomes necessary.

- [Public input schema](https://github.com/amichne/kast/blob/b668498eb44248c45f818301e46df3ec25079989/app-server/src/main/resources/io/github/amichne/kast/appserver/query/tools.schema.json) owns request variants and admission metadata.
- [Semantic reproduction runner](https://github.com/amichne/kast/blob/b668498eb44248c45f818301e46df3ec25079989/experiments/host-observation/reproduce_semantic_queries.py) owns installed sessions, replay, pins, output drainage, and comparisons.
- [Semantic reproduction procedure](https://github.com/amichne/kast/blob/b668498eb44248c45f818301e46df3ec25079989/experiments/host-observation/SEMANTIC_REPRODUCTION.md) describes fixtures, independent oracles, immutable archives, and native evidence.
- [Log and timing procedure](https://github.com/amichne/kast/blob/b668498eb44248c45f818301e46df3ec25079989/experiments/host-observation/README.md#semantic-replay-timing) defines bounded collection, exact joins, and overlapping clocks.
- [Native hosted fixtures](https://github.com/amichne/kast/blob/b668498eb44248c45f818301e46df3ec25079989/runtime/hosted/NATIVE_FIXTURES.md) define wait, disconnect, and early-rejection evidence.
- [TRACE suite](https://github.com/amichne/kast/blob/b668498eb44248c45f818301e46df3ec25079989/experiments/host-observation/trace-completeness/README.md) supplies frozen source/oracle manifests and a private native-host path.
- [Pi evaluation controller](https://github.com/amichne/kast/blob/b668498eb44248c45f818301e46df3ec25079989/experiments/host-observation/PI_EVALUATION.md) separates deterministic policy, SDK projection, native replies, and actual inference.
- [Callback contracts](https://github.com/amichne/kast/blob/b668498eb44248c45f818301e46df3ec25079989/docs/public/reference/callbacks.mdx), [program tracing](https://github.com/amichne/kast/blob/b668498eb44248c45f818301e46df3ec25079989/docs/public/reference/program-tracing.mdx), and [composition](https://github.com/amichne/kast/blob/b668498eb44248c45f818301e46df3ec25079989/docs/public/reference/composition.mdx) define semantic distinctions.
- [Limits](https://github.com/amichne/kast/blob/b668498eb44248c45f818301e46df3ec25079989/docs/public/reference/limits.mdx), [staleness](https://github.com/amichne/kast/blob/b668498eb44248c45f818301e46df3ec25079989/docs/public/reference/staleness.mdx), and [change contracts](https://github.com/amichne/kast/blob/b668498eb44248c45f818301e46df3ec25079989/docs/public/reference/change.mdx) define rejection, authority, and recovery.

The examined checkout was clean at `b668498eb44248c45f818301e46df3ec25079989`. The installed release source was `e7075cbb0cb42bede2976136182c728f3ebece82`.

The installed catalog has schema version 17 and SHA-256 `2243fa9c1303b49d517a3da135079e7d64bb283b53cedf3bc6cdd9a479f37c2e`.

Source inspection found documentation drift: `RELATION_WORK_REDUCTION` maps to contract 17 in the runner, while its procedure still states 14.

Use the source gate and actual catalog for admission. The other qualification slices keep their own versions and owners.

## Campaign procedure and stop conditions

1. Select a tier, corpus, exact cases, parameter values, and independent oracle.
2. Capture source identity, installed artifacts, loaded Host, catalog, and effective limits.
3. Resolve declared prerequisites before measurements.
4. Run the smallest positive case and its nearest negative control.
5. Preserve each failure before a retry or a changed prerequisite.
6. Run the eligible corpus variants with fresh issued handles.
7. Drain output with the existing runner and preserve all original qualifications.
8. Correlate logs by exact IDs and record capture gaps.
9. Score product behavior separately from model delivery and harness status.
10. Restore campaign-owned settings and export the bounded report.

Stop a case when its oracle passes, a declared stop occurs, or authority becomes stale. Do not raise grants or change scope inside a trial.

A corrected prerequisite permits a new trial with a new identity. Keep the earlier blocked or failed trial.

A campaign passes only when every selected necessary case passes, including its controls and evidence admission. Missing necessary evidence prevents a pass.

For a release claim, assign every public contract variant to an executed case or an explicit unsupported-surface statement. Keep cases without results visible.

Do not claim exhaustive Kotlin behavior from this checklist. New source patterns, response variants, and real failures extend the case inventory through their existing owners.
