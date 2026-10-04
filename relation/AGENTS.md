<!-- AGENTS.md: generated navigation; keep this file checked in -->
<!-- generated: 2026-10-03 | hash: e9e206c7a991 -->

# relation

## Purpose

Defines semantic relationship requests and facts, coordinates relation reads, and adapts Kotlin K2/IntelliJ resolution.

## Key Files

- [ValueSite.kt](contract/src/main/kotlin/io/github/amichne/kast/relation/contract/ValueSite.kt) and [ValueFlow.kt](contract/src/main/kotlin/io/github/amichne/kast/relation/contract/ValueFlow.kt) - exact compiler sites, bounded one-hop transfer reads, work receipts, and finite unresolved obligations.
- [ValueProducerSeed.kt](contract/src/main/kotlin/io/github/amichne/kast/relation/contract/ValueProducerSeed.kt) - exact producer invocation admission and fresh model endpoint revalidation.
- [RepresentationModel.kt](contract/src/main/kotlin/io/github/amichne/kast/relation/contract/RepresentationModel.kt), [RepresentationProvenance.kt](contract/src/main/kotlin/io/github/amichne/kast/relation/contract/RepresentationProvenance.kt), and [ValueBoundary.kt](contract/src/main/kotlin/io/github/amichne/kast/relation/contract/ValueBoundary.kt) - reviewed model bindings, guarded representation histories, and explicit boundary obligations.
- [RelationScopeExclusion.kt](contract/src/main/kotlin/io/github/amichne/kast/relation/contract/RelationScopeExclusion.kt) - compiler-confirmed domain exclusions remain distinct from unsupported in-domain evidence.
- [IntellijValueFlowCompilerAdapter.kt](intellij/src/main/kotlin/io/github/amichne/kast/relation/intellij/IntellijValueFlowCompilerAdapter.kt) - native seed, revalidation, and one-hop flow effects under current authority and bounded grants.
- [IntellijValueSiteRevalidationNative.kt](intellij/src/main/kotlin/io/github/amichne/kast/relation/intellij/IntellijValueSiteRevalidationNative.kt) and [NativeValueSiteRestorationFailure.kt](intellij/src/main/kotlin/io/github/amichne/kast/relation/intellij/NativeValueSiteRestorationFailure.kt) - exact Kotlin PSI shape restoration precedes native role and K2 binding checks, with bounded restoration outcomes.

- [RelationReferenceOccurrence.kt](contract/src/main/kotlin/io/github/amichne/kast/relation/contract/RelationReferenceOccurrence.kt) - compiler-confirmed target and occurrence identity with declaration, file, or unavailable ownership.
- [IntellijReferenceInventory.kt](intellij/src/main/kotlin/io/github/amichne/kast/relation/intellij/IntellijReferenceInventory.kt) - bounded detached native locator inventory under the original scope.
- [RelationOmissionEvidence.kt](contract/src/main/kotlin/io/github/amichne/kast/relation/contract/RelationOmissionEvidence.kt) - provider/version and measured or unmeasured omission evidence.
- [contract/src/main/kotlin/io/github/amichne/kast/relation/contract/RelationRequest.kt](contract/src/main/kotlin/io/github/amichne/kast/relation/contract/RelationRequest.kt) - relation requests retain an expansion domain independently of the subject selection scope.
- [contract/src/main/kotlin/io/github/amichne/kast/relation/contract/RelationFact.kt](contract/src/main/kotlin/io/github/amichne/kast/relation/contract/RelationFact.kt) - evidence model.
- [service/src/main/kotlin/io/github/amichne/kast/relation/service/RelationService.kt](service/src/main/kotlin/io/github/amichne/kast/relation/service/RelationService.kt) - orchestration.
- [intellij/src/main/kotlin/io/github/amichne/kast/relation/intellij/IntellijK2RelationSearch.kt](intellij/src/main/kotlin/io/github/amichne/kast/relation/intellij/IntellijK2RelationSearch.kt) - compiler-backed search.
- [intellij/src/main/kotlin/io/github/amichne/kast/relation/intellij/IntellijK2SymbolIdentity.kt](intellij/src/main/kotlin/io/github/amichne/kast/relation/intellij/IntellijK2SymbolIdentity.kt) - compiler-grounded identities for relation targets, including anonymous implementations.
- [intellij/src/main/kotlin/io/github/amichne/kast/relation/intellij/IntellijRelationScopeCompiler.kt](intellij/src/main/kotlin/io/github/amichne/kast/relation/intellij/IntellijRelationScopeCompiler.kt) - scope refinement.

## Subdirectories

- `contract` - operations, requests, and facts.
- `service` - relation orchestration.
- `intellij` - K2 identity, scope, provider ordering, collection, and projection.

## Entry Points

- Gradle projects: `:relation:contract`, `:relation:service`, `:relation:intellij`.

## Navigation Hints

- Start with the [repository knowledge](../openwiki/modules/semantic-reads.md) and follow its `code_sources` for source evidence. Root engineering rules remain authoritative; this map adds navigation only.

- Start with relation kind and scope in the contract, then service; open IntelliJ providers for compiler-specific resolution.
- Value-flow scheduling, branch conservation, paging, and checkpoints belong to the existing query engine; relation owns exact one-hop compiler facts and reviewed model evidence.
