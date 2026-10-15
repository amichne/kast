---
type: Module Guide
title: "Kast symbol"
description: "Defines declaration discovery and exact identity, with domain services and Kotlin compiler adapters for IntelliJ IDEA."
resource: "repo://symbol"
tags: [kast, navigation]
timestamp: "2026-10-14T23:25:10Z"
source_revision: e014a489bfd7e69cbf2c4b61140ee771d857a03e
code_sources:
  - path: symbol/contract/build.gradle.kts
  - path: symbol/contract/src/main/kotlin/io/github/amichne/kast/symbol/contract/CanonicalCompilerSignature.kt
  - path: symbol/contract/src/main/kotlin/io/github/amichne/kast/symbol/contract/LocalDeclarationAddress.kt
  - path: symbol/contract/src/main/kotlin/io/github/amichne/kast/symbol/contract/discovery/SymbolDiscoveryRequest.kt
  - path: symbol/intellij/build.gradle.kts
  - path: symbol/intellij/src/main/kotlin/io/github/amichne/kast/symbol/intellij/IntellijIncrementalDeclarationDiscovery.kt
  - path: symbol/intellij/src/main/kotlin/io/github/amichne/kast/symbol/intellij/IntellijPsiExactDeclarationLookup.kt
  - path: symbol/intellij/src/main/kotlin/io/github/amichne/kast/symbol/intellij/IntellijScopedDeclarationEnumeration.kt
  - path: symbol/service/build.gradle.kts
  - path: symbol/service/src/main/kotlin/io/github/amichne/kast/symbol/service/ExactRevalidationService.kt
  - path: symbol/service/src/main/kotlin/io/github/amichne/kast/symbol/service/SymbolDiscoveryService.kt
  - path: symbol/service/src/main/kotlin/io/github/amichne/kast/symbol/service/SymbolExactService.kt
---

<!-- AGENTS.md: generated navigation; keep this file checked in -->
<!-- generated: 2026-10-14 | hash: e9e206c7a991 -->

# symbol

## Purpose

Defines declaration discovery and exact identity, with domain services and Kotlin compiler adapters for IntelliJ IDEA.

## Key Files

- [SymbolDiscoveryRequest.kt](contract/src/main/kotlin/io/github/amichne/kast/symbol/contract/discovery/SymbolDiscoveryRequest.kt) - discovery scope and constraints.
- [CanonicalCompilerSignature.kt](contract/src/main/kotlin/io/github/amichne/kast/symbol/contract/CanonicalCompilerSignature.kt) - compiler-grounded declaration identity.
- [LocalDeclarationAddress.kt](contract/src/main/kotlin/io/github/amichne/kast/symbol/contract/LocalDeclarationAddress.kt) - qualified and local declaration addresses.
- [SymbolDiscoveryService.kt](service/src/main/kotlin/io/github/amichne/kast/symbol/service/SymbolDiscoveryService.kt) - current-authority discovery admission.
- [SymbolExactService.kt](service/src/main/kotlin/io/github/amichne/kast/symbol/service/SymbolExactService.kt) - exact lookup admission.
- [ExactRevalidationService.kt](service/src/main/kotlin/io/github/amichne/kast/symbol/service/ExactRevalidationService.kt) - fresh reacquisition of a detached exact locator.
- [IntellijScopedDeclarationEnumeration.kt](intellij/src/main/kotlin/io/github/amichne/kast/symbol/intellij/IntellijScopedDeclarationEnumeration.kt) - scope and kind constraints before enumeration.
- [IntellijIncrementalDeclarationDiscovery.kt](intellij/src/main/kotlin/io/github/amichne/kast/symbol/intellij/IntellijIncrementalDeclarationDiscovery.kt) - bounded discovery with structural continuation positions.
- [IntellijPsiExactDeclarationLookup.kt](intellij/src/main/kotlin/io/github/amichne/kast/symbol/intellij/IntellijPsiExactDeclarationLookup.kt) - declaration ancestry within one file.

## Subdirectories

- [contract/](contract/) - identities, selectors, discovery requests, and outcomes.
- [service/](service/) - discovery, exact lookup, and revalidation.
- [intellij/](intellij/) - PSI enumeration, K2 identity, and exact resolution.

## Entry Points

- Gradle projects: `:symbol:contract`, `:symbol:service`, and `:symbol:intellij`.

## Navigation Hints

- For excessive candidate work, examine scope and kind admission before compiler projection.
- For ambiguity or stale identity, examine contract and service admission before the compiler adapter.
- Read the [root guide](../AGENTS.md) for engineering rules and optional OpenWiki retrieval.

## Exact declaration boundary

- [IntellijPsiExactDeclarationLookup.kt](intellij/src/main/kotlin/io/github/amichne/kast/symbol/intellij/IntellijPsiExactDeclarationLookup.kt) owns file-bounded declaration ancestry. `PsiNamedElement` also includes files and directories: stop before `PsiFileSystemItem`, never treat these containers as declarations, and never request their parents. Continue checking all in-file ancestors; a first match must not erase ambiguity, stale PSI, or unsupported matching declarations. File validity and scope remain admission checks in `findLive`.
- [IntellijExactDeclarationFileBoundaryTest.kt](intellij/src/test/kotlin/io/github/amichne/kast/symbol/intellij/IntellijExactDeclarationFileBoundaryTest.kt) proves traversal limits, retained evidence, and closed rejection behavior with strict PSI observations. [IntellijExactDeclarationPhysicalPsiTest.kt](intellij/src/test/kotlin/io/github/amichne/kast/symbol/intellij/IntellijExactDeclarationPhysicalPsiTest.kt) exercises the production lookup with real file-backed Kotlin and Java PSI and actual containing directories. Standalone doubles or in-memory files alone cannot prove filesystem-boundary behavior.
- Start validation with `./gradlew :symbol:intellij:test --tests '*IntellijExactDeclaration*'`, then the owning module and affected consumers. These tests establish PSI lookup behavior, not K2 identity availability or installed-host qualification; retain those separate proofs.
