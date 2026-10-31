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
  - path: protocol/contract/src/main/kotlin/io/github/amichne/kast/protocol/contract/QueryCompletionDocument.kt
sources:
  - id: openwiki-source-b40db0ab2fa6f0ddc8926bd4
    resource: repo://query/protocol/src/main/kotlin/io/github/amichne/kast/query/protocol/QueryCallbackGraphAdmission.kt
  - id: openwiki-source-e37bf683e8f23013392fd8db
    resource: repo://query/protocol/src/test/kotlin/io/github/amichne/kast/query/protocol/CompleteOnlyCallbackQueryTest.kt
  - id: openwiki-source-794a059b04ef16349744ba54
    resource: repo://relation/contract/src/main/kotlin/io/github/amichne/kast/relation/contract/CallbackEvidenceDomain.kt
  - id: openwiki-source-92931cce7bbdc5df09bed1ca
    resource: repo://relation/contract/src/main/kotlin/io/github/amichne/kast/relation/contract/CallbackResultProjection.kt
  - id: openwiki-source-0c3775ff1ab9aa2c15a185c6
    resource: repo://relation/contract/src/main/kotlin/io/github/amichne/kast/relation/contract/CompleteStaticCallbackGraph.kt
  - id: openwiki-source-ba2ee282aef46ccb6d28cca8
    resource: repo://relation/intellij/src/main/kotlin/io/github/amichne/kast/relation/intellij/CallbackSummaryReuse.kt
  - id: openwiki-source-d34348d555f34ade3bb02006
    resource: repo://relation/intellij/src/main/kotlin/io/github/amichne/kast/relation/intellij/IntellijCallbackResultProjection.kt
  - id: openwiki-source-2cbba9b711c0d9c4a193731f
    resource: repo://relation/intellij/src/main/kotlin/io/github/amichne/kast/relation/intellij/IntellijCallbackSummaryReadmission.kt
  - id: openwiki-source-c998a3454d4c6728581ff50e
    resource: repo://relation/intellij/src/main/kotlin/io/github/amichne/kast/relation/intellij/IntellijK2RelationSearch.kt
  - id: openwiki-source-7fb52b2012eb836868d8299c
    resource: repo://relation/intellij/src/main/kotlin/io/github/amichne/kast/relation/intellij/IntellijReferenceInventoryReuse.kt
  - id: openwiki-source-94eada9d19d5e6c2255cf42d
    resource: repo://relation/intellij/src/test/kotlin/io/github/amichne/kast/relation/intellij/CallbackEvidenceAccessTest.kt
  - id: openwiki-source-832a991b5b9fb99d8fffe3f2
    resource: repo://relation/intellij/src/test/kotlin/io/github/amichne/kast/relation/intellij/ReferenceInventoryReuseTest.kt
  - id: openwiki-source-3be5d9cf3fcc7a7b479b3444
    resource: repo://topology/contract/src/main/kotlin/io/github/amichne/kast/topology/contract/SemanticDependencySnapshot.kt
generated: { by: "codex", at: "2026-10-16T21:29:39.165Z" }
verified:
  - by: openwiki/0.6.1
    at: 2026-10-30T23:25:02.193Z
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
The evidence domain, declaration dependencies, and compiler endpoints must agree before reuse.
Incomplete supply coverage cannot become evidence that no other callback exists.

## Source access and result eligibility

Subject restoration uses the exact scope that admitted the subject.
`CallbackEvidenceDomain` selects owned production, test, and generated sources across the imported workspace.
This source access excludes library bodies and keeps the original read authority and work and time limits.

Result eligibility still uses the caller's selected scope and filters.
`CallbackResultMembership` binds an admitted result site to the subject, authority, result domain, and relationship direction.
CALLERS uses a source occurrence or an actual callback invocation.
CALLEES uses the named target or anonymous body of an exhaustive supplier inventory.

Callback evidence outside that result scope can prove an invocation inside it.
An exhaustive graph with no eligible result site permits exclusion.
An incomplete graph or missing membership remains a finite rejection.
Query completion examines graph proof before membership, so an existing callback failure keeps its cause.

The adapter charges source reads and membership reads to the same collector.
Reference reuse keeps callback evidence separate from ordinary REFERENCES and from other evidence domains.
Neither broader evidence access nor lexical ownership creates a named caller edge.

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

## Failure subjects identify the unproved fact

Callback completion failures carry a typed callable or callback subject beside the original cause and observation positions.
The subject keeps exact file and source ranges for the failed callable or callback site and body.
A subject identifies the proof failure without a claim of runtime activation or a new source selector.
