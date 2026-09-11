<!-- AGENTS.md: generated navigation; keep this file checked in -->
<!-- generated: 2026-09-11 | hash: e9e206c7a991 -->

# relation

## Purpose

Defines semantic relationship requests and facts, coordinates relation reads, and adapts Kotlin K2/IntelliJ resolution.

## Key Files

- [contract/src/main/kotlin/io/github/amichne/kast/relation/contract/RelationRequest.kt](contract/src/main/kotlin/io/github/amichne/kast/relation/contract/RelationRequest.kt) - request model.
- [contract/src/main/kotlin/io/github/amichne/kast/relation/contract/RelationFact.kt](contract/src/main/kotlin/io/github/amichne/kast/relation/contract/RelationFact.kt) - evidence model.
- [service/src/main/kotlin/io/github/amichne/kast/relation/service/RelationService.kt](service/src/main/kotlin/io/github/amichne/kast/relation/service/RelationService.kt) - orchestration.
- [intellij/src/main/kotlin/io/github/amichne/kast/relation/intellij/IntellijK2RelationSearch.kt](intellij/src/main/kotlin/io/github/amichne/kast/relation/intellij/IntellijK2RelationSearch.kt) - compiler-backed search.
- [intellij/src/main/kotlin/io/github/amichne/kast/relation/intellij/IntellijRelationScopeCompiler.kt](intellij/src/main/kotlin/io/github/amichne/kast/relation/intellij/IntellijRelationScopeCompiler.kt) - scope refinement.

## Subdirectories

- `contract` - operations, requests, and facts.
- `service` - relation orchestration.
- `intellij` - K2 identity, scope, provider ordering, collection, and projection.

## Entry Points

- Gradle projects: `:relation:contract`, `:relation:service`, `:relation:intellij`.

## Navigation Hints

- Start with the [repository knowledge](../knowledge/modules/semantic-reads.md) and follow its `code_sources` for source evidence. Root engineering rules remain authoritative; this map adds navigation only.

- Start with relation kind and scope in the contract, then service; open IntelliJ providers for compiler-specific resolution.
