---
type: System Explanation
title: Callback reuse and graph completion
description: Explains bounded callback fact storage, dependency continuity, fresh
  endpoint readmission, supplier coverage, and static graph obligations.
tags:
- kast
- semantics
code_sources:
- path: topology/contract/src/main/kotlin/io/github/amichne/kast/topology/contract/SemanticDependencySnapshot.kt
- path: topology/build/src/main/kotlin/io/github/amichne/kast/topology/build/SemanticCallbackFactStore.kt
- path: relation/intellij/src/main/kotlin/io/github/amichne/kast/relation/intellij/CallbackSummaryReuse.kt
- path: relation/intellij/src/main/kotlin/io/github/amichne/kast/relation/intellij/IntellijCallbackSummaryReadmission.kt
- path: relation/contract/src/main/kotlin/io/github/amichne/kast/relation/contract/CompleteCallbackSupplierInventory.kt
- path: relation/contract/src/main/kotlin/io/github/amichne/kast/relation/contract/CompleteStaticCallbackGraph.kt
- path: query/protocol/src/main/kotlin/io/github/amichne/kast/query/protocol/QueryCallbackGraphAdmission.kt
- path: relation/intellij/src/test/kotlin/io/github/amichne/kast/relation/intellij/CallbackSummaryReuseTest.kt
- path: relation/contract/src/test/kotlin/io/github/amichne/kast/relation/contract/CallbackSummaryReadmissionTest.kt
- path: topology/build/src/test/kotlin/io/github/amichne/kast/topology/build/SemanticCallbackSupplierStoreTest.kt
verified:
  - by: openwiki/0.6.1
    at: 2026-10-15T00:33:05.932Z
sources:
  - id: openwiki-source-b40db0ab2fa6f0ddc8926bd4
    resource: repo://query/protocol/src/main/kotlin/io/github/amichne/kast/query/protocol/QueryCallbackGraphAdmission.kt
  - id: openwiki-source-0c3775ff1ab9aa2c15a185c6
    resource: repo://relation/contract/src/main/kotlin/io/github/amichne/kast/relation/contract/CompleteStaticCallbackGraph.kt
  - id: openwiki-source-ba2ee282aef46ccb6d28cca8
    resource: repo://relation/intellij/src/main/kotlin/io/github/amichne/kast/relation/intellij/CallbackSummaryReuse.kt
  - id: openwiki-source-2cbba9b711c0d9c4a193731f
    resource: repo://relation/intellij/src/main/kotlin/io/github/amichne/kast/relation/intellij/IntellijCallbackSummaryReadmission.kt
  - id: openwiki-source-3be5d9cf3fcc7a7b479b3444
    resource: repo://topology/contract/src/main/kotlin/io/github/amichne/kast/topology/contract/SemanticDependencySnapshot.kt
generated: { by: "codex", at: "2026-10-15T00:33:05.932Z" }
---

# Callback reuse and graph completion

## Store detached facts under one live lifetime

`SemanticCallbackFactStore` keeps project-owned formal summaries and complete supplier inventories under bounded retention.
Each entry keeps its dependency snapshot and the exact formal and scope identities.
The store has empty, owned, and retired lifetimes.
Retirement prevents an old cache entry from becoming a new authority.

`SemanticDependencySnapshot` joins the complete source inventory and resolution inputs under the original live owner.
Reuse compares environment continuity, source content, dependency domains, and resolution inputs.
A matching URL, SDK name, or modification counter alone does not establish continuity.
The snapshot proof does not establish callback completion.

## Fresh admission still happens on reuse

The relation adapter readmits every retained endpoint through current compiler identity and work accounting.
Summary restoration instantiates the current body and actual binding from the admitted formal facts.
An invalid binding or mismatched endpoint remains a rejection.
The cache does not suppress that native proof step.

`CallbackSummaryReuse` captures a candidate only after an exhaustive body scan.
It publishes optional storage only after final flow and owner-binding admission.
A retention rejection records its own bounded outcome.
It does not erase a valid current flow or manufacture a cache hit.

Supplier inventories have a separate completeness proof.
The selected scope, declaration dependencies, and compiler endpoints must agree before reuse.
Incomplete supply coverage cannot become evidence that no other callback exists.

## Complete graph proof

`CompleteStaticCallbackGraph` admits native invocation flows with no unresolved obligations and a supported named-call policy.
Stored or returned callbacks keep their own supply and invocation proof.
Lexical containment alone does not establish activation.
The graph keeps exact nodes, edge evidence, and workspace scope.

The query completion owner consumes that graph proof separately from ordinary named-call coverage.
Unproved callbacks produce a finite completion reason rather than a complete empty result.
The proof concerns static compiler relationships, without a runtime execution claim.

Store and readmission tests establish dependency, lifetime, binding, and rejection behavior.
A measured cache hit or lower work count does not independently prove installed-host latency or semantic completeness.

## Focused tests

- [CallbackSummaryReuseTest.kt](../../../relation/intellij/src/test/kotlin/io/github/amichne/kast/relation/intellij/CallbackSummaryReuseTest.kt)
- [CallbackSummaryReadmissionTest.kt](../../../relation/contract/src/test/kotlin/io/github/amichne/kast/relation/contract/CallbackSummaryReadmissionTest.kt)
- [SemanticCallbackSupplierStoreTest.kt](../../../topology/build/src/test/kotlin/io/github/amichne/kast/topology/build/SemanticCallbackSupplierStoreTest.kt)

## Source owners

- [SemanticDependencySnapshot.kt](../../../topology/contract/src/main/kotlin/io/github/amichne/kast/topology/contract/SemanticDependencySnapshot.kt)
- [SemanticCallbackFactStore.kt](../../../topology/build/src/main/kotlin/io/github/amichne/kast/topology/build/SemanticCallbackFactStore.kt)
- [CallbackSummaryReuse.kt](../../../relation/intellij/src/main/kotlin/io/github/amichne/kast/relation/intellij/CallbackSummaryReuse.kt)
- [IntellijCallbackSummaryReadmission.kt](../../../relation/intellij/src/main/kotlin/io/github/amichne/kast/relation/intellij/IntellijCallbackSummaryReadmission.kt)
- [CompleteCallbackSupplierInventory.kt](../../../relation/contract/src/main/kotlin/io/github/amichne/kast/relation/contract/CompleteCallbackSupplierInventory.kt)
- [CompleteStaticCallbackGraph.kt](../../../relation/contract/src/main/kotlin/io/github/amichne/kast/relation/contract/CompleteStaticCallbackGraph.kt)
- [QueryCallbackGraphAdmission.kt](../../../query/protocol/src/main/kotlin/io/github/amichne/kast/query/protocol/QueryCallbackGraphAdmission.kt)

## Read next

- [Supply and bindings](callback-supply-and-binding.md)
- [Live dependency store](../../topology/live-dependencies-and-reuse.md)
- [Query completion](../../queries/trace-and-completion.md)
