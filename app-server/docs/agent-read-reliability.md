# Agent read reliability: requirements and implementation plan

Status: implementation in progress. Transport, schema, relation/query budgets and page fitting have local evidence below. Remaining cross-operation work and installed gates must complete before this is a reliability release.

Baseline: `24a7e92d09a31624cf11e4f596c64d3a99e9a995`, inspected on 2026-09-14. Both `main` and `v0.40.0` pointed to this commit. Its existing checkpoint and traversal-timing work is therefore part of the assessed release, not a subsequent fix.

## Objective and boundary

Make the existing compiler-grounded read tools predictable for unattended agents: every accepted call either publishes sound, schema-valid evidence with an explicit completion/recovery state, or a finite rejection. Budget exhaustion must not discard safely publishable progress merely because a wider page was requested.

Keep declaration queries, relation-occurrence reads, graph traversal, and source reads. Preserve the existing IDE as semantic authority, original-owner epoch validation, configured operator ceilings, bounded storage, and exact references. Do not introduce another query engine, semantic index, outcome authority, background project import, automatic IDE repair, or source-mutation behavior.

### Evidence ledger

| Evidence | Status and consequence |
| --- | --- |
| 4 failures in 156 concurrent reads; all four serial retries succeeded | Supplied release-assessment observation. Reproduce at the installed transport boundary. Serial retry is diagnostic evidence, not a fix or proof of a particular race. |
| 66 behavioral assertions passed in a four-operation follow-up | Supplied observation. Preserve the tested semantic guarantees, but do not treat the count as a portable test suite. |
| Query pages of 107 + 43, source/relation pagination, resumable traversal | Supplied observations, consistent with existing continuation implementations. Do not rebuild those mechanisms. |
| Relation limit 100 rejected with `RESULT_TOO_LARGE`; limit 10 paginated | Supplied observation. Require automatic page fitting, not caller guesswork. |
| Two-hop caller traversal rejected with `BUDGET_EXCEEDED`; broad discovery qualified without a cursor | Supplied observations. Distinguish output pagination from genuinely resumable upstream work. |
| Stale-reference response/schema mismatch | Supplied observation. Captured payload and schema location are recorded in the local continuation evidence below; the missing canonical reasons are now covered without weakening schema validation. |
| Class discovery classifies every `KtClassOrObject` as `CLASSLIKE`; scoped enumeration accepts it before descending | Source-confirmed subtype problem: enum entries reach class candidate admission. The starter patch addresses this boundary. |
| `HostedEndpointService` serves each accepted connection before accepting the next; `HostedQueryExecutor` converts timeout to rejection | Source-confirmed boundaries, not a demonstrated root cause of the four transport failures. Instrument and reproduce before changing concurrency policy. |

The two raw `REPORT.md` files were subsequently read locally; the original failures remain supplied observations. The independently executed matrix and its exact commit are recorded below. Whether higher configured deadlines complete the originally failing caller traversal remains unverified.

## Requirements

### R1. Concurrent transport reliability and observable admission

An admitted concurrent-read workload must not lose, mix, truncate, or misattribute replies. Each connected request gets at most one terminal response; overload receives an existing or newly specified finite admission rejection rather than a broken frame. An intentionally disconnected caller cannot cancel unrelated work or retire a healthy listener. Peer cancellation must drain its own operation before releasing admission.

Record bounded, structured observations at accept, request-read, semantic admission, execution, encoding, and reply-write boundaries. Retain a correlation identifier, finite outcome/cause, observed queue/execution durations, and byte counts. Do not log source text, full tool payloads, credentials, or opaque handles.

First identify the failing boundary with a barrier-controlled transport test and an installed-client replay. Preserve current semantic serialization until tests establish which state can safely be concurrent. A bounded connection-admission layer may be appropriate; unbounded `launch` per connection and blanket client retries are not acceptable substitutes for diagnosis. Keep mutation approval and mutation serialization unchanged.

### R2. Exact failure/schema parity

Capture the actual stale-reference response, public tool identity, installed catalog/schema version, and referenced schema path. Validate both the semantic payload and outer tool envelope against the schema shipped by the same build.

