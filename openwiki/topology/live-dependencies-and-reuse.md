---
type: System Explanation
title: Live dependency proofs and fact reuse
description: Explains current-owner dependency capture, complete input inventories,
  guarded callback fact reuse, capacity, and retirement.
tags:
- kast
- topology
code_sources:
- path: topology/contract/src/main/kotlin/io/github/amichne/kast/topology/contract/SemanticDependencySnapshot.kt
- path: topology/intellij/src/main/kotlin/io/github/amichne/kast/topology/intellij/IntellijSemanticDependencyCapture.kt
- path: topology/build/src/main/kotlin/io/github/amichne/kast/topology/build/SemanticCallbackFactStore.kt
- path: runtime/hosted/build.gradle.kts
- path: topology/build/src/main/kotlin/io/github/amichne/kast/topology/build/SemanticCallbackFactRead.kt
- path: topology/contract/src/main/kotlin/io/github/amichne/kast/topology/contract/CompleteTopologyGeneration.kt
- path: topology/intellij/src/main/kotlin/io/github/amichne/kast/topology/intellij/AdmittedSourceRootEnumerator.kt
- path: topology/build/src/test/kotlin/io/github/amichne/kast/topology/build/SemanticCallbackFactStoreTest.kt
- path: topology/build/src/test/kotlin/io/github/amichne/kast/topology/build/SemanticCallbackStoreRetirementTest.kt
- path: topology/build/src/test/kotlin/io/github/amichne/kast/topology/build/SemanticCallbackSupplierStoreTest.kt
verified:
  - by: openwiki/0.6.1
    at: 2026-10-15T00:33:05.932Z
sources:
  - id: openwiki-source-99135b98c4f85c243291f774
    resource: repo://topology/build/src/main/kotlin/io/github/amichne/kast/topology/build/SemanticCallbackFactStore.kt
  - id: openwiki-source-3be5d9cf3fcc7a7b479b3444
    resource: repo://topology/contract/src/main/kotlin/io/github/amichne/kast/topology/contract/SemanticDependencySnapshot.kt
  - id: openwiki-source-c1b372e04845c7dde01c9691
    resource: repo://topology/intellij/src/main/kotlin/io/github/amichne/kast/topology/intellij/IntellijSemanticDependencyCapture.kt
generated: { by: "codex", at: "2026-10-15T00:33:05.932Z" }
---

# Live dependency proofs and fact reuse

## Live topology supports the hosted semantic path

The hosted runtime depends on topology contracts, build logic, and IntelliJ adapters.
These owners capture dependency evidence and reuse detached callback facts under current live authority.
Historical SQLite capture and graph algorithms use separate contracts and effects.
A directory named topology does not imply one interchangeable execution path.

`IntellijSemanticDependencyCapture` opens a native read scope with the original authority and imported workspace model.
It captures the dependency closure, complete source inventory, and resolution inputs within that scope.
The scope releases native carriers before the detached result escapes.
Capture cost remains charged when reuse eligibility fails.
A rejected capture preserves the ordinary fresh semantic path.

## Reuse needs complete content identities

`SemanticDependencySnapshot.fromCompiler` admits one workspace and one shared dependency domain for source and resolution inventories.
Resolution inputs include SDK, compiler configuration, and classpath content identities.
URLs, SDK names, and modification counters do not establish those identities.

`reuseFrom` first admits the current original owner and environment continuity.
It then compares resolution inputs, dependency domains, and source content.
Each mismatch produces its own finite rejection.
A reuse proof admits unchanged detached facts against the current snapshot.
It never revives an old lease or continuation.

## The fact store owns bounded lifetime

`SemanticCallbackFactStore` stores complete callback summaries, complete supplier inventories, and complete named relation partitions.
Keys preserve formal identity or subject, scope, discovery constraints, and relation domain as needed.
Publication and lookup pass through current-owner admission before synchronized store decisions.
Entry count and retained bytes share explicit limits.

The store starts empty, becomes owned by one authority, and can retire permanently.
A different owner cannot adopt old entries because its detached values happen to match.
The store also checks that supplied facts belong to the snapshot basis.
A capacity rejection cannot invent a successful cache result.

These proofs establish eligibility for reuse.
The callback completion owner still discharges semantic obligations before public completion.
Measure installed latency separately from cache counts or reduced source work.

## Focused tests

- [SemanticCallbackFactStoreTest.kt](../../topology/build/src/test/kotlin/io/github/amichne/kast/topology/build/SemanticCallbackFactStoreTest.kt)
- [SemanticCallbackStoreRetirementTest.kt](../../topology/build/src/test/kotlin/io/github/amichne/kast/topology/build/SemanticCallbackStoreRetirementTest.kt)
- [SemanticCallbackSupplierStoreTest.kt](../../topology/build/src/test/kotlin/io/github/amichne/kast/topology/build/SemanticCallbackSupplierStoreTest.kt)

## Source owners

- [SemanticDependencySnapshot.kt](../../topology/contract/src/main/kotlin/io/github/amichne/kast/topology/contract/SemanticDependencySnapshot.kt)
- [IntellijSemanticDependencyCapture.kt](../../topology/intellij/src/main/kotlin/io/github/amichne/kast/topology/intellij/IntellijSemanticDependencyCapture.kt)
- [SemanticCallbackFactStore.kt](../../topology/build/src/main/kotlin/io/github/amichne/kast/topology/build/SemanticCallbackFactStore.kt)
- [build.gradle.kts](../../runtime/hosted/build.gradle.kts)
- [SemanticCallbackFactRead.kt](../../topology/build/src/main/kotlin/io/github/amichne/kast/topology/build/SemanticCallbackFactRead.kt)
- [CompleteTopologyGeneration.kt](../../topology/contract/src/main/kotlin/io/github/amichne/kast/topology/contract/CompleteTopologyGeneration.kt)
- [AdmittedSourceRootEnumerator.kt](../../topology/intellij/src/main/kotlin/io/github/amichne/kast/topology/intellij/AdmittedSourceRootEnumerator.kt)

## Read next

- [Callback reuse](../semantics/relation/callback-reuse-and-completion.md)
- [Hosted composition](../runtime/hosted-composition-and-publication.md)
