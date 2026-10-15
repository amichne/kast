---
type: System Explanation
title: Diagnostic scans and progress
description: Explains diagnostic scope enumeration, compiler collection, detached
  scan progress, complete-file suffixes, and hosted output fitting.
tags:
- kast
- semantics
code_sources:
- path: diagnostic/contract/src/main/kotlin/io/github/amichne/kast/diagnostic/contract/DiagnosticScan.kt
- path: diagnostic/contract/src/main/kotlin/io/github/amichne/kast/diagnostic/contract/DiagnosticEnumeration.kt
- path: diagnostic/service/src/main/kotlin/io/github/amichne/kast/diagnostic/service/DiagnosticService.kt
- path: diagnostic/service/src/main/kotlin/io/github/amichne/kast/diagnostic/service/DiagnosticScanService.kt
- path: diagnostic/intellij/src/main/kotlin/io/github/amichne/kast/diagnostic/intellij/ProjectBoundDiagnosticEnumeration.kt
- path: diagnostic/intellij/src/main/kotlin/io/github/amichne/kast/diagnostic/intellij/IntellijDiagnosticCompilerAdapter.kt
- path: runtime/hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/HostedDiagnosticResponse.kt
- path: diagnostic/intellij/src/main/kotlin/io/github/amichne/kast/diagnostic/intellij/BoundedDiagnosticEnumeration.kt
- path: diagnostic/service/src/test/kotlin/io/github/amichne/kast/diagnostic/service/DiagnosticScanServiceTest.kt
- path: diagnostic/service/src/test/kotlin/io/github/amichne/kast/diagnostic/service/DiagnosticServiceTest.kt
verified:
  - by: openwiki/0.6.1
    at: 2026-10-15T00:33:05.932Z
sources:
  - id: openwiki-source-e7eea812202bbbd699e894a8
    resource: repo://diagnostic/contract/src/main/kotlin/io/github/amichne/kast/diagnostic/contract/DiagnosticScan.kt
  - id: openwiki-source-b4ecf6703c70a55644578c6e
    resource: repo://diagnostic/intellij/src/main/kotlin/io/github/amichne/kast/diagnostic/intellij/ProjectBoundDiagnosticEnumeration.kt
  - id: openwiki-source-6dbfe18d12d60b0c26fdc6b3
    resource: repo://diagnostic/service/src/main/kotlin/io/github/amichne/kast/diagnostic/service/DiagnosticScanService.kt
  - id: openwiki-source-4004e44292553832194bb1a8
    resource: repo://runtime/hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/HostedDiagnosticResponse.kt
generated: { by: "codex", at: "2026-10-15T00:33:05.932Z" }
---

# Diagnostic scans and progress

## Scope and compiler evidence have separate owners

The diagnostic contract binds a requested scope to admitted workspace authority.
`DiagnosticService` admits the request and consumes the compiler port's finite outcome.
The IntelliJ adapter owns native diagnostic collection.
Display text or an empty process response does not substitute for an admitted compiler result.

Project-bound enumeration uses the selected IDEA source scope and original source ownership.
Its native index callback collects eligible Kotlin files under bounded accounting.
Package or compiler refinement remains outside the native index callback.
Cancellation propagates through its existing cancellation types.
Unavailable native state becomes a finite enumeration rejection.

## A scan keeps independent progress

`DiagnosticScanService` advances enumeration, analysis, and output through detached stages.
The original query, authority, work allowance, and elapsed-time allowance constrain every transition.
A resumed scan must restore the compatible scan state.
An unrelated cursor cannot supply authority for another scope.

The service keeps complete-file diagnostic suffixes when a page cannot deliver the whole file's output.
Output progress does not erase unfinished file enumeration or compiler analysis.
An empty delivered page can therefore mean unfinished work rather than a clean scope.
Coverage and scan stage explain which conclusion the result permits.

## Hosted completion and fitting

The hosted diagnostic owner joins the scan result with the original caller deadline and final freshness decision.
`HostedDiagnosticResponse` fits the full response envelope and keeps a produced suffix when output needs another page.
The envelope keeps coverage, cumulative progress, and finite failures.
A byte limit cannot turn an unfinished scan into a successful absence claim.

For a missing diagnostic, examine scope ownership and scan progress before the compiler collector.
For late or truncated output, examine the hosted completion policy and response fitting.
Keep unavailable state, incomplete enumeration, and complete diagnostic absence distinct.

Service tests establish scan transitions and grant handling with explicit ports.
Native compiler fixtures establish collection under the IDE model.
Neither source inspection nor a service test proves diagnostics for an unexamined user workspace.

## Focused tests

- [DiagnosticScanServiceTest.kt](../../../diagnostic/service/src/test/kotlin/io/github/amichne/kast/diagnostic/service/DiagnosticScanServiceTest.kt)
- [DiagnosticServiceTest.kt](../../../diagnostic/service/src/test/kotlin/io/github/amichne/kast/diagnostic/service/DiagnosticServiceTest.kt)

## Source owners

- [DiagnosticScan.kt](../../../diagnostic/contract/src/main/kotlin/io/github/amichne/kast/diagnostic/contract/DiagnosticScan.kt)
- [DiagnosticEnumeration.kt](../../../diagnostic/contract/src/main/kotlin/io/github/amichne/kast/diagnostic/contract/DiagnosticEnumeration.kt)
- [DiagnosticService.kt](../../../diagnostic/service/src/main/kotlin/io/github/amichne/kast/diagnostic/service/DiagnosticService.kt)
- [DiagnosticScanService.kt](../../../diagnostic/service/src/main/kotlin/io/github/amichne/kast/diagnostic/service/DiagnosticScanService.kt)
- [ProjectBoundDiagnosticEnumeration.kt](../../../diagnostic/intellij/src/main/kotlin/io/github/amichne/kast/diagnostic/intellij/ProjectBoundDiagnosticEnumeration.kt)
- [IntellijDiagnosticCompilerAdapter.kt](../../../diagnostic/intellij/src/main/kotlin/io/github/amichne/kast/diagnostic/intellij/IntellijDiagnosticCompilerAdapter.kt)
- [HostedDiagnosticResponse.kt](../../../runtime/hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/HostedDiagnosticResponse.kt)
- [BoundedDiagnosticEnumeration.kt](../../../diagnostic/intellij/src/main/kotlin/io/github/amichne/kast/diagnostic/intellij/BoundedDiagnosticEnumeration.kt)

## Read next

- [Workspace freshness](../workspace/model-and-freshness.md)
- [Hosted publication](../../runtime/hosted-composition-and-publication.md)
