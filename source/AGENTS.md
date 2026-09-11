<!-- AGENTS.md: generated navigation; keep this file checked in -->
<!-- generated: 2026-09-11 | hash: e9e206c7a991 -->

# source

## Purpose

Models exact source selection, ranges, snapshots, and identity; serves reads through a narrow IntelliJ adapter.

## Key Files

- [contract/src/main/kotlin/io/github/amichne/kast/source/contract/SourceSelector.kt](contract/src/main/kotlin/io/github/amichne/kast/source/contract/SourceSelector.kt) - source selection domain.
- [contract/src/main/kotlin/io/github/amichne/kast/source/contract/SourceSnapshot.kt](contract/src/main/kotlin/io/github/amichne/kast/source/contract/SourceSnapshot.kt) - captured source evidence.
- [contract/src/main/kotlin/io/github/amichne/kast/source/contract/SourceTextIdentity.kt](contract/src/main/kotlin/io/github/amichne/kast/source/contract/SourceTextIdentity.kt) - content identity.
- [contract/src/main/kotlin/io/github/amichne/kast/source/contract/Utf16Coordinate.kt](contract/src/main/kotlin/io/github/amichne/kast/source/contract/Utf16Coordinate.kt) - coordinate invariant.
- [service/src/main/kotlin/io/github/amichne/kast/source/service/SourceReadService.kt](service/src/main/kotlin/io/github/amichne/kast/source/service/SourceReadService.kt) - orchestration.
- [intellij/src/main/kotlin/io/github/amichne/kast/source/intellij/IntellijSourceReadPort.kt](intellij/src/main/kotlin/io/github/amichne/kast/source/intellij/IntellijSourceReadPort.kt) - platform read boundary.

## Subdirectories

- `contract` - selectors, ranges, entities, requests, outcomes, snapshots, and ports.
- `service` - source-read orchestration.
- `intellij` - hosted source-read implementation.

## Entry Points

- Gradle projects: `:source:contract`, `:source:service`, `:source:intellij`.

## Navigation Hints

- Start with the [repository knowledge](../knowledge/modules/semantic-reads.md) and follow its `code_sources` for source evidence. Root engineering rules remain authoritative; this map adds navigation only.

- Establish coordinate and identity semantics in `contract` before diagnosing offsets or stale reads in the IntelliJ adapter.
