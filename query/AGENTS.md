---
type: Module Guide
title: "Kast query"
description: "Owns query plans, semantic execution, strict completion, and retained output under the original read authority."
resource: "repo://query"
tags: [kast, navigation]
timestamp: "2026-10-22T06:15:00Z"
source_revision: ebc805014ada9e3eb1b85ef611e41bd176af8d80
code_sources:
  - path: query/protocol/src/main/kotlin/io/github/amichne/kast/query/protocol/QueryCallbackCompletion.kt
  - path: query/contract/build.gradle.kts
  - path: query/contract/src/main/kotlin/io/github/amichne/kast/query/contract/QueryImpactLedger.kt
  - path: query/contract/src/main/kotlin/io/github/amichne/kast/query/contract/QueryInvestigationCompletion.kt
  - path: query/contract/src/main/kotlin/io/github/amichne/kast/query/contract/QueryPlan.kt
  - path: query/contract/src/main/kotlin/io/github/amichne/kast/query/contract/QueryRows.kt
  - path: query/contract/src/main/kotlin/io/github/amichne/kast/query/contract/QueryTrace.kt
  - path: query/contract/src/main/kotlin/io/github/amichne/kast/query/contract/QueryStorageAllowance.kt
  - path: query/protocol/build.gradle.kts
  - path: query/protocol/src/main/kotlin/io/github/amichne/kast/query/protocol/CanonicalQueryProtocol.kt
  - path: query/protocol/src/main/kotlin/io/github/amichne/kast/query/protocol/QueryCompletionEvidence.kt
  - path: query/protocol/src/main/kotlin/io/github/amichne/kast/query/protocol/QueryInvocationProjection.kt
  - path: query/protocol/src/main/kotlin/io/github/amichne/kast/query/protocol/QueryInvocationPreview.kt
  - path: query/protocol/src/main/kotlin/io/github/amichne/kast/query/protocol/QueryOriginalEvidenceSummary.kt
  - path: query/protocol/src/main/kotlin/io/github/amichne/kast/query/protocol/QueryStateStore.kt
  - path: query/protocol/src/main/kotlin/io/github/amichne/kast/query/protocol/RetainedQueryPresentation.kt
  - path: query/protocol/src/test/kotlin/io/github/amichne/kast/query/protocol/CompleteOnlyCallbackQueryTest.kt
  - path: query/protocol/src/test/kotlin/io/github/amichne/kast/query/protocol/PartialPrefixProducerTest.kt
  - path: query/service/build.gradle.kts
  - path: query/service/src/main/kotlin/io/github/amichne/kast/query/service/QueryService.kt
  - path: query/service/src/main/kotlin/io/github/amichne/kast/query/service/QueryTraceTasks.kt
  - path: query/service/src/test/kotlin/io/github/amichne/kast/query/service/QueryJoinCompositionTest.kt
---

<!-- AGENTS.md: generated navigation; keep this file checked in -->
<!-- generated: 2026-10-14 | hash: cb7b0326cfae -->

# query

## Purpose

Owns query plans, semantic execution, strict completion, and retained output under the original read authority.

## Key Files

