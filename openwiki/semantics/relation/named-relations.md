---
type: System Explanation
title: Named relationships and occurrences
description: Explains exact relation subjects, native destination scopes, compiler-confirmed
  candidates, occurrence ownership, and qualified omissions.
tags:
- kast
- semantics
code_sources:
- path: relation/contract/src/main/kotlin/io/github/amichne/kast/relation/contract/RelationRequest.kt
- path: relation/contract/src/main/kotlin/io/github/amichne/kast/relation/contract/RelationReferenceOccurrence.kt
- path: relation/contract/src/main/kotlin/io/github/amichne/kast/relation/contract/RelationScopeExclusion.kt
- path: relation/contract/src/main/kotlin/io/github/amichne/kast/relation/contract/RelationOmissionEvidence.kt
- path: relation/service/src/main/kotlin/io/github/amichne/kast/relation/service/RelationService.kt
- path: relation/intellij/src/main/kotlin/io/github/amichne/kast/relation/intellij/IntellijK2RelationSearch.kt
- path: relation/intellij/src/main/kotlin/io/github/amichne/kast/relation/intellij/RelationFileEnumerationPlan.kt
- path: relation/intellij/src/main/kotlin/io/github/amichne/kast/relation/intellij/IntellijRelationScopeCompiler.kt
- path: relation/contract/src/main/kotlin/io/github/amichne/kast/relation/contract/CallbackArgumentBinding.kt
- path: relation/intellij/src/main/kotlin/io/github/amichne/kast/relation/intellij/AdmittedRelationPreparation.kt
- path: relation/service/src/test/kotlin/io/github/amichne/kast/relation/service/RelationServiceTest.kt
- path: relation/intellij/src/test/kotlin/io/github/amichne/kast/relation/intellij/RelationFileEnumerationTest.kt
- path: relation/intellij/src/test/kotlin/io/github/amichne/kast/relation/intellij/RelationScopeExclusionTest.kt
sources:
  - id: openwiki-source-2002ffb478926f34bc0304a5
    resource: repo://relation/contract/src/main/kotlin/io/github/amichne/kast/relation/contract/RelationOmissionEvidence.kt
  - id: openwiki-source-98a8ae5650f214661d5db777
    resource: repo://relation/contract/src/main/kotlin/io/github/amichne/kast/relation/contract/RelationReferenceOccurrence.kt
  - id: openwiki-source-f9d18389170c5d611434b1b2
    resource: repo://relation/contract/src/main/kotlin/io/github/amichne/kast/relation/contract/RelationRequest.kt
  - id: openwiki-source-cc2bd1ef3fe25286c333cef2
    resource: repo://relation/contract/src/main/kotlin/io/github/amichne/kast/relation/contract/RelationScopeExclusion.kt
  - id: openwiki-source-ad9fc4007f8026e7d56a010c
    resource: repo://relation/intellij/src/main/kotlin/io/github/amichne/kast/relation/intellij/RelationFileEnumerationPlan.kt
  - id: openwiki-source-92edd83497ca3a9bab136f66
    resource: repo://relation/service/src/main/kotlin/io/github/amichne/kast/relation/service/RelationService.kt
generated: { by: "codex", at: "2026-10-15T00:33:05.932Z" }
verified:
  - by: openwiki/0.6.1
    at: 2026-10-30T23:25:02.193Z
---

# Named relationships and occurrences

## Select the subject and destination domain separately

The request keeps an exact subject selector with its original authority and source constraints.
Its expansion domain selects the native relationship destinations.
The source scope that found the subject does not automatically constrain every later destination.
`RelationFileEnumerationPlan` and scope compilation carry cheap file, source-set, and ownership restrictions into native collection.

The selected meaning distinguishes references, callers, callees, implementations, overrides, and related semantic reads.
Each provider must establish that meaning through compiler evidence.
A matching name or text occurrence does not establish a call edge.

## Admit current facts around native work

`RelationService` examines the exact authority before the compiler port runs.
It repeats that admission after native work.
The compiler output must match the request's subject, meaning, scope, authority, coverage, and continuation facts.
A foreign or internally inconsistent batch becomes a contract rejection.

Native search first limits eligible inputs, then refines candidates through K2.
An excluded file or declaration family must not consume the eligible result capacity.
Collected candidates remain weaker evidence until the compiler confirms the target and relationship.
Bounded inventories and advancing positions keep resumed pages from replaying already-consumed work.

## Occurrences, exclusions, and omissions

`RelationReferenceOccurrence` keeps the confirmed target and occurrence location.
Ownership can identify a declaration, a file, or an explicit unavailable owner.
A file-level occurrence does not invent a declaration endpoint merely to fit a graph row.

Scope exclusions establish that a confirmed destination lies outside the admitted expansion domain.
Provider omissions describe unsupported or unproved evidence inside the requested question.
These facts have different meanings and must remain distinct through query and traversal projection.
Measured and unmeasured omissions keep their provider and qualification evidence.

For missing callers, separate named-call ownership from callback supply and invocation proof.
For incomplete results, examine domain admission, provider coverage, and advancing inventory state before widening the scan.
Source-backed relationship facts establish compiler-static structure rather than runtime reachability.

## Focused tests

- [RelationServiceTest.kt](../../../relation/service/src/test/kotlin/io/github/amichne/kast/relation/service/RelationServiceTest.kt)
- [RelationFileEnumerationTest.kt](../../../relation/intellij/src/test/kotlin/io/github/amichne/kast/relation/intellij/RelationFileEnumerationTest.kt)
- [RelationScopeExclusionTest.kt](../../../relation/intellij/src/test/kotlin/io/github/amichne/kast/relation/intellij/RelationScopeExclusionTest.kt)

## Source owners

- [RelationRequest.kt](../../../relation/contract/src/main/kotlin/io/github/amichne/kast/relation/contract/RelationRequest.kt)
- [RelationReferenceOccurrence.kt](../../../relation/contract/src/main/kotlin/io/github/amichne/kast/relation/contract/RelationReferenceOccurrence.kt)
- [RelationScopeExclusion.kt](../../../relation/contract/src/main/kotlin/io/github/amichne/kast/relation/contract/RelationScopeExclusion.kt)
- [RelationOmissionEvidence.kt](../../../relation/contract/src/main/kotlin/io/github/amichne/kast/relation/contract/RelationOmissionEvidence.kt)
- [RelationService.kt](../../../relation/service/src/main/kotlin/io/github/amichne/kast/relation/service/RelationService.kt)
- [IntellijK2RelationSearch.kt](../../../relation/intellij/src/main/kotlin/io/github/amichne/kast/relation/intellij/IntellijK2RelationSearch.kt)
- [RelationFileEnumerationPlan.kt](../../../relation/intellij/src/main/kotlin/io/github/amichne/kast/relation/intellij/RelationFileEnumerationPlan.kt)
- [IntellijRelationScopeCompiler.kt](../../../relation/intellij/src/main/kotlin/io/github/amichne/kast/relation/intellij/IntellijRelationScopeCompiler.kt)
- [CallbackArgumentBinding.kt](../../../relation/contract/src/main/kotlin/io/github/amichne/kast/relation/contract/CallbackArgumentBinding.kt)
- [AdmittedRelationPreparation.kt](../../../relation/intellij/src/main/kotlin/io/github/amichne/kast/relation/intellij/AdmittedRelationPreparation.kt)

## Read next

- [Callbacks](callback-supply-and-binding.md)
- [Traversal](../traversal/frontiers-and-continuations.md)
- [TRACE](../../queries/trace-and-completion.md)
