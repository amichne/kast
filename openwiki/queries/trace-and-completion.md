---
type: System Explanation
title: TRACE and compiler-static completion
description: Explains fixed affected-use topology, original invocation completion,
  callback graph obligations, and typed recovery on unproved completion.
tags:
  - kast
  - queries
code_sources:
  - path: query/service/src/main/kotlin/io/github/amichne/kast/query/service/QueryTraceTasks.kt
  - path: query/service/src/main/kotlin/io/github/amichne/kast/query/service/QueryTraceMembers.kt
  - path: query/protocol/src/main/kotlin/io/github/amichne/kast/query/protocol/CanonicalQueryProtocol.kt
  - path: query/protocol/src/main/kotlin/io/github/amichne/kast/query/protocol/QueryCompletionEvidence.kt
  - path: query/protocol/src/main/kotlin/io/github/amichne/kast/query/protocol/QueryCallbackGraphAdmission.kt
  - path: protocol/contract/src/main/kotlin/io/github/amichne/kast/protocol/contract/QueryCompletionDocument.kt
  - path: query/contract/src/main/kotlin/io/github/amichne/kast/query/contract/QueryInvestigationCompletion.kt
  - path: query/protocol/src/main/kotlin/io/github/amichne/kast/query/protocol/AdmittedBoundaryModel.kt
  - path: query/service/src/test/kotlin/io/github/amichne/kast/query/service/QueryTraceTest.kt
  - path: query/protocol/src/test/kotlin/io/github/amichne/kast/query/protocol/CompleteOnlyCallbackQueryTest.kt
  - path: protocol/contract/src/test/kotlin/io/github/amichne/kast/protocol/contract/QueryCompletionContractTest.kt
sources:
  - id: openwiki-source-468da36f81e497d3a91bd73f
    resource: repo://app-server/src/main/kotlin/io/github/amichne/kast/appserver/query/PublicToolContract.kt
  - id: openwiki-source-9577b7564213b6b964c79590
    resource: repo://app-server/src/main/resources/io/github/amichne/kast/appserver/query/tools.schema.json
  - id: openwiki-source-01b72c0031ed69c6870649b2
    resource: repo://protocol/contract/src/main/kotlin/io/github/amichne/kast/protocol/contract/QueryCompletionDocument.kt
  - id: openwiki-source-dfd865ab52ce8eea1b519c4e
    resource: repo://query/protocol/src/main/kotlin/io/github/amichne/kast/query/protocol/CanonicalQueryProtocol.kt
  - id: openwiki-source-f59335e3d2864a9877a4b4c2
    resource: repo://query/protocol/src/main/kotlin/io/github/amichne/kast/query/protocol/QueryCallbackCompletion.kt
  - id: openwiki-source-b40db0ab2fa6f0ddc8926bd4
    resource: repo://query/protocol/src/main/kotlin/io/github/amichne/kast/query/protocol/QueryCallbackGraphAdmission.kt
  - id: openwiki-source-74144d81e5190009f473dacf
    resource: repo://query/protocol/src/main/kotlin/io/github/amichne/kast/query/protocol/QueryCompletionEvidence.kt
  - id: openwiki-source-d8121c19da31a42d14a0a998
    resource: repo://query/service/src/main/kotlin/io/github/amichne/kast/query/service/QueryTraceMembers.kt
  - id: openwiki-source-884fbecd15a59fc68fc423de
    resource: repo://query/service/src/main/kotlin/io/github/amichne/kast/query/service/QueryTraceTasks.kt
  - id: openwiki-source-028580fffb72a2163a2c8808
    resource: repo://relation/contract/src/main/kotlin/io/github/amichne/kast/relation/contract/ValueCallbackReturn.kt
  - id: openwiki-source-eebf02fe95c68e81eb45857a
    resource: repo://relation/contract/src/main/kotlin/io/github/amichne/kast/relation/contract/ValueStateEvidence.kt
  - id: openwiki-source-d2f6ff91c73f52d620ca8b1e
    resource: repo://relation/intellij/src/main/kotlin/io/github/amichne/kast/relation/intellij/NativeCallbackReturnSemantics.kt
  - id: openwiki-source-6cf55579d637587c70c522e9
    resource: repo://relation/intellij/src/nativeTest/kotlin/io/github/amichne/kast/relation/intellij/NativeCallbackDirectoryIsolationTest.kt
  - id: openwiki-source-3729941e30572a88593a1c00
    resource: repo://relation/intellij/src/nativeTest/kotlin/io/github/amichne/kast/relation/intellij/NativeDependencyCallbackTest.kt