- [QueryPlan.kt](contract/src/main/kotlin/io/github/amichne/kast/query/contract/QueryPlan.kt) - typed sources and ordered stages.
- [QueryRows.kt](contract/src/main/kotlin/io/github/amichne/kast/query/contract/QueryRows.kt) - symbol, occurrence, binding, traversal, and value-path rows.
- [QueryTrace.kt](contract/src/main/kotlin/io/github/amichne/kast/query/contract/QueryTrace.kt) - the fixed topology for affected uses.
- [QueryService.kt](service/src/main/kotlin/io/github/amichne/kast/query/service/QueryService.kt) - the existing stage interpreter.
- [QueryTraceTasks.kt](service/src/main/kotlin/io/github/amichne/kast/query/service/QueryTraceTasks.kt) - reference, implementation, override, and caller tasks.
- [CanonicalQueryProtocol.kt](protocol/src/main/kotlin/io/github/amichne/kast/query/protocol/CanonicalQueryProtocol.kt) - canonical admission, automatic accumulation, and projection.
- [QueryCompletionEvidence.kt](protocol/src/main/kotlin/io/github/amichne/kast/query/protocol/QueryCompletionEvidence.kt) - completion proof from execution, failures, and callback evidence.
- [QueryInvestigationCompletion.kt](contract/src/main/kotlin/io/github/amichne/kast/query/contract/QueryInvestigationCompletion.kt) - original impact completion independent of a presentation slice.
- [QueryStateStore.kt](protocol/src/main/kotlin/io/github/amichne/kast/query/protocol/QueryStateStore.kt) - checkpoint ownership, retained evidence, quotas, and external token lifetimes.
- [QueryStorageAllowance.kt](contract/src/main/kotlin/io/github/amichne/kast/query/contract/QueryStorageAllowance.kt) - finite or unlimited internal storage allowances.
- [QueryInvocationProjection.kt](protocol/src/main/kotlin/io/github/amichne/kast/query/protocol/QueryInvocationProjection.kt) - final retention and issuance after invocation completion.
- [QueryInvocationPreview.kt](protocol/src/main/kotlin/io/github/amichne/kast/query/protocol/QueryInvocationPreview.kt) - pure preview fitting after the invocation owner issues row identities.
- [QueryOriginalEvidenceSummary.kt](protocol/src/main/kotlin/io/github/amichne/kast/query/protocol/QueryOriginalEvidenceSummary.kt) - pure counts and finite reasons from original evidence before presentation selection.
- [RetainedQueryPresentation.kt](protocol/src/main/kotlin/io/github/amichne/kast/query/protocol/RetainedQueryPresentation.kt) - immutable result and witness pages without semantic replay.
- [QueryImpactLedger.kt](contract/src/main/kotlin/io/github/amichne/kast/query/contract/QueryImpactLedger.kt) - branch conservation and unresolved obligations.

## Subdirectories

- [contract/](contract/) - sources, plans, row kinds, proof, budgets, and coverage.
- [service/](service/) - stage tasks, joins, traversal composition, and value-flow execution.
- [protocol/](protocol/) - canonical admission, issued references, completion, retention, and presentation.

## Entry Points

- Gradle projects: `:query:contract`, `:query:service`, and `:query:protocol`.
- [CompleteOnlyCallbackQueryTest.kt](protocol/src/test/kotlin/io/github/amichne/kast/query/protocol/CompleteOnlyCallbackQueryTest.kt) - strict callback completion cases.
- [QueryJoinCompositionTest.kt](service/src/test/kotlin/io/github/amichne/kast/query/service/QueryJoinCompositionTest.kt) - binding rows and complete-right admission.
- [PartialPrefixProducerTest.kt](protocol/src/test/kotlin/io/github/amichne/kast/query/protocol/PartialPrefixProducerTest.kt) - retained output delivery without a native host.

## Navigation Hints

- Trace a public `RUN` through `CanonicalQueryProtocol` into `QueryService` and its domain operations.
- Every public run uses `COMPLETE_ONLY` under `COMPILER_RESOLVED_STATIC_V1`. Unproved completion remains a typed rejection.
- `QueryCallbackCompletion` selects source completeness for terminal `TRACE` from source symbols with only filters or deduplication before it. Callback effects remain explicit evidence.
- Explicit relation operations, traversal, composition, retained sources, and impact sources keep callback graph completeness. Retained presentation uses the original question's interpretation.
- `RESUME` presents produced output. `READ_RESULT` presents immutable rows and evidence. Neither operation restarts semantic execution.
- A fresh `RUN` can select `RUNNER_CONTROLLED`. Its invocation keeps acquired evidence past the token lifetime until completion or cancellation.
- External tokens still expire. Output pages remain finite. Internal storage quotas do not stop `RUNNER_CONTROLLED` execution.
- Keep output cursors, exact references, result references, and internal execution checkpoints distinct.
- Capture the original evidence summary before presentation selection. A summary does not prove that the client received its full evidence.
- For public syntax, read the app-server query guide. Its schema owns defaults and generated syntax.
- Read the [root guide](../AGENTS.md) for engineering rules and optional OpenWiki retrieval.
