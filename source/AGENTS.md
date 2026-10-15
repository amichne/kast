---
type: Module Guide
title: "Kast source"
description: "Defines exact source anchors, UTF-16 coordinates, snapshots, and bounded source reads through IntelliJ IDEA."
resource: "repo://source"
tags: [kast, navigation]
timestamp: "2026-10-14T23:25:10Z"
source_revision: e014a489bfd7e69cbf2c4b61140ee771d857a03e
code_sources:
  - path: source/contract/build.gradle.kts
  - path: source/contract/src/main/kotlin/io/github/amichne/kast/source/contract/SourceReadCursor.kt
  - path: source/contract/src/main/kotlin/io/github/amichne/kast/source/contract/SourceSelector.kt
  - path: source/contract/src/main/kotlin/io/github/amichne/kast/source/contract/Utf16Coordinate.kt
  - path: source/intellij/build.gradle.kts
  - path: source/intellij/src/main/kotlin/io/github/amichne/kast/source/intellij/IntellijSourceEntityPageCollector.kt
  - path: source/intellij/src/main/kotlin/io/github/amichne/kast/source/intellij/IntellijSourceReadPort.kt
  - path: source/intellij/src/main/kotlin/io/github/amichne/kast/source/intellij/IntellijSourceTraversal.kt
  - path: source/service/build.gradle.kts
  - path: source/service/src/main/kotlin/io/github/amichne/kast/source/service/SourceReadService.kt
---

<!-- AGENTS.md: generated navigation; keep this file checked in -->
<!-- generated: 2026-10-14 | hash: e9e206c7a991 -->

# source

## Purpose

Defines exact source anchors, UTF-16 coordinates, snapshots, and bounded source reads through IntelliJ IDEA.

## Key Files

- [SourceSelector.kt](contract/src/main/kotlin/io/github/amichne/kast/source/contract/SourceSelector.kt) - source selection and snapshot identity.
- [SourceReadCursor.kt](contract/src/main/kotlin/io/github/amichne/kast/source/contract/SourceReadCursor.kt) - request-bound structural progress.
- [Utf16Coordinate.kt](contract/src/main/kotlin/io/github/amichne/kast/source/contract/Utf16Coordinate.kt) - source coordinate invariants.
- [SourceReadService.kt](service/src/main/kotlin/io/github/amichne/kast/source/service/SourceReadService.kt) - source admission and final content revalidation.
- [IntellijSourceReadPort.kt](intellij/src/main/kotlin/io/github/amichne/kast/source/intellij/IntellijSourceReadPort.kt) - the native source read adapter.
- [IntellijSourceEntityPageCollector.kt](intellij/src/main/kotlin/io/github/amichne/kast/source/intellij/IntellijSourceEntityPageCollector.kt) - kind admission before projection and page capacity.
- [IntellijSourceTraversal.kt](intellij/src/main/kotlin/io/github/amichne/kast/source/intellij/IntellijSourceTraversal.kt) - structural continuation without prefix replay.

## Subdirectories

- [contract/](contract/) - anchors, selectors, ranges, snapshots, ports, and outcomes.
- [service/](service/) - source admission and orchestration.
- [intellij/](intellij/) - PSI reads, entity collection, and local declaration addresses.

## Entry Points

- Gradle projects: `:source:contract`, `:source:service`, and `:source:intellij`.

## Navigation Hints

- For offset errors, establish UTF-16 and snapshot semantics before the native read.
- Keep candidate, symbol, and source-selector failures distinct through protocol projection.
- For pagination, trace `SourceReadCursor` into the structural traversal and hosted source store.
- Read the [root guide](../AGENTS.md) for engineering rules and optional OpenWiki retrieval.