generated: { by: "codex", at: "2026-11-03T05:45:59.282Z" }
verified:
  - by: openwiki/0.9.0
    at: 2026-11-12T06:30:47.924Z
---

# TRACE and compiler-static completion

## A fixed affected-use question

`TRACE` expands through the existing relation and source operations inside the query interpreter.
For a seed, it gathers references and direct callers where the declaration is callable.
Class-like seeds add implementations and member work.
Function and property seeds add overrides.
Implementation arrivals gather their uses before one further caller layer.

`QueryTraceTasks` groups scoped arrivals before a downstream caller read for each exact capability.
The stage keeps source evidence, qualification, and the original expansion domain.
It does not expose arbitrary traversal depth or a separate graph engine.
Row admission permits `TRACE` only as the last stage with symbol output.

## Complete coverage and semantic completion

Complete coverage concerns finished supported enumeration in the declared scope.
Semantic completion also needs exhausted execution, no item failures, and no omitted evidence under the fixed compiler-static model.
Callback graph obligations and original impact investigation obligations remain separate proof inputs.
An empty qualified page cannot prove absence.

`CanonicalQueryProtocol` automatically accumulates the admitted question under one invocation policy.
It keeps internal checkpoints private to that invocation.
Only produced output can enter a public output continuation.
A caller cannot resume an internal pipeline token to extend semantic execution.

## Callback and impact proof

Named caller edges do not establish when a stored or returned callback executes.
Callback observations carry supply, binding, native invocation, and unresolved obligation evidence into graph admission.
`QueryCallbackGraphAdmission` keeps that evidence separate from ordinary call edges.
Unproved callback completion yields its finite completion reason.

An impact witness also needs the original complete value-path investigation.
A compact finding or the last presentation page does not create original investigation completion.
The retained proof must link to the original result and its accounting.

## Rejection still carries useful evidence

`QueryCompletionDocument` fixes `COMPLETE_ONLY` and `COMPILER_RESOLVED_STATIC_V1` in encoded output.
Unproved execution, item failures, omitted evidence, callback obligations, investigation obligations, and retention failures remain distinct.
When retention succeeds, rejection can carry an issued result and a proven preview for later examination.
Reading that evidence preserves the original rejection and performs no semantic replay.

The proof concerns compiler-resolved source relationships.
It does not establish runtime activation, path feasibility, or behavior outside the admitted model.

## Three public tracing contracts

EXPAND_RELATION, WALK, and TRACE connect declarations through compiler-resolved source relationships.
Callback observations explain supply, formal mapping, forwarding, and invocation.
IMPACT with KOTLIN_FORWARD_V1 follows exact producer values through supported transfers and reviewed models.
A reference count is not a call count. None of these contracts proves runtime execution or branch feasibility.

In the public `submit` and `forward` illustration, `checkout` supplies a lambda to `submit.block`.
An immutable `action` alias keeps its value. Forwarding supplies `forward.next`, whose invocation belongs to `forward`.
The call to `charge` belongs to the anonymous lambda body.
The named lexical container, anonymous body, supplier, receiving formal, and actual invocation owner remain distinct.
Supply alone does not create a direct named caller edge.
The example illustrates source meaning rather than recording a Kast response.

Declared calls-in-place effects need compiler-selected formals and qualified binary evidence.
Inline syntax alone supplies no declared effect.
Missing or conflicting effects remain proof gaps rather than becoming declared UNKNOWN.
Native fixtures expect declared callsInPlace effects for with, apply, and let in their selected standard library.
They expect map to preserve missing-effect gaps.
These statements describe fixture expectations rather than a new native execution.
For `forEach` and other installed dependencies, the admitted formal's actual effect and qualified binary identity determine the result.
The reviewed lazy model proves initializer supply without proving a property read or initializer execution.

