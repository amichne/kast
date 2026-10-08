<!-- AGENTS.md: generated navigation; keep this file checked in -->
<!-- generated: 2026-09-30 | hash: e9e206c7a991 -->

# symbol

## Purpose

Defines symbol discovery and exact declaration identity, provides domain services, and implements compiler/PSI-backed IntelliJ adapters.

## Key Files

- [SymbolDiscoveryProgress.kt](contract/src/main/kotlin/io/github/amichne/kast/symbol/contract/SymbolDiscoveryProgress.kt) - declared source universe, coverage, and detached advancing remainder.
- [IntellijIncrementalDeclarationDiscovery.kt](intellij/src/main/kotlin/io/github/amichne/kast/symbol/intellij/IntellijIncrementalDeclarationDiscovery.kt) - lexical unopened partitions and active-file structural positions for bounded Kotlin discovery.

- [ExactRevalidation.kt](contract/src/main/kotlin/io/github/amichne/kast/symbol/contract/ExactRevalidation.kt) - detached locator and fresh compiler revalidation contracts.
- [ExactRevalidationService.kt](service/src/main/kotlin/io/github/amichne/kast/symbol/service/ExactRevalidationService.kt) - read-only reacquisition with current content, scope and compiler identity proof.

- [IntellijCallableIdentity.kt](intellij/src/main/kotlin/io/github/amichne/kast/symbol/intellij/IntellijCallableIdentity.kt) - native and compiler-owned enum-entry member identity.

- [CanonicalSymbolId.kt](contract/src/main/kotlin/io/github/amichne/kast/symbol/contract/CanonicalSymbolId.kt) - snapshot-local canonical equality without widening selector scope.
- [LocalDeclarationAddress.kt](contract/src/main/kotlin/io/github/amichne/kast/symbol/contract/LocalDeclarationAddress.kt) - pure qualified/local address alternatives, exact compiler and lexical ownership, and closed admission failures.
- [CanonicalCompilerSignatureEncoding.kt](contract/src/main/kotlin/io/github/amichne/kast/symbol/contract/CanonicalCompilerSignatureEncoding.kt) - qualified byte compatibility and versioned local signature encoding.
- [BoundedLexicalCandidates.kt](intellij/src/main/kotlin/io/github/amichne/kast/symbol/intellij/BoundedLexicalCandidates.kt) - bounded lexical ranking before compiler projection.
- [contract/src/main/kotlin/io/github/amichne/kast/symbol/contract/CanonicalCompilerSignature.kt](contract/src/main/kotlin/io/github/amichne/kast/symbol/contract/CanonicalCompilerSignature.kt) - canonical signature identity.
- [contract/src/main/kotlin/io/github/amichne/kast/symbol/contract/ExactDeclarationSelector.kt](contract/src/main/kotlin/io/github/amichne/kast/symbol/contract/ExactDeclarationSelector.kt) - exact selector.
- [contract/src/main/kotlin/io/github/amichne/kast/symbol/contract/discovery/SymbolDiscoveryRequest.kt](contract/src/main/kotlin/io/github/amichne/kast/symbol/contract/discovery/SymbolDiscoveryRequest.kt) - discovery request.
- [service/src/main/kotlin/io/github/amichne/kast/symbol/service/SymbolDiscoveryService.kt](service/src/main/kotlin/io/github/amichne/kast/symbol/service/SymbolDiscoveryService.kt) - discovery orchestration.
- [service/src/main/kotlin/io/github/amichne/kast/symbol/service/SymbolExactService.kt](service/src/main/kotlin/io/github/amichne/kast/symbol/service/SymbolExactService.kt) - exact lookup orchestration.
- [intellij/src/main/kotlin/io/github/amichne/kast/symbol/intellij/exact/IntellijKotlinCompilerSymbolLookup.kt](intellij/src/main/kotlin/io/github/amichne/kast/symbol/intellij/exact/IntellijKotlinCompilerSymbolLookup.kt) - compiler lookup.

- [intellij/src/main/kotlin/io/github/amichne/kast/symbol/intellij/IntellijScopedDeclarationEnumeration.kt](intellij/src/main/kotlin/io/github/amichne/kast/symbol/intellij/IntellijScopedDeclarationEnumeration.kt) - constraint-first scoped declaration discovery.

## Subdirectories

- `contract` - exact and discovery identities, requests, facts, and outcomes.
- `service` - discovery and exact services.
- `intellij/src/main/kotlin/io/github/amichne/kast/symbol/intellij/discovery` - search and scope compilation.
- `intellij/src/main/kotlin/io/github/amichne/kast/symbol/intellij/exact` - compiler/PSI exact resolution.

## Entry Points

- Gradle projects: `:symbol:contract`, `:symbol:service`, `:symbol:intellij`.

## Navigation Hints

- Start with the [repository knowledge](../openwiki/modules/semantic-reads.md) and follow its `code_sources` for source evidence. Root engineering rules remain authoritative; this map adds navigation only.

- Use discovery to narrow candidates, then retain an exact selector through subsequent operations.
- For ambiguity, start in contract/service admission before reading PSI or compiler adapters.

## Exact declaration boundary

- [IntellijPsiExactDeclarationLookup.kt](intellij/src/main/kotlin/io/github/amichne/kast/symbol/intellij/IntellijPsiExactDeclarationLookup.kt) owns file-bounded declaration ancestry. `PsiNamedElement` also includes files and directories: stop before `PsiFileSystemItem`, never treat these containers as declarations, and never request their parents. Continue checking all in-file ancestors; a first match must not erase ambiguity, stale PSI, or unsupported matching declarations. File validity and scope remain admission checks in `findLive`.
- [IntellijExactDeclarationFileBoundaryTest.kt](intellij/src/test/kotlin/io/github/amichne/kast/symbol/intellij/IntellijExactDeclarationFileBoundaryTest.kt) proves traversal limits, retained evidence, and closed rejection behavior with strict PSI observations. [IntellijExactDeclarationPhysicalPsiTest.kt](intellij/src/test/kotlin/io/github/amichne/kast/symbol/intellij/IntellijExactDeclarationPhysicalPsiTest.kt) exercises the production lookup with real file-backed Kotlin and Java PSI and actual containing directories. Standalone doubles or in-memory files alone cannot prove filesystem-boundary behavior.
- Start validation with `./gradlew :symbol:intellij:test --tests '*IntellijExactDeclaration*'`, then the owning module and affected consumers. These tests establish PSI lookup behavior, not K2 identity availability or installed-host qualification; retain those separate proofs.
