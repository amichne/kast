---
type: System Explanation
title: Hosted endpoint composition and publication
description: Traces the project-owned IDEA endpoint through canonical dispatch, admitted
  semantic services, bounded transport, and final publication.
tags:
- kast
- runtime
code_sources:
- path: runtime/hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/HostedEndpointService.kt
- path: runtime/hosted/src/main/resources/META-INF/plugin.xml
- path: runtime/hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/HostedSemanticServices.kt
- path: runtime/hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/HostedCanonicalQuery.kt
- path: runtime/hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/HostedEpochStore.kt
- path: runtime/hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/HostedConnectionAdmission.kt
- path: runtime/hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/HostedQueryPublicationSession.kt
- path: workspace/intellij-read/src/main/kotlin/io/github/amichne/kast/workspace/intellij/read/hosted/HostedQueryExecutor.kt
- path: workspace/intellij-read/src/main/kotlin/io/github/amichne/kast/workspace/intellij/read/hosted/HostedReadPublicationAdmission.kt
- path: runtime/hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/DeadHostedEndpointOwner.kt
- path: runtime/hosted/src/test/kotlin/io/github/amichne/kast/runtime/hosted/HostedQueryPublicationProjectionTest.kt
- path: runtime/hosted/src/test/kotlin/io/github/amichne/kast/runtime/hosted/HostedCompletionRejectionPublicationTest.kt
sources:
  - id: openwiki-source-5efa910d729623be2b070bc2
    resource: repo://runtime/hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/HostedConnectionAdmission.kt
  - id: openwiki-source-b711ac75af08966d13a95b22
    resource: repo://runtime/hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/HostedEndpointService.kt
  - id: openwiki-source-94796d308902e0d5ca80c5fc
    resource: repo://runtime/hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/HostedQueryPublicationSession.kt
  - id: openwiki-source-ac716e2943cfb6f63701ba77
    resource: repo://runtime/hosted/src/main/resources/META-INF/plugin.xml
  - id: openwiki-source-931b927626ea234f44ea20df
    resource: repo://workspace/intellij-read/src/main/kotlin/io/github/amichne/kast/workspace/intellij/read/hosted/HostedQueryExecutor.kt
  - id: openwiki-source-95e44f119ab39a83df93b28f
    resource: repo://workspace/intellij-read/src/main/kotlin/io/github/amichne/kast/workspace/intellij/read/hosted/HostedReadPublicationAdmission.kt
generated: { by: "codex", at: "2026-10-15T00:57:31.735Z" }
verified:
  - by: openwiki/0.6.1
    at: 2026-10-15T00:57:31.735Z
---

# Hosted endpoint composition and publication

## Project ownership precedes semantic work

The plugin descriptor registers `HostedEndpointService` as the project-owned endpoint service.
The service composes passive semantic reads and explicitly requested workspace lifecycle operations.
Its endpoint identity, project admission, refresh owner, and retirement state belong to that project lifetime.
Another process cannot manufacture a current endpoint by copying a descriptor.

The endpoint admits request framing and connection ownership before semantic dispatch.
`HostedConnectionAdmission` bounds concurrent connection jobs.
When capacity is unavailable, it performs one bounded rejection exchange without another queued semantic job.
Each admitted connection releases its permit even when cancellation races with its start.

## One real query path

Canonical dispatch selects the query owner after request and workspace admission.
`HostedCanonicalQuery` composes `CanonicalQueryProtocol` around `QueryService` and the native semantic services.
`HostedSemanticServices` supplies discovery, exact lookup, source, relation, traversal, value-flow, and producer acquisition ports.
The ports share the admitted authority and accounting for that request.

`HostedEpochStore` binds retained state to the current original live owner.
Retirement prevents old retained partitions from regaining authority through a matching detached epoch value.
Topology fact stores participate in the project lifetime and preserve their separate dependency reuse proofs.
They do not become a second semantic host.

## Admit the final response before publication

`HostedQueryExecutor` owns read admission, deadlines, cancellation drainage, and final publication decisions.
Containment admission makes sure that necessary typed rejection envelopes fit before a semantic provider runs.
The granted budget remains visible in later finite outcomes.
A response-capacity failure cannot silently remove required evidence.

`HostedQueryPublicationSession` connects semantic accumulation with allocation, full-envelope fitting, encoded output, and commit.
Cancellation releases unpublished claims and retained suffixes through that same owner.
Peer disconnect and extra input stop the admitted transport with distinct causes.
Successful encoding alone does not establish successful transport delivery.

Bounded stage and correlation evidence distinguishes transport, read admission, semantic execution, retention, and publication.
It excludes source payloads and unbounded native diagnostics.
Fixture tests establish these state and adapter mechanisms.
An installed endpoint and a fresh compiler query need separate live-host evidence.

## Focused tests

- [HostedQueryPublicationProjectionTest.kt](../../runtime/hosted/src/test/kotlin/io/github/amichne/kast/runtime/hosted/HostedQueryPublicationProjectionTest.kt)
- [HostedCompletionRejectionPublicationTest.kt](../../runtime/hosted/src/test/kotlin/io/github/amichne/kast/runtime/hosted/HostedCompletionRejectionPublicationTest.kt)

## Source owners

- [HostedEndpointService.kt](../../runtime/hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/HostedEndpointService.kt)
- [plugin.xml](../../runtime/hosted/src/main/resources/META-INF/plugin.xml)
- [HostedSemanticServices.kt](../../runtime/hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/HostedSemanticServices.kt)
- [HostedCanonicalQuery.kt](../../runtime/hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/HostedCanonicalQuery.kt)
- [HostedEpochStore.kt](../../runtime/hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/HostedEpochStore.kt)
- [HostedConnectionAdmission.kt](../../runtime/hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/HostedConnectionAdmission.kt)
- [HostedQueryPublicationSession.kt](../../runtime/hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/HostedQueryPublicationSession.kt)
- [HostedQueryExecutor.kt](../../workspace/intellij-read/src/main/kotlin/io/github/amichne/kast/workspace/intellij/read/hosted/HostedQueryExecutor.kt)
- [HostedReadPublicationAdmission.kt](../../workspace/intellij-read/src/main/kotlin/io/github/amichne/kast/workspace/intellij/read/hosted/HostedReadPublicationAdmission.kt)
- [DeadHostedEndpointOwner.kt](../../runtime/hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/DeadHostedEndpointOwner.kt)

## Read next

- [Workspace authority](../semantics/workspace/read-authority.md)
- [Lifecycle and refresh](idea-lifecycle-and-refresh.md)
- [Retained results](../queries/retention-and-presentation.md)
