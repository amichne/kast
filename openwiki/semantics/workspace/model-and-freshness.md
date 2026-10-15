---
type: System Explanation
title: Imported model and freshness
description: Explains existing IDEA model capture, Gradle source ownership, source-issued
  epochs, short native reads, and final freshness decisions.
tags:
- kast
- semantics
code_sources:
- path: workspace/intellij-read/src/main/kotlin/io/github/amichne/kast/workspace/intellij/read/DetachedModelCapture.kt
- path: workspace/intellij-read/src/main/kotlin/io/github/amichne/kast/workspace/intellij/read/NamedGradleSourceScope.kt
- path: workspace/intellij-read/src/main/kotlin/io/github/amichne/kast/workspace/intellij/read/LiveNamedGradleSourceScopeCapture.kt
- path: workspace/intellij-read/src/main/kotlin/io/github/amichne/kast/workspace/intellij/read/hosted/HostedReadTransaction.kt
- path: workspace/intellij-read/src/main/kotlin/io/github/amichne/kast/workspace/intellij/read/hosted/HostedReadFreshnessOwner.kt
- path: workspace/intellij-read/src/main/kotlin/io/github/amichne/kast/workspace/intellij/read/epoch/ProjectReadEpochVfsListener.kt
- path: topology/contract/src/main/kotlin/io/github/amichne/kast/topology/contract/SemanticDependencySnapshot.kt
- path: workspace/intellij-read/src/main/kotlin/io/github/amichne/kast/workspace/intellij/read/BoundedSourceFileCollector.kt
- path: workspace/intellij-read/src/main/kotlin/io/github/amichne/kast/workspace/intellij/read/hosted/AdmittedHostedQuery.kt
- path: workspace/intellij-read/src/test/kotlin/io/github/amichne/kast/workspace/intellij/read/hosted/HostedReadTransactionTest.kt
- path: workspace/intellij-read/src/test/kotlin/io/github/amichne/kast/workspace/intellij/read/LiveNamedGradleSourceScopeCaptureTest.kt
- path: workspace/intellij-read/src/test/kotlin/io/github/amichne/kast/workspace/intellij/read/ProjectReadEpochVfsOverflowTest.kt
verified:
  - by: openwiki/0.6.1
    at: 2026-10-15T00:33:05.932Z
sources:
  - id: openwiki-source-3be5d9cf3fcc7a7b479b3444
    resource: repo://topology/contract/src/main/kotlin/io/github/amichne/kast/topology/contract/SemanticDependencySnapshot.kt
  - id: openwiki-source-d8481e414cf6b4922dfc7f53
    resource: repo://workspace/intellij-read/src/main/kotlin/io/github/amichne/kast/workspace/intellij/read/DetachedModelCapture.kt
  - id: openwiki-source-26adf1e57264a6d43e0f22c1
    resource: repo://workspace/intellij-read/src/main/kotlin/io/github/amichne/kast/workspace/intellij/read/hosted/HostedReadFreshnessOwner.kt
  - id: openwiki-source-6fc4d1c9f6659b50cecc0294
    resource: repo://workspace/intellij-read/src/main/kotlin/io/github/amichne/kast/workspace/intellij/read/hosted/HostedReadTransaction.kt
  - id: openwiki-source-333fbc6ad593a072534f0baf
    resource: repo://workspace/intellij-read/src/main/kotlin/io/github/amichne/kast/workspace/intellij/read/LiveNamedGradleSourceScopeCapture.kt
generated: { by: "codex", at: "2026-10-15T00:33:05.932Z" }
---

# Imported model and freshness

## Use the imported IDEA model

Workspace admission consumes the project that IDEA already owns.
`DetachedModelCapture` projects the current imported model into detached source and dependency facts.
It does not replace IDEA's Gradle import, indexing, or project lifetime with a second importer.
Unavailable or untrusted model state remains a rejection.

Named Gradle source scope keeps source-set names and imported ownership together.
The native capture admits the exact project and requested root before source enumeration.
A source path under the same repository does not prove that the imported model assigns it to the requested source set.
Library, generated, project-source, and out-of-scope inputs keep their existing policies.

