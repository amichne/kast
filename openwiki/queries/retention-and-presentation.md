---
type: System Explanation
title: Retained results and output delivery
description: Explains immutable result authority, publication claims, output continuations,
  independent evidence cursors, expiry, and replay.
tags:
  - kast
  - queries
code_sources:
  - path: query/protocol/src/main/kotlin/io/github/amichne/kast/query/protocol/QueryStateStore.kt
  - path: query/protocol/src/main/kotlin/io/github/amichne/kast/query/protocol/QueryInvocationPublication.kt
  - path: query/protocol/src/main/kotlin/io/github/amichne/kast/query/protocol/QueryInvocationProjection.kt
  - path: query/protocol/src/main/kotlin/io/github/amichne/kast/query/protocol/RetainedQueryPresentation.kt
  - path: query/contract/src/main/kotlin/io/github/amichne/kast/query/contract/QueryRetainedResult.kt
  - path: runtime/hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/HostedQueryPublicationSession.kt
  - path: cli/src/main/kotlin/io/github/amichne/kast/cli/rpc/KastToolRpcQuerySession.kt
  - path: cli/src/main/js/query-delivery.mjs
  - path: query/protocol/src/test/kotlin/io/github/amichne/kast/query/protocol/QueryRetainedOccurrencePresentationTest.kt
  - path: query/protocol/src/test/kotlin/io/github/amichne/kast/query/protocol/QueryRetainedCallbackPresentationTest.kt
  - path: runtime/hosted/src/test/kotlin/io/github/amichne/kast/runtime/hosted/HostedQueryPublicationProjectionTest.kt
  - path: cli/src/main/js/query-presentation.mjs
  - path: cli/src/test/js/query-presentation.test.mjs
sources:
  - id: openwiki-source-a184ae82a49703816522523e
    resource: repo://query/contract/src/main/kotlin/io/github/amichne/kast/query/contract/QueryRetainedResult.kt
  - id: openwiki-source-47abb721c1c0711f6358e68d
    resource: repo://query/protocol/src/main/kotlin/io/github/amichne/kast/query/protocol/QueryInvocationProjection.kt
  - id: openwiki-source-e2c1e8974f98107495f06907
    resource: repo://query/protocol/src/main/kotlin/io/github/amichne/kast/query/protocol/QueryInvocationPublication.kt
  - id: openwiki-source-548dc6caf72f8f7bd038635e
    resource: repo://query/protocol/src/main/kotlin/io/github/amichne/kast/query/protocol/QueryStateStore.kt
  - id: openwiki-source-4bf64022307aff9f9f531f9b
    resource: repo://query/protocol/src/main/kotlin/io/github/amichne/kast/query/protocol/RetainedQueryPresentation.kt
  - id: openwiki-source-94796d308902e0d5ca80c5fc
    resource: repo://runtime/hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/HostedQueryPublicationSession.kt
generated: { by: "codex", at: "2026-10-15T01:04:37.434Z" }
verified:
  - by: openwiki/0.6.1
    at: 2026-10-15T01:04:37.434Z
---

# Retained results and output delivery

## Execution finishes before public output resumes

The canonical invocation accumulates semantic work under one admitted grant and completion policy.
Final retention captures immutable rows, coverage, failures, observations, and the original question.
An output continuation refers to an already-produced suffix.
It does not continue semantic execution or enlarge the original grant.

`QueryStateStore` owns checkpoints, retained results, fitted pages, and exclusive publication claims under one lifetime and quota.
Internal automatic checkpoints remain invocation-owned until final publication settles them.
`QueryInvocationPublication` coordinates those transitions and releases unpublished state after failure or cancellation.
A partial transport reply cannot transfer an uncommitted publication claim to another caller.

## Result and evidence positions differ

`RetainedQueryPresentation` reads immutable output under its original authority.
The retained result reference identifies the stored result.
The result cursor selects a row position within that result.
The evidence cursor independently selects failures, omissions, and observations.
An evidence-only page can advance without a new semantic row.

Selected issued row identifiers remain bound to their owning result.
They do not reconstruct weaker exact references or establish complete coverage for a subset.
Binding rows keep each cell's evidence.
Impact findings keep their original path links and investigation qualification.

## Fit and commit one response

The hosted publication session owns allocation, response fitting, encoded output proof, commit, and cancellation release.
The full envelope must fit, including necessary qualification and failure evidence.
A response-byte budget is not permission to discard evidence until a success document fits.
Required retention failure remains a finite rejection with the original coverage.

Tool RPC and the shared JavaScript delivery client consume this admitted output protocol.
Presentation keeps received evidence and follows returned cursors without a hidden semantic retry.
Expired, foreign, unavailable, or capacity-rejected state does not become an empty successful page.
Output reads preserve the owning lifetime and do not renew it through a new semantic grant.

The retained-output tests establish immutable replay, row and evidence progress, and typed rejection under controlled storage.
They do not establish the latency or completeness of an unmeasured native query.

## Focused tests

- [QueryRetainedOccurrencePresentationTest.kt](../../query/protocol/src/test/kotlin/io/github/amichne/kast/query/protocol/QueryRetainedOccurrencePresentationTest.kt)
- [QueryRetainedCallbackPresentationTest.kt](../../query/protocol/src/test/kotlin/io/github/amichne/kast/query/protocol/QueryRetainedCallbackPresentationTest.kt)
- [HostedQueryPublicationProjectionTest.kt](../../runtime/hosted/src/test/kotlin/io/github/amichne/kast/runtime/hosted/HostedQueryPublicationProjectionTest.kt)

## Source owners

- [QueryStateStore.kt](../../query/protocol/src/main/kotlin/io/github/amichne/kast/query/protocol/QueryStateStore.kt)
- [QueryInvocationPublication.kt](../../query/protocol/src/main/kotlin/io/github/amichne/kast/query/protocol/QueryInvocationPublication.kt)
- [QueryInvocationProjection.kt](../../query/protocol/src/main/kotlin/io/github/amichne/kast/query/protocol/QueryInvocationProjection.kt)
- [RetainedQueryPresentation.kt](../../query/protocol/src/main/kotlin/io/github/amichne/kast/query/protocol/RetainedQueryPresentation.kt)
- [QueryRetainedResult.kt](../../query/contract/src/main/kotlin/io/github/amichne/kast/query/contract/QueryRetainedResult.kt)
- [HostedQueryPublicationSession.kt](../../runtime/hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/HostedQueryPublicationSession.kt)
- [KastToolRpcQuerySession.kt](../../cli/src/main/kotlin/io/github/amichne/kast/cli/rpc/KastToolRpcQuerySession.kt)
- [query-delivery.mjs](../../cli/src/main/js/query-delivery.mjs)

## Read next

- [Completion](trace-and-completion.md)
- [Shared delivery](../transport/harness-delivery.md)
- [Impact witnesses](impact-and-peer-proof.md)

## Received-evidence summaries

The shared presentation starts with received row, relation, and file counts, original coverage, and blockers.
Received counts do not describe an unread suffix or the invocation's entire accumulated output.
Refinement failures keep exact locations and finite reasons without an issued source selector.
Recovery advice does not grant authority or start another RUN.
