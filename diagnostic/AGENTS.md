<!-- AGENTS.md: generated navigation; keep this file checked in -->
<!-- generated: 2026-09-11 | hash: e9e206c7a991 -->

# diagnostic

## Purpose

Models diagnostic queries and outcomes, orchestrates collection, and adapts IntelliJ compiler evidence.

## Key Files

- [contract/src/main/kotlin/io/github/amichne/kast/diagnostic/contract/DiagnosticOutcome.kt](contract/src/main/kotlin/io/github/amichne/kast/diagnostic/contract/DiagnosticOutcome.kt) - typed result domain.
- [contract/src/main/kotlin/io/github/amichne/kast/diagnostic/contract/DiagnosticScopeQuery.kt](contract/src/main/kotlin/io/github/amichne/kast/diagnostic/contract/DiagnosticScopeQuery.kt) - request/scope model.
- [service/src/main/kotlin/io/github/amichne/kast/diagnostic/service/DiagnosticService.kt](service/src/main/kotlin/io/github/amichne/kast/diagnostic/service/DiagnosticService.kt) - domain orchestration.
- [intellij/src/main/kotlin/io/github/amichne/kast/diagnostic/intellij/IntellijDiagnosticCompilerAdapter.kt](intellij/src/main/kotlin/io/github/amichne/kast/diagnostic/intellij/IntellijDiagnosticCompilerAdapter.kt) - compiler boundary.
- [intellij/src/main/kotlin/io/github/amichne/kast/diagnostic/intellij/IntellijDiagnosticCollector.kt](intellij/src/main/kotlin/io/github/amichne/kast/diagnostic/intellij/IntellijDiagnosticCollector.kt) - platform collection.

## Subdirectories

- `contract` - facts, operations, scopes, and outcomes.
- `service` - effect-independent orchestration.
- `intellij` - compiler-backed collection and projection.

## Entry Points

- Gradle projects: `:diagnostic:contract`, `:diagnostic:service`, `:diagnostic:intellij`.

## Navigation Hints

- Start with the [repository knowledge](../knowledge/modules/semantic-reads.md) and follow its `code_sources` for source evidence. Root engineering rules remain authoritative; this map adds navigation only.

- Start with the contract, then `DiagnosticService`; inspect IntelliJ code only for compiler or scope-resolution behavior.