## Source-issued epoch signals

The project epoch owner receives native content and environment signals.
VFS changes, model changes, and disposal affect the admitted read state through their existing owners.
Oversized or unclassified VFS batches invalidate conservatively.
The listener does not claim exact root relevance when it lacks that evidence.

A matching epoch signal alone does not establish unchanged compiler inputs.
Dependency reuse additionally needs complete source and resolution inventories.
`SemanticDependencySnapshot` compares those identities before it admits reuse.
The comparison preserves unavailable or incomparable environment state as a finite failure.

## Short native access, one read transaction

`runHostedReadTransaction` places the full evaluator between freshness examinations without one surrounding platform read lock.
Native adapters use their short admitted read operations for compiler and PSI access.
Detached work can continue outside those operations, but it cannot grant itself fresh native authority.

`HostedReadFreshnessOwner` joins the current source and model facts through short authority transitions.
Final freshness and publication remain necessary after semantic work completes.
A moved content or model basis rejects with its typed recovery direction.
The caller must use that actual recovery instruction instead of a hidden unconditional retry.

The focused model and epoch tests establish capture, scope, invalidation, and transaction rules.
They do not establish that a running customer's imported model matches a local fixture.

## Focused tests

- [HostedReadTransactionTest.kt](../../../workspace/intellij-read/src/test/kotlin/io/github/amichne/kast/workspace/intellij/read/hosted/HostedReadTransactionTest.kt)
- [LiveNamedGradleSourceScopeCaptureTest.kt](../../../workspace/intellij-read/src/test/kotlin/io/github/amichne/kast/workspace/intellij/read/LiveNamedGradleSourceScopeCaptureTest.kt)
- [ProjectReadEpochVfsOverflowTest.kt](../../../workspace/intellij-read/src/test/kotlin/io/github/amichne/kast/workspace/intellij/read/ProjectReadEpochVfsOverflowTest.kt)

## Source owners

- [DetachedModelCapture.kt](../../../workspace/intellij-read/src/main/kotlin/io/github/amichne/kast/workspace/intellij/read/DetachedModelCapture.kt)
- [NamedGradleSourceScope.kt](../../../workspace/intellij-read/src/main/kotlin/io/github/amichne/kast/workspace/intellij/read/NamedGradleSourceScope.kt)
- [LiveNamedGradleSourceScopeCapture.kt](../../../workspace/intellij-read/src/main/kotlin/io/github/amichne/kast/workspace/intellij/read/LiveNamedGradleSourceScopeCapture.kt)
- [HostedReadTransaction.kt](../../../workspace/intellij-read/src/main/kotlin/io/github/amichne/kast/workspace/intellij/read/hosted/HostedReadTransaction.kt)
- [HostedReadFreshnessOwner.kt](../../../workspace/intellij-read/src/main/kotlin/io/github/amichne/kast/workspace/intellij/read/hosted/HostedReadFreshnessOwner.kt)
- [ProjectReadEpochVfsListener.kt](../../../workspace/intellij-read/src/main/kotlin/io/github/amichne/kast/workspace/intellij/read/epoch/ProjectReadEpochVfsListener.kt)
- [SemanticDependencySnapshot.kt](../../../topology/contract/src/main/kotlin/io/github/amichne/kast/topology/contract/SemanticDependencySnapshot.kt)
- [BoundedSourceFileCollector.kt](../../../workspace/intellij-read/src/main/kotlin/io/github/amichne/kast/workspace/intellij/read/BoundedSourceFileCollector.kt)
- [AdmittedHostedQuery.kt](../../../workspace/intellij-read/src/main/kotlin/io/github/amichne/kast/workspace/intellij/read/hosted/AdmittedHostedQuery.kt)

## Read next

- [Read authority](read-authority.md)
- [Live reuse](../../topology/live-dependencies-and-reuse.md)
- [Explicit refresh](../../runtime/idea-lifecycle-and-refresh.md)
