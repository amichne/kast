<!-- AGENTS.md: generated navigation; keep this file checked in -->
<!-- generated: 2026-09-11 | hash: e9e206c7a991 -->

# symbol

## Purpose

Defines symbol discovery and exact declaration identity, provides domain services, and implements compiler/PSI-backed IntelliJ adapters.

## Key Files

- [contract/src/main/kotlin/io/github/amichne/kast/symbol/contract/CanonicalCompilerSignature.kt](contract/src/main/kotlin/io/github/amichne/kast/symbol/contract/CanonicalCompilerSignature.kt) - canonical signature identity.
- [contract/src/main/kotlin/io/github/amichne/kast/symbol/contract/ExactDeclarationSelector.kt](contract/src/main/kotlin/io/github/amichne/kast/symbol/contract/ExactDeclarationSelector.kt) - exact selector.
- [contract/src/main/kotlin/io/github/amichne/kast/symbol/contract/discovery/SymbolDiscoveryRequest.kt](contract/src/main/kotlin/io/github/amichne/kast/symbol/contract/discovery/SymbolDiscoveryRequest.kt) - discovery request.
- [service/src/main/kotlin/io/github/amichne/kast/symbol/service/SymbolDiscoveryService.kt](service/src/main/kotlin/io/github/amichne/kast/symbol/service/SymbolDiscoveryService.kt) - discovery orchestration.
- [service/src/main/kotlin/io/github/amichne/kast/symbol/service/SymbolExactService.kt](service/src/main/kotlin/io/github/amichne/kast/symbol/service/SymbolExactService.kt) - exact lookup orchestration.
- [intellij/src/main/kotlin/io/github/amichne/kast/symbol/intellij/exact/IntellijKotlinCompilerSymbolLookup.kt](intellij/src/main/kotlin/io/github/amichne/kast/symbol/intellij/exact/IntellijKotlinCompilerSymbolLookup.kt) - compiler lookup.

## Subdirectories

- `contract` - exact and discovery identities, requests, facts, and outcomes.
- `service` - discovery and exact services.
- `intellij/src/main/kotlin/io/github/amichne/kast/symbol/intellij/discovery` - search and scope compilation.
- `intellij/src/main/kotlin/io/github/amichne/kast/symbol/intellij/exact` - compiler/PSI exact resolution.

## Entry Points

- Gradle projects: `:symbol:contract`, `:symbol:service`, `:symbol:intellij`.

## Navigation Hints

- Start with the [repository knowledge](../knowledge/modules/semantic-reads.md) and follow its `code_sources` for source evidence. Root engineering rules remain authoritative; this map adds navigation only.

- Use discovery to narrow candidates, then retain an exact selector through subsequent operations.
- For ambiguity, start in contract/service admission before reading PSI or compiler adapters.
