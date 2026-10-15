---
type: System Explanation
title: Source anchors and structural pages
description: Explains source anchor admission, snapshot and UTF-16 identity, entity
  filters, advancing structural cursors, and freshness rejection.
tags:
- kast
- semantics
code_sources:
- path: source/contract/src/main/kotlin/io/github/amichne/kast/source/contract/SourceSelector.kt
- path: source/contract/src/main/kotlin/io/github/amichne/kast/source/contract/SourceReadCursor.kt
- path: source/contract/src/main/kotlin/io/github/amichne/kast/source/contract/SourceSnapshot.kt
- path: source/contract/src/main/kotlin/io/github/amichne/kast/source/contract/Utf16Coordinate.kt
- path: source/service/src/main/kotlin/io/github/amichne/kast/source/service/SourceReadService.kt
- path: source/intellij/src/main/kotlin/io/github/amichne/kast/source/intellij/IntellijSourceEntityPageCollector.kt
- path: source/intellij/src/main/kotlin/io/github/amichne/kast/source/intellij/IntellijSourceTraversal.kt
- path: source/intellij/src/main/kotlin/io/github/amichne/kast/source/intellij/IntellijLocalSourceAddress.kt
- path: source/contract/src/main/kotlin/io/github/amichne/kast/source/contract/SourceDeclarationVisibility.kt
- path: source/intellij/src/main/kotlin/io/github/amichne/kast/source/intellij/InstalledIntellijSourceReadPort.kt
- path: source/intellij/src/test/kotlin/io/github/amichne/kast/source/intellij/SourceCursorReadTest.kt
- path: source/intellij/src/test/kotlin/io/github/amichne/kast/source/intellij/IntellijSourceEntityReadTest.kt
- path: source/intellij/src/test/kotlin/io/github/amichne/kast/source/intellij/IntellijSourceScopeAdmissionTest.kt
verified:
  - by: openwiki/0.6.1
    at: 2026-10-15T00:33:05.932Z
sources:
  - id: openwiki-source-dc05ad68626efb8c1b3b756a
    resource: repo://source/contract/src/main/kotlin/io/github/amichne/kast/source/contract/SourceReadCursor.kt
  - id: openwiki-source-3794ab03976ae54e783a5bda
    resource: repo://source/intellij/src/main/kotlin/io/github/amichne/kast/source/intellij/IntellijSourceEntityPageCollector.kt
  - id: openwiki-source-e212e925aae3e26dc3078713
    resource: repo://source/intellij/src/main/kotlin/io/github/amichne/kast/source/intellij/IntellijSourceTraversal.kt
  - id: openwiki-source-d16ec0b0b90b8cd04743a4f3
    resource: repo://source/service/src/main/kotlin/io/github/amichne/kast/source/service/SourceReadService.kt
generated: { by: "codex", at: "2026-10-15T00:33:05.932Z" }
---

# Source anchors and structural pages

## Admit the exact anchor and snapshot

Source reads distinguish candidate, exact-symbol, and source-selector anchors.
Each anchor keeps the evidence and original authority that identify its selected source.
`SourceReadService` admits the current source context before the native read and reexamines content before publication.
Candidate staleness, source-selector staleness, wrong context, and source-state mismatch keep distinct causes.

`SourceSnapshot` binds source evidence to the exact content context.
`Utf16Coordinate` defines offsets in the coordinate system that IntelliJ and Kotlin PSI consume.
A byte offset or a character count from another encoding cannot substitute for that admitted position.
Ranges and nested selectors preserve their parent snapshot instead of reconstructing a weaker file path.

## Filter before compiler projection and capacity

The native entity collector receives structural declarations in a stable order.
It applies containment and declaration-kind admission before deferred compiler projection.
The final entity predicate decides which eligible entities consume the output page capacity.
An excluded container can still contain eligible descendants.

The collector keeps an explicit collecting, page-full, or stopped state.
A matching lookahead proves that a suffix remains.
Out-of-order native entities reject the collector contract.
An unmatched declaration cannot exhaust the requested result capacity merely because it appears first.

## Resume structural work without prefix replay

`SourceReadCursor` binds the exact request, region fingerprint, snapshot, structural frontier, and cumulative progress.
`IntellijSourceTraversal` restores those pending positions.
It does not walk an ordinal prefix again just to reach the next page.
A cursor for a different region or snapshot rejects rather than guessing a new position.

Local declaration addresses use the same admitted ownership and compiler identity vocabulary as discovery and relation reads.
Returned text, entity selectors, and qualified progress keep their source evidence through protocol projection.
The hosted source state owner separately controls stored output, expiry, and publication claims.

## Evidence limits

Cursor and entity tests establish request binding, order, capacity, and structural progress.
Compiler identity needs the native adapter's current K2 evidence.
A successful text read alone does not establish that every declaration in a requested semantic scope was enumerated.

## Focused tests

- [SourceCursorReadTest.kt](../../../source/intellij/src/test/kotlin/io/github/amichne/kast/source/intellij/SourceCursorReadTest.kt)
- [IntellijSourceEntityReadTest.kt](../../../source/intellij/src/test/kotlin/io/github/amichne/kast/source/intellij/IntellijSourceEntityReadTest.kt)
- [IntellijSourceScopeAdmissionTest.kt](../../../source/intellij/src/test/kotlin/io/github/amichne/kast/source/intellij/IntellijSourceScopeAdmissionTest.kt)

## Source owners

- [SourceSelector.kt](../../../source/contract/src/main/kotlin/io/github/amichne/kast/source/contract/SourceSelector.kt)
- [SourceReadCursor.kt](../../../source/contract/src/main/kotlin/io/github/amichne/kast/source/contract/SourceReadCursor.kt)
- [SourceSnapshot.kt](../../../source/contract/src/main/kotlin/io/github/amichne/kast/source/contract/SourceSnapshot.kt)
- [Utf16Coordinate.kt](../../../source/contract/src/main/kotlin/io/github/amichne/kast/source/contract/Utf16Coordinate.kt)
- [SourceReadService.kt](../../../source/service/src/main/kotlin/io/github/amichne/kast/source/service/SourceReadService.kt)
- [IntellijSourceEntityPageCollector.kt](../../../source/intellij/src/main/kotlin/io/github/amichne/kast/source/intellij/IntellijSourceEntityPageCollector.kt)
- [IntellijSourceTraversal.kt](../../../source/intellij/src/main/kotlin/io/github/amichne/kast/source/intellij/IntellijSourceTraversal.kt)
- [IntellijLocalSourceAddress.kt](../../../source/intellij/src/main/kotlin/io/github/amichne/kast/source/intellij/IntellijLocalSourceAddress.kt)
- [SourceDeclarationVisibility.kt](../../../source/contract/src/main/kotlin/io/github/amichne/kast/source/contract/SourceDeclarationVisibility.kt)
- [InstalledIntellijSourceReadPort.kt](../../../source/intellij/src/main/kotlin/io/github/amichne/kast/source/intellij/InstalledIntellijSourceReadPort.kt)

## Read next

- [Exact identity](../symbol/discovery-and-identity.md)
- [Output lifetime](../../queries/retention-and-presentation.md)
- [Source failures](../../contracts/source-failures.md)
