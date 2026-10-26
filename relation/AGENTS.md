---
type: Module Guide
title: "Kast relation"
description: "Defines one-hop semantic relationships, callback evidence, and value-flow facts with compiler-backed IntelliJ adapters."
resource: "repo://relation"
tags: [kast, navigation]
timestamp: "2026-10-26T11:36:07Z"
source_revision: 371fb8bfcb7c2c22a12ab373b3d7c49d1fbb528a
code_sources:
  - path: relation/contract/src/main/kotlin/io/github/amichne/kast/relation/contract/CompleteCallbackSourceRelation.kt
  - path: relation/contract/src/main/kotlin/io/github/amichne/kast/relation/contract/CompleteNamedCallbackSourceReference.kt
  - path: relation/contract/build.gradle.kts
  - path: relation/contract/src/main/kotlin/io/github/amichne/kast/relation/contract/CompleteStaticCallbackGraph.kt
  - path: relation/contract/src/main/kotlin/io/github/amichne/kast/relation/contract/RelationCallbackObservation.kt
  - path: relation/contract/src/main/kotlin/io/github/amichne/kast/relation/contract/RelationReferenceOccurrence.kt
  - path: relation/contract/src/main/kotlin/io/github/amichne/kast/relation/contract/RelationRequest.kt
  - path: relation/contract/src/main/kotlin/io/github/amichne/kast/relation/contract/RepresentationModel.kt
  - path: relation/contract/src/main/kotlin/io/github/amichne/kast/relation/contract/ValueFlow.kt
  - path: relation/intellij/build.gradle.kts
  - path: relation/intellij/src/main/kotlin/io/github/amichne/kast/relation/intellij/IntellijCallbackInvocationFlow.kt
  - path: relation/intellij/src/main/kotlin/io/github/amichne/kast/relation/intellij/IntellijCallbackSummaryReadmission.kt
  - path: relation/intellij/src/main/kotlin/io/github/amichne/kast/relation/intellij/IntellijK2RelationSearch.kt
  - path: relation/service/build.gradle.kts
  - path: relation/service/src/main/kotlin/io/github/amichne/kast/relation/service/RelationService.kt
  - path: relation/service/src/test/kotlin/io/github/amichne/kast/relation/service/RelationServiceTest.kt
---

<!-- AGENTS.md: generated navigation; keep this file checked in -->
<!-- generated: 2026-10-14 | hash: e9e206c7a991 -->

# relation

## Purpose

Defines one-hop semantic relationships, callback evidence, and value-flow facts with compiler-backed IntelliJ adapters.

## Key Files

- [RelationRequest.kt](contract/src/main/kotlin/io/github/amichne/kast/relation/contract/RelationRequest.kt) - exact subjects and independent expansion domains.
- [RelationReferenceOccurrence.kt](contract/src/main/kotlin/io/github/amichne/kast/relation/contract/RelationReferenceOccurrence.kt) - target identity and declaration or file ownership.
- [RelationCallbackObservation.kt](contract/src/main/kotlin/io/github/amichne/kast/relation/contract/RelationCallbackObservation.kt) - callback observations separate from named caller edges.
- [CompleteStaticCallbackGraph.kt](contract/src/main/kotlin/io/github/amichne/kast/relation/contract/CompleteStaticCallbackGraph.kt) - complete callback graph admission.
- [CompleteCallbackSourceRelation.kt](contract/src/main/kotlin/io/github/amichne/kast/relation/contract/CompleteCallbackSourceRelation.kt) - exact source relationships with explicit callback-effect gaps.
- [CompleteNamedCallbackSourceReference.kt](contract/src/main/kotlin/io/github/amichne/kast/relation/contract/CompleteNamedCallbackSourceReference.kt) - exact callable references independent of execution completeness.
- [ValueFlow.kt](contract/src/main/kotlin/io/github/amichne/kast/relation/contract/ValueFlow.kt) - bounded one-hop transfers and unresolved obligations.
- [RepresentationModel.kt](contract/src/main/kotlin/io/github/amichne/kast/relation/contract/RepresentationModel.kt) - reviewed representation models.
- [RelationService.kt](service/src/main/kotlin/io/github/amichne/kast/relation/service/RelationService.kt) - authority admission before and after compiler work.
- [IntellijK2RelationSearch.kt](intellij/src/main/kotlin/io/github/amichne/kast/relation/intellij/IntellijK2RelationSearch.kt) - native relationship search.
- [IntellijCallbackInvocationFlow.kt](intellij/src/main/kotlin/io/github/amichne/kast/relation/intellij/IntellijCallbackInvocationFlow.kt) - native callback invocation evidence.
- [IntellijCallbackSummaryReadmission.kt](intellij/src/main/kotlin/io/github/amichne/kast/relation/intellij/IntellijCallbackSummaryReadmission.kt) - fresh endpoint admission for cached summaries.

## Subdirectories

- [contract/](contract/) - relation facts, callback graphs, model evidence, and finite failures.
- [service/](service/) - relation admission and model transformations.
- [intellij/](intellij/) - K2 search, callback supply, native value sites, and bounded inventories.

## Entry Points

- Gradle projects: `:relation:contract`, `:relation:service`, and `:relation:intellij`.
- Native fixtures live in `intellij/src/nativeTest`; their evidence differs from ordinary JVM tests.
- `:relation:intellij:compileDeclaredEffectFixture` owns the immutable dependency archive for relation and hosted fixtures. Each case copies that archive into its temporary directory.
- `check` and `scopedCheck` include `dependencyFixtureTest` for archive isolation.
- [RelationServiceTest.kt](service/src/test/kotlin/io/github/amichne/kast/relation/service/RelationServiceTest.kt) - relation admission and finite outcomes.

## Navigation Hints

- For missing callers, trace lexical ownership, callback supply, compiler bindings, and graph qualifications.
- For reuse, trace dependency continuity in topology and fresh endpoint admission here.
- The query engine owns multi-hop scheduling, paging, and branch conservation. Relation supplies one-hop facts.
- Read the [root guide](../AGENTS.md) for engineering rules and optional OpenWiki retrieval.