Exercise every finite rejection branch relevant to the four read tools, including stale/foreign references, stale/expired continuations, invalid inputs, budget rejection, and transport rejection. Preserve required discriminators, defaults, details, and transitive schema definitions. Fix the owning DTO/projection/schema boundary, not an unrelated schema copy. Do not accept arbitrary strings or turn rejection into an empty success to obtain parity.

An unknown reference must never be resolved by spelling, silently refreshed, or retried against a different snapshot. Recovery must direct the caller to reacquire authority explicitly.

### R3. Per-call execution budgets under operator ceilings

Expose one optional `execution_budget` control on the four read surfaces and query-backed search conveniences. Proposed fields are `max_elapsed_ms`, `max_work_units`, `max_results`, and `max_returned_bytes`; confirm names through the authored public contract before generating projections. Existing omitted/null behavior must retain configured defaults.

At the boundary, refine numeric inputs using the existing positive domain values. Distinguish configured defaults from configured hard ceilings. For each dimension, admit:

```text
effective = min(requested-or-default, operator ceiling, applicable transport capacity)
```

Return effective limits and any clamping reason. Reject non-positive, overflowing, unsupported, or structurally impossible budgets before semantic work; never ignore a supplied limit. Define operation-specific work units and result units. `max_results` limits page output, not the semantics of an explicit query `take` or graph depth.

Use one immutable admitted budget through public lowering, canonical request admission, hosted execution, domain adapters, and encoding. Existing `HostedSemanticBudgets` must consume that admitted value rather than independently rebuilding defaults. Keep caller allowances separate from checkpoint-store capacity and operator policy.

Coordinate the deadlines end to end:

```text
cooperative semantic stop
  < hosted hard deadline (revalidation + checkpoint/publication + encoding reserve)
  < client/provider deadline (bounded queue + IPC allowance)
```

Use monotonic elapsed observations; do not serialize raw process-local clock values across IPC. Reuse and extend existing deadline admission/reserves. Admission must account for time already spent, and metadata must distinguish queue time from semantic time. Raising a semantic allowance cannot leave an earlier transport deadline unchanged. Actual observed overruns remain evidence and must not be clipped to the allowance.

### R4. Cooperative partial completion

Check limits before starting another bounded unit of provider work and between completed units. Detach and retain only facts proven under the admitted authority. Stop early enough to validate the epoch, establish a checkpoint, fit the encoded page, and publish before the hard deadline.

Reuse the query pipeline checkpoints, hosted encoded-output suffixes, source/relation continuation stores, and traversal frontier state already present. A cursor for a retained output suffix does not establish that an interrupted index scan can resume. For upstream discovery that cannot safely checkpoint, preserve sound results as a terminal partial with a finite reason; do not invent an index offset or advertise a resumable scan.

A relation page that exceeds the byte allowance must publish a fitting proven prefix and retain the unreturned work when a safe checkpoint exists. Measure the full encoded response, including metadata and cursor, against its transport cap. For a single indivisible oversized item or an envelope below its minimum encodable size, return a finite, actionable outcome. Do not loop forever returning an empty page with an unchanged cursor and unchanged budget.

A graph prefix must include its proven nodes/edges, coverage, and unfinished frontier/partial expansion state. Never imply that a partial graph is exhaustive or that reachable code will break.

The hard timeout remains a final containment boundary. Do not change its exception handler to manufacture partial success. Partial publication must happen cooperatively from a validated, detached checkpoint. Caller cancellation, IDE disposal, freshness failure, and budget exhaustion remain distinct conditions. Do not publish canceled/restarted IntelliJ read-attempt state or retain live PSI/K2 objects in a cursor.

### R5. Explicit completion and continuation semantics

Retain the canonical `Complete` / `Qualified` / `Rejected` outcome authority. Strengthen qualified progress with closed resumable/terminal variants and derive public completion metadata from that authority; do not add an independently mutable success flag.

| Public meaning | Required evidence |
| --- | --- |
| Complete | Requested semantics exhausted; no unfinished work or unresolved coverage gap. |
| Partial/resumable | Sound published prefix, qualification, valid checkpoint, exhausted dimension/cause, effective budget, and a supported next action. A zero-result page is allowed only with advancing work or an explicit required budget change. |
| Partial/terminal | Sound published prefix and finite explanation of why no safe continuation exists. |
| Rejected | No publishable result for this call; finite cause and recovery direction. |

