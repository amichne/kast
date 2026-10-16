---
type: System Explanation
title: Declaration discovery and exact identity
description: Explains constraint-first discovery, bounded compiler refinement, exact
  and local declaration identity, and fresh locator revalidation.
tags:
- kast
- semantics
code_sources:
- path: symbol/service/src/main/kotlin/io/github/amichne/kast/symbol/service/SymbolDiscoveryService.kt
- path: symbol/service/src/main/kotlin/io/github/amichne/kast/symbol/service/SymbolExactService.kt
- path: symbol/service/src/main/kotlin/io/github/amichne/kast/symbol/service/ExactRevalidationService.kt
- path: symbol/contract/src/main/kotlin/io/github/amichne/kast/symbol/contract/LocalDeclarationAddress.kt
- path: symbol/intellij/src/main/kotlin/io/github/amichne/kast/symbol/intellij/IntellijScopedDeclarationEnumeration.kt
- path: symbol/intellij/src/main/kotlin/io/github/amichne/kast/symbol/intellij/IntellijIncrementalDeclarationDiscovery.kt
- path: symbol/intellij/src/main/kotlin/io/github/amichne/kast/symbol/intellij/IntellijPsiExactDeclarationLookup.kt
- path: symbol/intellij/src/main/kotlin/io/github/amichne/kast/symbol/intellij/IntellijClasslikeAstBinding.kt
- path: symbol/intellij/src/main/kotlin/io/github/amichne/kast/symbol/intellij/IntellijKotlinAstParserBinding.kt
- path: symbol/contract/src/main/kotlin/io/github/amichne/kast/symbol/contract/CandidateSelector.kt
- path: symbol/intellij/src/main/kotlin/io/github/amichne/kast/symbol/intellij/BoundedLexicalCandidates.kt
- path: symbol/intellij/src/test/kotlin/io/github/amichne/kast/symbol/intellij/IntellijExactDeclarationPhysicalPsiTest.kt
- path: symbol/intellij/src/test/kotlin/io/github/amichne/kast/symbol/intellij/DiscoveryDirectoryWorkTest.kt
- path: symbol/intellij/src/test/kotlin/io/github/amichne/kast/symbol/intellij/ScopedExactReacquisitionBudgetTest.kt
verified:
  - by: openwiki/0.6.1
    at: 2026-10-15T00:33:05.932Z
sources:
  - id: openwiki-source-031dda37f86087dbac51fa2d
    resource: repo://symbol/intellij/src/main/kotlin/io/github/amichne/kast/symbol/intellij/IntellijIncrementalDeclarationDiscovery.kt
  - id: openwiki-source-344be58a4219db5fdc42ff47
    resource: repo://symbol/intellij/src/main/kotlin/io/github/amichne/kast/symbol/intellij/IntellijPsiExactDeclarationLookup.kt
  - id: openwiki-source-fa61902bcb0bcf62e8d3fbdd
    resource: repo://symbol/intellij/src/main/kotlin/io/github/amichne/kast/symbol/intellij/IntellijScopedDeclarationEnumeration.kt
  - id: openwiki-source-d3e7e4c127d3b87501a5acd5
    resource: repo://symbol/service/src/main/kotlin/io/github/amichne/kast/symbol/service/ExactRevalidationService.kt
generated: { by: "codex", at: "2026-10-15T00:33:05.932Z" }
---

# Declaration discovery and exact identity

## Constrain the source universe first

Discovery starts with an admitted workspace authority, source scope, name or text intent, and declaration constraints.
The source plan chooses eligible files, source sets, ownership, and declaration families before native enumeration consumes capacity.
`IntellijScopedDeclarationEnumeration` keeps those restrictions through native collection.
Compiler refinement follows cheap eligibility decisions.

Incremental declaration discovery keeps unopened partitions and structural positions for the active file.
The continuation describes unexamined source work rather than a display-page offset.
Bounded lexical candidates can rank eligible declarations before expensive compiler projection.
Unrelated files, names, and kinds must not exhaust the eligible result allowance.

## Exact identity is stronger than discovery location

