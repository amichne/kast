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
  - id: openwiki-source-01b72c0031ed69c6870649b2
    resource: repo://protocol/contract/src/main/kotlin/io/github/amichne/kast/protocol/contract/QueryCompletionDocument.kt
  - id: openwiki-source-dfd865ab52ce8eea1b519c4e
    resource: repo://query/protocol/src/main/kotlin/io/github/amichne/kast/query/protocol/CanonicalQueryProtocol.kt
  - id: openwiki-source-b40db0ab2fa6f0ddc8926bd4
    resource: repo://query/protocol/src/main/kotlin/io/github/amichne/kast/query/protocol/QueryCallbackGraphAdmission.kt
  - id: openwiki-source-74144d81e5190009f473dacf
    resource: repo://query/protocol/src/main/kotlin/io/github/amichne/kast/query/protocol/QueryCompletionEvidence.kt
  - id: openwiki-source-d8121c19da31a42d14a0a998
    resource: repo://query/service/src/main/kotlin/io/github/amichne/kast/query/service/QueryTraceMembers.kt
  - id: openwiki-source-884fbecd15a59fc68fc423de
    resource: repo://query/service/src/main/kotlin/io/github/amichne/kast/query/service/QueryTraceTasks.kt
generated: { by: "codex", at: "2026-10-15T01:04:37.434Z" }
verified:
  - by: openwiki/0.6.1
    at: 2026-10-16T21:29:39.165Z
---

# TRACE and compiler-static completion

## A fixed affected-use question

`TRACE` expands through the existing relation and source operations inside the query interpreter.
For a seed, it gathers references and direct callers where the declaration is callable.
Classlike seeds add implementations and member work.
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