Continuation presence alone is not proof of completeness or coverage. Preserve omissions and qualification evidence across all pages. Keep unknown/unmeasured work explicitly unknown; do not fabricate counts.

### R6. Resume with larger execution allowances

Bind a continuation to the original workspace, host lifetime, epoch/snapshot, normalized query, projection, scope, relationship selection, traversal strategy, and effective semantic depth. Bind implicitly defaulted semantic choices at the initial request too.

Permit changed time/work/page-result/page-byte allowances within current operator ceilings without changing that semantic identity. Do not include mutable execution allowances in the semantic fingerprint; do not remove query-defining limits such as `take` or traversal depth from it. Re-admit each grant on resume. A higher allowance is permission to do more of the same work, not permission to expand scope.

Specify token consumption/replay policy. For read continuations, a lost reply must not silently consume the only checkpoint; replay must be deterministic or return a finite documented state. Preserve host ownership, bounded retained bytes/entries, expiry, and retirement. Restart or epoch change invalidates unsupported continuations; this work does not promise durable cross-restart replay.

Acceptance compares the ordered/identified results of low-budget pages followed by a higher-budget resume against an exhaustive high-budget reference run on one unchanged fixture. Compare occurrence identity for relation reads, declaration identity for distinct queries, and graph node/edge identity plus coverage for traversal. Do not incorrectly deduplicate two source occurrences into one fact.

### R7. Class discovery excludes enum entries before capacity and refinement

Recognize `KtEnumEntry` before its `KtClassOrObject` superclass. A proven enum entry is outside the requested supported declaration kinds, not an unknown PSI failure. Keep unsupported classification distinct so incomplete evidence still qualifies results.

Apply this rule in shared candidate-kind admission and scoped enumeration, before candidate capacity or exact refinement. Continue descending into excluded enum-entry bodies: excluding a container does not exclude eligible members. Retain ordinary classes, objects, enum classes, and nested declarations. Do not add enum-entry support to the public compiler-kind algebra in this patch.

### R8. Operation names and scope documentation, after reliability

Keep `query_symbols` for declaration pipelines, relation reads for individual occurrences/use sites, traversal for graph reachability, and `source_read` for saved source/structure. Keep search conveniences lowering through the existing query engine.

Rename the presentation `semantic_query` to `read_relations` and `impact_analyze` to `traverse_relations` only after the reliability gates pass. Retain the same canonical operation identities and implementations. Generate schemas, installed catalogs, CLI bindings, help, and public references from their existing owners. Use a documented compatibility interval for old input names, advertise only the preferred names in new catalogs, and advance the appropriate catalog/contract version when old persisted catalogs are incompatible.

Document root-relative paths, direct versus descendant containment, package versus directory semantics, source sets, generated/library policy, subject-selection scope versus expansion-destination scope, page limits versus semantic limits, and declaration versus occurrence cardinality. State that bounded reachability is evidence for impact assessment, not a breakage or test-selection guarantee.

Fix stderr noise and broken configuration help afterward; do not combine those changes with timeout, admission, or cursor semantics.

## Implementation map

Paths are relative to the repository root. These are existing owners to change, not permission to create parallel mechanisms.

