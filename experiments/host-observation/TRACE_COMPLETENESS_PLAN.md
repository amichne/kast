# Complete the two remaining TRACE cases

Status: **Proposed**. Written 2026-10-08 for an implementer and reviewer continuing
the enterprise-feedback work. This document plans validation and implementation;
no product code or executable tests were changed or run to produce it.

## Working card

- **Goal and acceptance:** Complete the scoped static TRACE questions for the
  selected Ktor `Attributes` and coroutine anonymous-collector shapes. Every
  implementation, override, caller and required callback connection must have
  compiler-grounded evidence. Preserve strict rejection and useful retained
  facts whenever an obligation remains unproven. Completion means the original
  scoped question succeeds against an independent source oracle, not merely that
  a smaller query succeeds or a rejection is handled correctly.
- **Known:** The prior candidate retained 29 Ktor rows and 20 coroutine rows with
  explicit proof gaps. Its build and recovery assertions passed. The broad TRACE
  completion objective remains unmet. See the [validation record](ENTERPRISE_CACHE_VALIDATION.md#live-compiler-litmus).
- **Inferred:** External callback admission and anonymous declaration projection
  are the main remaining boundaries. Current source contains matching rejection
  paths, but that does not establish the complete cause of every prior omission.
- **Unknown:** Which existing compiler observations can discharge the external
  callback obligation, and which anonymous identity transitions need extension.
  Resolve these before selecting a new representation or broadening a schema.
- **Smallest faithful tests:** One resolved `also` callback and one anonymous
  `FlowCollector` implementation, each with independently specified source
  locations, ownership and edges. Include a shadowed callback function and two
  distinct same-shaped anonymous objects as controls.
- **Widening gate:** Pure admission tests establish rules; actual K2 resolution
  establishes compiler facts; a disposable imported IDEA project establishes
  public TRACE, retention and freshness composition. No earlier layer substitutes
  for the next claim.
- **Stop condition:** If authority, identity or coverage cannot be proved, retain
  the finite cause and mark that case incomplete. Do not remove the obligation,
  narrow the question, increase a grant or alter an oracle to manufacture success.

## Evidence and scope

The baseline report describes a working-tree candidate based on
`0e69f48053328941bd94ecf8a3ea483fff79d4fb`, not a committed implementation at that
SHA. Its archive and changed-file hashes are recorded under
[evidence identity](ENTERPRISE_CACHE_VALIDATION.md#evidence-identity-and-limits).
Source inspection for this plan used the current uncommitted task checkout.

| Evidence | Status and authority | What it establishes |
| --- | --- | --- |
| Full build, guards and selected native litmus | **Passed, inherited** from the validation record | The assertions described there; not complete Ktor/coroutine TRACE. |
| Ktor broad TRACE | **Failed completion, inherited** | Enumeration completed; required callback proof rejected with `CALLBACK_GRAPH_UNPROVEN / OUTSIDE_DOMAIN` at library `also` callbacks. Recovery retained 29 qualified rows, including ten interface members. |
| Coroutine broad TRACE | **Failed completion, inherited** | Anonymous implementation/override/caller omissions remained `UNSUPPORTED_ITEM`; recovery retained 20 qualified rows and `exhaustive: false`. |
| Depth-one `collectIndexed` CALLEES | **Passed, inherited** | The proven `Flow.collect` edge under 15 seconds / 100 results; no broader graph or runtime activation claim. |
| Admission and identity owners below | **Known, directly inspected** | Existing representations and explicit rejection boundaries; no new runtime observation. |
| All checks proposed below | **Proposed** | No execution result yet. Promote status only with the actual command, source snapshot, oracle and receipt. |

The old `/private/tmp/kast-litmus-czkf4t1h` root, adapted corpus and raw receipts
were intentionally deleted after cleanup. The durable report is the inherited
record; fresh execution must reconstruct and record its inputs. Old opaque
references, retained-result handles and deleted init-script paths are not reusable.

The durable record does not preserve every broad TRACE request, grant or authored
consumer. Before baseline execution, author and freeze a new manifest containing
the complete fixture sources, exact request shapes, scopes, grants and independent
oracle. Acquire opaque handles afresh. Compare baseline and candidate on that same
revision. Unless the old inputs can independently be recovered, this qualifies the
same reported shapes; it does not replay the deleted run. The 15-second/100-result
grant above belongs to the recorded CALLEES case and must not be attributed to the
broad TRACE requests.

The source pins and adaptation limits are in the validation record. Use the same
Ktor, Spring-guide and kotlinx.coroutines commits for the next matched run. These
are selected source slices with authored consumers, not qualification of entire
upstream repositories. Keep Spring's zero-source-implementation case as a control:
runtime proxies must not become invented source implementations.

## Work A: prove the external callback connection

The failing shape is `Attributes.take` / `takeOrNull` calling library `also`, with
`remove` inside its callback. Finding the callback body or enumerating its source
does not by itself prove the required invocation connection.

**Current owner.** [IntellijCallbackBindingReader](../../relation/intellij/src/main/kotlin/io/github/amichne/kast/relation/intellij/IntellijCallbackBindingReader.kt)
rejects a mapped function outside `context.scope.nativeScope` with `OUTSIDE_DOMAIN`
before admitting its target and function parameter. Other callback supply and
forwarding readers also enforce scope. Trace the original obligation through
these paths before changing one gate in isolation.

**Decision to investigate.** Determine whether current K2 observations, admitted
dependency source, or an existing compiler-grounded summary can establish the
callback connection without changing the user's authored-source relation scope.
Reuse the existing callback graph and supply contracts first. Extend them only
for a demonstrated missing fact. A function named `also`, an `inline` modifier or
an unverified summary must never suffice. Availability of usable compiler
contract evidence is currently **Unknown**.

Distinguish a proved out-of-scope endpoint from an unresolved required callback
connection. A typed scope exclusion can explain the former; it cannot discharge
the latter. Conversely, known exclusions must not become generic unresolved
in-domain failures. Preserve the original relation question and dependency basis.

| Case | Starting facts and permitted effects | Independent expected result |
| --- | --- | --- |
| A1: admission rule | Explicit compiler observations and current domain types; no filesystem, IDE or clock | Required proof cannot be replaced by `NotRequired`; unavailable input retains its original finite causes. Extend the existing required-proof test only for a new rule. |
| A2: resolved library callback | Small imported Kotlin source with the failing `also { remove(key) }` shape and real library resolution; K2 reads only | Exact argument-to-parameter binding, callback body identity and connection to the source `remove` call agree with independently marked source spans. Complete only when every required obligation is discharged. |
| A3: boundary controls | Same callback body through an in-domain helper, then through actual resolved library `also`, holding query scope and grants fixed | The pair isolates external admission from callback-body discovery. Record the first differing typed stage; do not assume both have the same available proof. |
| A4: negative controls | A same-named user function that stores or conditionally invokes its callback; unresolved supply; excluded dependency | No name-based library special case, fabricated unconditional invocation or loss of scope evidence. Preserve qualified flow or reject according to the actual proof; unresolved required proof cannot complete. |

Expected callback facts come from the fixture's authored operations and independent
compiler binding checks, not Kast's returned rows. Static invocation possibilities
and conditional qualifications are distinct from proof that a callback ran.
If no authoritative external proof is available, record the precise missing
capability and keep A incomplete; a more helpful rejection is a separate result.

## Work B: preserve anonymous implementation and caller identities

Keep three concepts distinct: an anonymous object implementing an interface, its
named override method, and an anonymous function/lambda body. They cannot share a
fabricated qualified name or be replaced by their enclosing named function.

**Reuse before extension.**

| Existing owner | Current supported proof and boundary |
| --- | --- |
| [RelationCallableBody](../../relation/contract/src/main/kotlin/io/github/amichne/kast/relation/contract/RelationCallableBody.kt) | Anonymous function bodies retain a compiler signature bound to file/range. This is not an anonymous class identity. |
| [LocalDeclarationAddress](../../symbol/contract/src/main/kotlin/io/github/amichne/kast/symbol/contract/LocalDeclarationAddress.kt) | Local function/property addresses retain compiler owner identity, range and lexical ancestry, with live readmission required. |
| [IntellijK2SymbolIdentity](../../relation/intellij/src/main/kotlin/io/github/amichne/kast/relation/intellij/IntellijK2SymbolIdentity.kt) | Missing class IDs reject classlike projection; local symbols route through local projection first. A source range alone is explicitly insufficient as a reusable exact endpoint. |
| [IntellijLocalRelationProjection](../../relation/intellij/src/main/kotlin/io/github/amichne/kast/relation/intellij/IntellijLocalRelationProjection.kt) | Supports named local functions and local properties; other local declaration forms reject. |
| [IntellijNamedCallbackObservationEmitter](../../relation/intellij/src/main/kotlin/io/github/amichne/kast/relation/intellij/IntellijNamedCallbackObservationEmitter.kt) | Owner projection and callback observation admission can emit `UNSUPPORTED_ITEM`; lexical containment alone is not a callable edge. |

**Investigation order:** compiler candidate → admitted source identity → enclosing
compiler owner → override/implementation relation → caller occurrence owner →
TRACE grouping → retained/public projection → exact-reference readmission. Record
the first unsupported transition for each omission. Reuse working transitions;
extend the owning finite type only when a failing case demonstrates the need.

| Case | Starting facts and permitted effects | Independent expected result |
| --- | --- | --- |
| B1: detached identity invariants | Existing domain constructors with explicit identities, ranges and ancestry; no external effects | Distinct owners remain distinct; mismatched signature/location or ancestry rejects. Names and offsets alone cannot establish compiler authority. |
| B2: implementation and override | Small actual K2 fixture with `object : FlowCollector<T>` and named `override suspend fun emit`; a named implementation as control | Both implementations resolve to the interface; the anonymous override retains its actual owner and declared target. No synthetic globally qualified class name. |
| B3: caller ownership | Calls inside the override and a separate lambda; two same-shaped objects with identical method names | Each occurrence belongs to its actual callable; objects and methods remain distinct. Lexical nesting must not invent a direct caller edge from the outer function. |
| B4: readmission | Same admitted snapshot, then a controlled source edit and real saved-PSI refresh | Reuse only with current compiler authority. A stale identity rejects or explicitly reacquires; changed ownership cannot silently reuse an old lease. Retained results preserve their own freshness rules. |
| B5: unsupported controls | Unavailable compiler owner, unsupported type evidence, out-of-domain candidate | Retain the corresponding finite failure or proved exclusion. No dropped omission, guessed endpoint or exhaustive claim. |

The independent oracle names the interface target, each object expression, each
override, and each source call with file/UTF-16 ranges and expected ownership.
Assign separate oracle labels before issuing queries. Do not derive expected
identities or cardinality by copying actual responses. Compiler/platform tests
must verify the relation; a parser-only range test proves syntax containment only.

## Work C: carry the new proof through TRACE and recovery

Reuse [QueryTraceTasks](../../query/service/src/main/kotlin/io/github/amichne/kast/query/service/QueryTraceTasks.kt)
and its existing seed, implementation and use phases. These phases already request
implementations, direct members, overrides, references and callers. Do not add a
parallel trace engine to compensate for a lost identity or admission failure.

1. Prove the admitted A/B facts survive task expansion, grouping and deduplication.
   Distinct anonymous owners must not collapse. Existing occurrence evidence,
   source-set qualifications, imports and code uses must survive unchanged.
2. Prove enumeration completion and required callback-proof completion separately.
   A complete enumerator with a failed callback obligation still rejects strict
   completion and retains its original cause.
3. Compare every retained page with an independently expected ordered result.
   Include a page containing proof gaps without ordinary rows. Reading retained
   evidence must neither replay semantic work nor erase the unfinished scope.
4. Exercise actual encoded public requests and all affected outcome variants
   against canonical schemas. If an identity variant must cross the public
   boundary, change its owning types, schemas, parsers, projections and examples
   together. Never widen a schema just to admit an incidental invalid row.

Existing regression owners include
[RequiredCallbackInvocationProofTest](../../relation/intellij/src/test/kotlin/io/github/amichne/kast/relation/intellij/RequiredCallbackInvocationProofTest.kt),
[RelationLocalOwnerCallableIdentityObservationTest](../../relation/intellij/src/test/kotlin/io/github/amichne/kast/relation/intellij/RelationLocalOwnerCallableIdentityObservationTest.kt),
[CompleteOnlyCallbackQueryTest](../../query/protocol/src/test/kotlin/io/github/amichne/kast/query/protocol/CompleteOnlyCallbackQueryTest.kt)
and [QueryTraceGroupedRecoveryTest](../../query/service/src/test/kotlin/io/github/amichne/kast/query/service/QueryTraceGroupedRecoveryTest.kt).
Observation-driven tests establish production decisions under supplied facts;
they do not establish that K2 supplies those facts in a real project.

## Execution gates and completion receipts

All gates are **Proposed**, in order. Keep A and B independently reportable.

| Gate | Work and entry condition | Required receipt / exit condition |
| --- | --- | --- |
| 0: reconstruct baseline | Create an owned disposable root. Preserve the current checkout and inventory its exact candidate bytes. Reconstruct minimal fixtures and pinned source slices from the durable report and [portable guide](ENTERPRISE_CACHE.md). Freeze the complete fixture/query/oracle manifest before issuing baseline queries. | Fixture provenance, content hashes, independently reviewed source oracle, exact queries/grants, toolchain and current candidate identity. If old inputs cannot be recovered exactly, label this a new fixture revision, not an exact replay. |
| 1: reproduce the gap | Run the manifest's broad TRACE queries on the unchanged baseline candidate before changing the relevant owner; retain strict mode and the declared scope. | Actual finite causes and first failing stages for A and B. Failure to reproduce is an investigation result; do not invent a red baseline. |
| 2: smallest owner proof | Reuse/extend only the demonstrated production rule. Run the focused owner test before wider consumers; use controlled effect observations only at real seams. | Positive case and adversarial control pass against the production rule. Actual compiler observations, not a double that returns the desired success, are required for identity/resolution claims. |
| 3: integrated public behavior | A/B authority proof passes; exercise affected TRACE, serialization and retention consumers. | Exact identities/occurrences and finite failures survive all projections. Schema validity alone does not prove semantic correctness. |
| 4: native acceptance | Prior gates pass. Build/load the exact candidate in a private IDEA profile with actual Gradle import and indexing. Use the same frozen fixture/query/oracle manifest for baseline and candidate. | Both broad TRACE questions from that manifest complete for the supported slices with independently justified rows and callback qualifications. Negative controls remain qualified/rejected. Verify loaded class hashes, public schemas, page equality and freshness. Report this as matched qualification on the recorded revision. |
| 5: repository qualification | Native assertions are resolved; run affected consumers and required guards for the actual changes. | Exact commands, terminal results, test counts/skips, candidate snapshot and remaining limits in an updated durable validation record. A green build cannot replace gate 4. |
| 6: cleanup and handoff | Save the bounded source/proof summary needed to assess the result. | Stop only owned processes, verify closure, remove the owned root/pointer, verify absence. Retain intended source changes and concise evidence only. |

For focused selectors, use the repository's real module task and the named class,
for example `./gradlew :relation:intellij:test --tests '*RequiredCallbackInvocationProofTest'`.
Resolve runner requirements before execution. Add compiler/native fixtures only
where the assertion needs their authority. Follow [repository testing rules](../../AGENTS.md#testing).

From the disposable candidate checkout, proposed final guards are
`./gradlew verifyJsonContracts verifyKastArchitecture`, then
`./gradlew build knowledgeImpact verifyKnowledgeBase --continue` with the owned
test-isolation configuration and project-managed JVM. These are future commands,
not new passing results. Run additional packaging/native builds only when the
changed surface or candidate composition requires them.

Counts of 29 and 20 are historical retained counts, not acceptance totals for
newly supported traces. Derive the new exact expected set from source before
evaluating candidate output. Preserve all previously proved rows unless an
independently reviewed source/contract change explains a difference.

## Resource bounds, deferred claims and cleanup

Byte grants are a separate concern from these two proof gaps. The previous native
run used an allowance of 524,288 with a 3,162-byte proof-storage estimate and did
not reproduce `BYTE_LIMIT_REACHED`. Paging model-visible content addresses output
size; it does not by itself bound semantic enumeration or retained proof storage.
This plan neither removes nor raises the storage bound as a tracing fix.

If implementation changes proof storage, require deterministic admission/rejection
tests with known accounting and durable typed diagnostics. Preserve bounded work,
elapsed time, cancellation, storage, presentation and retention lifetimes. A
budget failure must identify the consumed allowance and unfinished scope while
retaining admissible facts. Do not describe storage estimates as measured heap or
response bytes. Changing the grant model needs its own measured acceptance case.

Enterprise default-two-second latency, concurrent-load behavior, native byte
exhaustion, runtime callback activation, full upstream completeness and managed
installer qualification remain **deferred and unverified**. Do not fold these
claims into success for A/B. If latency work follows, use matched workloads and
record index readiness, source volume, grant, concurrency and the measured
dimension; the previous mixed concurrent/sequential runs do not isolate causation.

Put all test checkouts, caches, source adaptations, short socket paths, Gradle
state, temporary files and private IDE state under one newly allocated owned root.
Track owned process IDs/groups and file manifests. Do not replace or restart the
daily IDE. Export only a bounded review record before removing temporary receipts;
exclude source payloads, secrets and unbounded diagnostics. Cleanup must run after
failed as well as successful qualification, with any failure explicitly recorded.

## Handoff

The next execution step is gate 0 followed by the two baseline reproductions.
The first decisions are which authoritative callback evidence is available and
where anonymous object/override identity first fails. Implementation follows
those findings, preserving existing owners wherever they already carry the proof.

Close this plan only with separate A, B and C results, native acceptance, required
build/guard evidence and verified cleanup. If a case remains unsupported, state
that gap alongside the passed recovery checks. Planning is complete when this
document is reviewed; implementation and execution remain separate work.
