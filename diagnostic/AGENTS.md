---
type: Module Guide
title: "Kast diagnostic"
description: "Defines compiler diagnostics and bounded scans, with service admission and IntelliJ collection adapters."
resource: "repo://diagnostic"
tags: [kast, navigation]
timestamp: "2026-10-14T23:25:10Z"
source_revision: e014a489bfd7e69cbf2c4b61140ee771d857a03e
code_sources:
  - path: diagnostic/contract/build.gradle.kts
  - path: diagnostic/contract/src/main/kotlin/io/github/amichne/kast/diagnostic/contract/DiagnosticOutcome.kt
  - path: diagnostic/contract/src/main/kotlin/io/github/amichne/kast/diagnostic/contract/DiagnosticScan.kt
  - path: diagnostic/contract/src/main/kotlin/io/github/amichne/kast/diagnostic/contract/DiagnosticScopeQuery.kt
  - path: diagnostic/intellij/build.gradle.kts
  - path: diagnostic/intellij/src/main/kotlin/io/github/amichne/kast/diagnostic/intellij/IntellijDiagnosticCompilerAdapter.kt
  - path: diagnostic/service/build.gradle.kts
  - path: diagnostic/service/src/main/kotlin/io/github/amichne/kast/diagnostic/service/DiagnosticScanService.kt
  - path: diagnostic/service/src/main/kotlin/io/github/amichne/kast/diagnostic/service/DiagnosticService.kt
  - path: diagnostic/service/src/test/kotlin/io/github/amichne/kast/diagnostic/service/DiagnosticScanServiceTest.kt
---

<!-- AGENTS.md: generated navigation; keep this file checked in -->
<!-- generated: 2026-10-14 | hash: e9e206c7a991 -->

# diagnostic

## Purpose

Defines compiler diagnostics and bounded scans, with service admission and IntelliJ collection adapters.

## Key Files

- [DiagnosticScopeQuery.kt](contract/src/main/kotlin/io/github/amichne/kast/diagnostic/contract/DiagnosticScopeQuery.kt) - diagnostic scope and authority.
- [DiagnosticOutcome.kt](contract/src/main/kotlin/io/github/amichne/kast/diagnostic/contract/DiagnosticOutcome.kt) - typed diagnostic outcomes.
- [DiagnosticScan.kt](contract/src/main/kotlin/io/github/amichne/kast/diagnostic/contract/DiagnosticScan.kt) - enumeration, analysis, and output progress.
- [DiagnosticService.kt](service/src/main/kotlin/io/github/amichne/kast/diagnostic/service/DiagnosticService.kt) - diagnostic admission and collection.
- [DiagnosticScanService.kt](service/src/main/kotlin/io/github/amichne/kast/diagnostic/service/DiagnosticScanService.kt) - bounded scan chunks and complete-file suffixes.
- [IntellijDiagnosticCompilerAdapter.kt](intellij/src/main/kotlin/io/github/amichne/kast/diagnostic/intellij/IntellijDiagnosticCompilerAdapter.kt) - the native compiler adapter.

## Subdirectories

- [contract/](contract/) - diagnostic facts, scopes, scan state, and outcomes.
- [service/](service/) - collection and scan orchestration.
- [intellij/](intellij/) - project-bound enumeration and compiler-backed collection.

## Entry Points

- Gradle projects: `:diagnostic:contract`, `:diagnostic:service`, and `:diagnostic:intellij`.
- [DiagnosticScanServiceTest.kt](service/src/test/kotlin/io/github/amichne/kast/diagnostic/service/DiagnosticScanServiceTest.kt) - scan progress, authority, and grants.

## Navigation Hints

- For missing diagnostics, examine scope and scan coverage before the native collector.
- For timeout or output fitting, trace hosted diagnostic completion and retained suffixes in `runtime/hosted`.
- Read the [root guide](../AGENTS.md) for engineering rules and optional OpenWiki retrieval.