| Work | Existing change boundary | Required change |
| --- | --- | --- |
| Transport | `runtime/hosted/.../HostedEndpointService.kt`; `workspace/intellij-read/.../hosted/HostedQueryExecutor.kt`; App Server provider/host adapters | Correlated stage evidence, deterministic failure reproduction, then bounded admission/failure isolation at the demonstrated boundary. |
| Public budgets and names | `app-server/src/main/resources/io/github/amichne/kast/appserver/query/tools.schema.json`; `packaging/generate-public-query.py`; `PublicToolMapping.kt`; `protocol/registry/.../CanonicalAgentToolDefinitions.kt` and `PublicToolIdentity.kt` | One authored input grammar, generated projections, canonical lowering, explicit compatibility. |
| Budget admission and deadlines | `kernel`; `workspace/intellij-read/.../hosted/HostedQueryService.kt` and `HostedQueryExecutor.kt`; `runtime/hosted/.../HostedCanonicalQuery.kt` (`HostedSemanticBudgets`) | Refine requested/default/ceiling values once; coordinate remaining time and serialization reserves across the transport. |
| Continuations and fitting | `query/service`, `query/protocol`; `runtime/hosted/.../HostedCanonicalQuery.kt` and `HostedQueryContinuations`; `source/intellij`, `relation/service`, `relation/intellij`, `traversal/service` | Reuse checkpoints, separate semantic identity from grants, preserve coverage, and fit pages before hard rejection. |
| Stale/schema parity | `protocol/contract/.../CanonicalReadOperationModels.kt`; packaged hosted schemas; `cli/.../bootstrap/HostedRejectionSchemas.kt`; `cli/.../LiveReadOutputSchemaTest.kt` | Locate the actual mismatch; test emitted envelopes against the same build's independent schema. |
| Enum-entry admission | `symbol/intellij/.../discovery/IntellijDiscoveryProjection.kt`; `IntellijDiscoveryConstraintAdmission.kt`; `IntellijScopedDeclarationEnumeration.kt` | Explicit enum-entry classification, finite exclusion, and continued member traversal. |
| Installed proof | existing `packaging` and App Server native acceptance harnesses | Deterministic fixtures plus real installed transport, schema, epoch, and continuation validation. |

Full source anchors: [endpoint](../../runtime/hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/HostedEndpointService.kt), [executor](../../workspace/intellij-read/src/main/kotlin/io/github/amichne/kast/workspace/intellij/read/hosted/HostedQueryExecutor.kt), [budget projection](../../runtime/hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/HostedCanonicalQuery.kt), [kind classifier](../../symbol/intellij/src/main/kotlin/io/github/amichne/kast/symbol/intellij/discovery/IntellijDiscoveryProjection.kt), [constraint admission](../../symbol/intellij/src/main/kotlin/io/github/amichne/kast/symbol/intellij/IntellijDiscoveryConstraintAdmission.kt), [scoped enumeration](../../symbol/intellij/src/main/kotlin/io/github/amichne/kast/symbol/intellij/IntellijScopedDeclarationEnumeration.kt), [public contract](../../knowledge/contracts/public-tools.md), [semantic read ownership](../../knowledge/modules/semantic-reads.md).

## Delivery order and exit criteria

| Slice | Scope | Exit criterion |
| --- | --- | --- |
| 0 — evidence and starter fix | Capture current-build fixture inputs, emitted failures, and schema identities. Land the independent enum-entry exclusion and admission tests. | Baseline failures are named and replayable; enum entries cannot consume class candidate capacity; unknown PSI remains qualified. Native enumeration/refinement still needs its installed gate. |
| 1 — transport and schema | Reproduce concurrent loss at a controlled boundary; fix only that owner; correct actual stale/schema mismatch. | No transport failures in the proposed 156-call admitted replay; overload and disconnect cases produce expected finite outcomes; every captured response validates against its own build's schema. |
| 2 — one vertical budget/partial slice | Implement caller budgets through `read_relations`' current `semantic_query` presentation first, including byte fitting and resume with larger grants. | Low-budget pages plus resume equal the reference occurrence sequence, including duplicate call sites; effective limits and exhaustion cause are visible. |
| 3 — remaining cooperative execution | Apply the same admitted-budget boundary to query, traversal, and source owners; preserve or explain non-resumable upstream discovery. | Work/time/result/byte tests pass independently; two-hop traversal returns a proven prefix when safely available; terminal partials explain their limitation. |
| 4 — installed qualification and naming | Run the installed matrix, then rename presentations and regenerate documentation/catalogs. | Original semantic guarantees remain; compatibility policy is tested; no assertion of robustness depends on serial retries or manually coordinated client/IDE deadlines. |

The relation slice intentionally precedes broad upstream resumability: it exercises the entire contract with an existing pageable provider and the reported byte-limit failure before extending more complex graph/pipeline state.

## Deterministic verification matrix