IMPACT tracks constructor results, arguments, receivers, supported immutable storage, reads, and matching returns.
Each mapper call and field keeps its own provenance.
Eligible source implementations keep separate alternatives within the declared domain.
Opaque argument-to-result relationships need source proof or a reviewed model.
Normal final try and catch values can supply the try expression.
Each catch alternative keeps its own producer. Throws supply no normal result.
Finally transfers keep FINALLY_UNSUPPORTED.
These transfers supply no automatic container, branch-condition, collection-element, logging, header, or error model.

Immutable callback captures keep exact bindings and anonymous owners.
Captured arguments, captured receivers, and wrapper receivers remain distinct.
Invocation-dependent transfers need an admitted callback route from the named root.
CALLBACK_RETURN keeps supplier occurrence, invocation owner, and the matching source-call frame.
Different wrapper calls keep separate result identities.
A discarded callback result supplies no transfer to a constant return.
Unit coercion discards a value. Unit? remains a value.
Anonymous invocation owners, mutable captures, cross-owner captures, unsupported routes, and unresolved owners keep explicit obligations.
A returned uncalled callback gains no invocation-dependent value path.
Parameter declarations cannot seed public IMPACT.

Source-symbol TRACE can complete with explicit callback execution gaps under SOURCE_RELATIONSHIPS.
Explicit CALLERS still needs the callback invocation graph.
Both interpretations reject unresolved source identity, absent result membership, stale dependency evidence, and exhausted grants.
Presentation preserves the original interpretation and completion obligations.

RUNNER_CONTROLLED is the public default without implicit semantic time or work quotas.
Explicit maxElapsedMs includes model capture. Explicit maxWorkUnits rejects.
Caller cancellation and disconnection stop execution.
`maxResults` and maxReturnedBytes bound output pages without granting completion.
BOUNDED remains an explicit selection of configured grants and ceilings.

The [public tracing reference](../../docs/public/reference/program-tracing.mdx) keeps the illustrated Kotlin route, exact request, diagrams, and lookup links.

## Focused tests

- [QueryTraceTest.kt](../../query/service/src/test/kotlin/io/github/amichne/kast/query/service/QueryTraceTest.kt)
- [CompleteOnlyCallbackQueryTest.kt](../../query/protocol/src/test/kotlin/io/github/amichne/kast/query/protocol/CompleteOnlyCallbackQueryTest.kt)
- [QueryCompletionContractTest.kt](../../protocol/contract/src/test/kotlin/io/github/amichne/kast/protocol/contract/QueryCompletionContractTest.kt)

## Source owners

- [QueryTraceTasks.kt](../../query/service/src/main/kotlin/io/github/amichne/kast/query/service/QueryTraceTasks.kt)
- [QueryTraceMembers.kt](../../query/service/src/main/kotlin/io/github/amichne/kast/query/service/QueryTraceMembers.kt)
- [CanonicalQueryProtocol.kt](../../query/protocol/src/main/kotlin/io/github/amichne/kast/query/protocol/CanonicalQueryProtocol.kt)
- [QueryCompletionEvidence.kt](../../query/protocol/src/main/kotlin/io/github/amichne/kast/query/protocol/QueryCompletionEvidence.kt)
- [QueryCallbackGraphAdmission.kt](../../query/protocol/src/main/kotlin/io/github/amichne/kast/query/protocol/QueryCallbackGraphAdmission.kt)
- [QueryCompletionDocument.kt](../../protocol/contract/src/main/kotlin/io/github/amichne/kast/protocol/contract/QueryCompletionDocument.kt)
- [QueryInvestigationCompletion.kt](../../query/contract/src/main/kotlin/io/github/amichne/kast/query/contract/QueryInvestigationCompletion.kt)
- [AdmittedBoundaryModel.kt](../../query/protocol/src/main/kotlin/io/github/amichne/kast/query/protocol/AdmittedBoundaryModel.kt)

## Read next

- [Callback proof](../semantics/relation/callback-reuse-and-completion.md)
- [Retained rejection evidence](retention-and-presentation.md)
- [Execution stages](execution-and-composition.md)

## Exact callback failure subjects

A callback graph rejection keeps its cause, origin, group, and observation position.
It also carries the exact callable location or callback site and body locations that identify the failed proof.
These locations explain the rejection without a new invocation edge or completion claim.
The callback graph admission owner supplies the subject through the canonical result schema.