A candidate location and name do not establish exact compiler identity.
Exact lookup admits the source file and native scope, examines declaration ancestry inside that file, and detaches the result.
`PsiNamedElement` also includes filesystem items.
`IntellijPsiExactDeclarationLookup` stops before those containers and never treats a directory as a declaration.
It keeps ambiguity and stale or unsupported matching ancestors visible instead of accepting the first convenient match.

Qualified signatures and local declaration addresses preserve different identity forms.
A local address keeps compiler and lexical ownership within the original declaration context.
Display names and offsets alone do not establish equality across those forms.
The same identity vocabulary travels through source and relation projections.

## Reacquire through current compiler evidence

`ExactRevalidationService` accepts a detached locator and a separately admitted current live authority.
It requires the original workspace and host owner before compiler confirmation.
The selected policy compares either original-document evidence or current declaration identity.
The service repeats current-authority admission before it issues the reacquired selector.
A matching detached epoch number cannot bypass that sequence.

The classlike AST path has an admitted parser and source binding.
Its qualification assumes trusted bundled parser behavior, as the runtime qualification report states.
A configured Java agent selects the existing fallback policy rather than proving arbitrary transformed parser equivalence.

The tests distinguish excluded-input work, physical PSI ancestry, exact identity, and fresh reacquisition.
Standalone syntax presence does not establish installed-host compiler identity.

## Focused tests

- [IntellijExactDeclarationPhysicalPsiTest.kt](../../../symbol/intellij/src/test/kotlin/io/github/amichne/kast/symbol/intellij/IntellijExactDeclarationPhysicalPsiTest.kt)
- [DiscoveryDirectoryWorkTest.kt](../../../symbol/intellij/src/test/kotlin/io/github/amichne/kast/symbol/intellij/DiscoveryDirectoryWorkTest.kt)
- [ScopedExactReacquisitionBudgetTest.kt](../../../symbol/intellij/src/test/kotlin/io/github/amichne/kast/symbol/intellij/ScopedExactReacquisitionBudgetTest.kt)

## Source owners

- [SymbolDiscoveryService.kt](../../../symbol/service/src/main/kotlin/io/github/amichne/kast/symbol/service/SymbolDiscoveryService.kt)
- [SymbolExactService.kt](../../../symbol/service/src/main/kotlin/io/github/amichne/kast/symbol/service/SymbolExactService.kt)
- [ExactRevalidationService.kt](../../../symbol/service/src/main/kotlin/io/github/amichne/kast/symbol/service/ExactRevalidationService.kt)
- [LocalDeclarationAddress.kt](../../../symbol/contract/src/main/kotlin/io/github/amichne/kast/symbol/contract/LocalDeclarationAddress.kt)
- [IntellijScopedDeclarationEnumeration.kt](../../../symbol/intellij/src/main/kotlin/io/github/amichne/kast/symbol/intellij/IntellijScopedDeclarationEnumeration.kt)
- [IntellijIncrementalDeclarationDiscovery.kt](../../../symbol/intellij/src/main/kotlin/io/github/amichne/kast/symbol/intellij/IntellijIncrementalDeclarationDiscovery.kt)
- [IntellijPsiExactDeclarationLookup.kt](../../../symbol/intellij/src/main/kotlin/io/github/amichne/kast/symbol/intellij/IntellijPsiExactDeclarationLookup.kt)
- [IntellijClasslikeAstBinding.kt](../../../symbol/intellij/src/main/kotlin/io/github/amichne/kast/symbol/intellij/IntellijClasslikeAstBinding.kt)
- [IntellijKotlinAstParserBinding.kt](../../../symbol/intellij/src/main/kotlin/io/github/amichne/kast/symbol/intellij/IntellijKotlinAstParserBinding.kt)
- [CandidateSelector.kt](../../../symbol/contract/src/main/kotlin/io/github/amichne/kast/symbol/contract/CandidateSelector.kt)
- [BoundedLexicalCandidates.kt](../../../symbol/intellij/src/main/kotlin/io/github/amichne/kast/symbol/intellij/BoundedLexicalCandidates.kt)

## Read next

- [Source anchors](../source/anchors-and-paging.md)
- [Relationship evidence](../relation/named-relations.md)
- [Workspace authority](../workspace/read-authority.md)