1. **Concurrent transport:** use explicit barriers, not sleep-based scheduling, to start 12 clients with 13 read calls each (156 total; this is a proposed new replay shape, not the original unknown concurrency pattern). Validate correlation, one full response per call, schema parity, and unchanged semantic results. Repeat with one blocked client, one disconnected peer, malformed input, and deliberate admission saturation. Record first attempts separately from diagnostic serial retries.
2. **Stale authority:** obtain a reference, make one controlled fixture edit through its normal lifecycle, await the observed epoch transition, then reuse it. Require a finite schema-valid rejection and verify a newly acquired reference works. Repeat for a continuation and a foreign workspace. Do not bypass freshness to make the test pass.
3. **Each budget dimension:** inject clocks and controllable providers into service tests. Exhaust time/work/results/bytes separately and at boundaries before the first unit, after a proven prefix, during final encoding, and at the hard deadline. Assert no extra provider work after exhaustion, reserved publication headroom, preserved coverage, and no false completion.
4. **Resume:** increase only execution allowances. Require equality with an unchanged-snapshot reference run. Reject changed query/scope/strategy/depth and stale/foreign/expired tokens. Exercise replay after a lost response, bounded store exhaustion, and no-progress/single-oversized-item cases.
5. **Semantic cardinality:** fixture function A calls B twice. Relation reads retain two call occurrences; explicit declaration deduplication returns one caller; traversal preserves valid edge evidence and handles a cycle; source reads preserve text/ranges/child pagination.
6. **Enum entries:** use the fixture below with exact, fuzzy, and ALL/scoped class discovery. Entries do not appear as class candidates, ordinary/nested classes survive, eligible entry-body members remain traversable, and returned class candidates refine without this subtype failure. Add enough excluded entries to prove they do not exhaust eligible candidate capacity.

```kotlin
enum class Mode {
    ACTIVE { override fun act() = target() },
    PASSIVE;
    open fun act() = Unit
    class Nested
}
fun target() = Unit
```

Run focused repository checks for the starter patch:

```sh
./gradlew :symbol:intellij:test --tests '*IntellijDiscoveryKindAdmissionTest'
./gradlew verifyJsonContracts knowledgeImpact verifyKnowledgeBase
```

Then use the existing CI product verification path, which runs `python3 .github/scripts/ci/verify-checks.py` on its configured macOS/JDK toolchain. Do not add a new all-purpose test framework or bypass required repository checks. Installed acceptance is a separate gate from unit tests and must retain the exact plugin/build/IDE/JDK/configuration identity.

## Historical starter patch and verification status

The initial starter patch changed only enum-entry classification/admission/scoped enumeration and adds focused admission tests. It does **not** implement per-call budgets, change concurrency, fix an unidentified schema mismatch, rename tools, or claim the original stress cases now pass.

A local standalone Kotlin 1.9.0 PSI probe parsed the fixture above. The baseline class-like test admitted `[Mode, ACTIVE, PASSIVE, Nested]`; excluding `KtEnumEntry` retained `[Mode, Nested]` while retaining both `act` declarations and `target` during member traversal. This supports the subtype diagnosis only: it is not compilation of Kast or validation against Kast's pinned IntelliJ/Kotlin distribution.

At that initial checkpoint, local repository compilation was blocked by unavailable GitHub network resolution in the execution container; the available local JDK is 21, while repository CI explicitly provisions JDK 25. That initial checkpoint did not establish repository or native qualification. Subsequent evidence follows.

## Platform constraints informing the design

Kotlin's [`withTimeout` contract](https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines/with-timeout.html) documents cooperative cancellation and the race in which a timeout can prevent delivery of a computed result. Therefore progress must be owned and published before the hard deadline, not reconstructed from a caught timeout.

JetBrains' [coroutine read-action contract](https://plugins.jetbrains.com/docs/intellij/coroutine-read-actions.html) permits write-allowing read attempts to be canceled and restarted. Keep read actions short, use the platform's cancellation checks, and publish only detached, revalidated facts outside abandoned attempts. Existing lifetime and final-publication checks must remain intact.

## Local continuation evidence (2026-09-14)

The original reports and captured results are now available under
`~/Downloads/kast-0.40.0-stress-2026-09-14`. Inspection confirms the original
concurrency patterns were 60 mixed requests at concurrency 6 and 96 class reads
at concurrency 16. The stale query document is a `query.run` rejection with
`type=reference-rejected`, `path=from.values[0]`, and `reason=stale-authority`.
The installed schema omitted three existing canonical reference reasons.
`LiveReadOutputSchemaTest` now checks every canonical reference rejection
against the installed output envelope; its new regression failed before the
schema correction and passed afterward. Unknown reasons remain rejected.

A JVM native-socket test reproduced refusal while dispatch was occupied:
only 2 of 12 barrier-started clients connected with the original backlog of one.
All 12 connect with the bounded configurable backlog. The extracted listener
now serves bounded concurrent frames while preserving serialized semantic and
mutation dispatch. Native tests complete 156 identified replies with a blocked
frame, verify finite saturation rejection, and prove disconnected dispatch drains
before the next request enters. These are transport fixtures, not installed
compiler qualification or proof that every original failure had this cause.

JDK 25 is available locally. The starter enum tests, hosted transport tests,
installed output-schema tests, JSON contract guard, knowledge impact, and
knowledge validation have run successfully. Broader checks and installed
qualification remain separate gates. R3–R6 and R8 are not yet implemented by
these changes; no release-completion claim follows from this evidence.

### Installed acceptance after transport and fixture corrections

Commit `c3b185662` passed the installed `hostedChangeAcceptance` harness on
2026-09-14 with the pinned IDEA 262 distribution and JDK 25. The private report
`/tmp/kast-agent-read-native-c3b185662.json` records 116 CLI/provider read cases,
156/156 schema-validated concurrent first attempts, zero serial retries, and the
full native mutation, recovery, owner restart, and undo workflow. The existing
harness reported `releaseQualified=true`; that is qualification of its matrix,
not a claim that all requirements in this document are finished.

The earlier run at `20e6eb876` passed both read gates but failed the plan-invariance
check. Its retained IDE log showed a Gradle cache VFS refresh after reimport,
between plan epoch 6 and read epoch 7. Production freshness rejection was retained.
The fixture now waits for completed import before draining refresh, retains the
pre-refresh import generation, and rejects evidence from an earlier generation.
Its regression failed before the correction and passes afterward. Native plan
evidence now distinguishes source changes, authority changes, and both.

Query/search propagation and encoded output fitting have subsequent focused
checks for public admission, host grants, retained identity, independent output
limits, full encoded metadata, and preservation of known failures across pages.
Legacy query null-control rejection remains intact. Schema reuse keeps the full
provider catalog within its existing byte cap. Full source/traversal fitting, stronger closed
completion metadata, remaining resume/native enum tests, installed overload and
disconnect variants, and the compatibility naming work remain release gates.
No requirement in R1–R8 is waived by the matrix above.

Commit `f8359fe93` passed the same installed harness after source continuation byte/TTL bounds, installed configuration registration, source/traversal input admission, and cooperative native source accounting. Report `/tmp/kast-agent-read-native-f8359fe93.json` records 116/116 read cases and 156/156 concurrent first attempts with zero retries, plus all native mutation/recovery cases. This remains evidence for that committed matrix. Later source/traversal budget-report projections require their own current-build validation; the remaining specification gates are not waived.

### Source encoded page fitting

Source hosted encoding now reuses the detached output suffix owner. A full encoded
page over its caller byte/result allowance publishes a fitting nonempty entity
prefix after retaining the remainder. Prefix and suffix preserve snapshot, text,
known minimum, original qualifications, and any upstream source cursor. Output
cursors are independently bounded by `QUERY_CONTINUATION_*`; native source cursor
retention remains governed by `SOURCE_CONTINUATION_*`. Neither is unbounded.

`HostedSourceResponseTest` failed with `Oversized` at RED `08e51ef4e`, then passed
with fitting bytes, ordered entity conservation, preserved terminal coverage and
non-consuming replay. Runtime, protocol contract/wire, source/traversal installed
budget-schema tests, JSON, architecture and knowledge checks passed. Generated
references and App Server checks passed (the external raw-display schema test
still requires the installed harness). This is local encoding evidence; it does
not establish the remaining source work-buffer, traversal, completion-state,
finite rejection parity, or installed matrix requirements.
