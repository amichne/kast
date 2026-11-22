---
type: System Explanation
title: Repository knowledge after installed bundle retirement
description: Explains the retained repository citation scanner and verified module report,
  separate from the retired installed knowledge payload and retrieval implementation.
tags:
- kast
- knowledge
code_sources:
- path: build-logic/src/main/kotlin/conventions/jsoncontracts/KotlinDocumentationScanner.kt
- path: build-logic/src/main/kotlin/conventions/jsoncontracts/KnowledgeSymbolsMain.kt
- path: build-logic/src/main/kotlin/kast.architecture.gradle.kts
- path: build-logic/src/main/kotlin/support/architecture/gradle/ArchitectureTasks.kt
- path: build-logic/src/main/kotlin/support/tasks/verification/VerifyDistributionTasks.kt
- path: cli/src/main/kotlin/io/github/amichne/kast/cli/bootstrap/KastCliMain.kt
- path: distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/ManagementCommand.kt
- path: build-logic/src/main/kotlin/support/architecture/knowledge/ModuleKnowledgeProjection.kt
- path: build-logic/src/test/kotlin/support/architecture/knowledge/ModuleKnowledgeProjectionTest.kt
- path: build-logic/src/test/kotlin/KotlinDocumentationScannerTest.kt
sources:
  - id: openwiki-source-f0a1e859396a2b60ccc2b595
    resource: repo://build-logic/src/main/kotlin/conventions/jsoncontracts/KnowledgeSymbolsMain.kt
  - id: openwiki-source-97c69063b7394d01bd57dc64
    resource: repo://build-logic/src/main/kotlin/conventions/jsoncontracts/KotlinDocumentationScanner.kt
  - id: openwiki-source-6d6c58a5bfeff1b2388a6735
    resource: repo://build-logic/src/main/kotlin/kast.architecture.gradle.kts
  - id: openwiki-source-0fb50e2bf387542f1a9d1fd4
    resource: repo://build-logic/src/main/kotlin/support/architecture/gradle/ArchitectureTasks.kt
  - id: openwiki-source-b99ddefd81bdadd0befd1ee3
    resource: repo://build-logic/src/main/kotlin/support/architecture/knowledge/ModuleKnowledgeProjection.kt
  - id: openwiki-source-a387d9d545afff426d34a10a
    resource: repo://build-logic/src/main/kotlin/support/tasks/verification/VerifyDistributionTasks.kt
  - id: openwiki-source-604402605ebdfe0ea353e307
    resource: repo://build-logic/src/test/kotlin/KotlinDocumentationScannerTest.kt
  - id: openwiki-source-ed6022ca58911768febfbe7e
    resource: repo://cli/src/main/kotlin/io/github/amichne/kast/cli/bootstrap/KastCliMain.kt
  - id: openwiki-source-2141db5e92ccbb5de75a1544
    resource: repo://distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/KastManagementMain.kt
generated: { by: "amp", at: "2026-11-15T20:21:03.545Z" }
verified:
  - by: openwiki/0.9.0
    at: 2026-11-22T20:53:36.158Z
---

# Repository knowledge after installed bundle retirement

## Retired product boundary

The installed knowledge generator, Kotlin documentation comment extraction, CLI command, and reader are retired.
Control distribution verification rejects `share/kast/knowledge` and its descendants.
The removed declaration cards and guide-resource inventory are not a current installed interface.
The public native management graph exposes no knowledge command, and the private installer entry admits only `--version`.
Read the [retired contract](../contracts/installed-knowledge.md) for the unchanged archive limit and separate native acceptance boundary.

## Repository citation evidence

`KotlinDocumentationScanner.symbols()` remains a repository citation scanner.
It visits named Kotlin declarations regardless of visibility, reports declaration names and identifier line ranges, and rejects syntax errors as `INVALID_KOTLIN`.
It marks a function annotated `@Test` with a body as an executable-test citation candidate.
Comments and quoted declaration text do not become declarations.
This PSI evidence performs no name or type resolution and does not prove a test passed.

`KnowledgeSymbolsMain` reads source files through that scanner and serializes accepted or rejected results.
An unreadable file retains `UNREADABLE_SOURCE` rather than a successful empty result.
These repository checks do not recreate installed documentation resources or live workspace authority.

## Verified module report

`generateKastModuleKnowledge` remains wired to `verifyKastArchitecture`.
Its output is `build/reports/kast-architecture/kast-module-knowledge.json`, not an installed knowledge manifest.
The task reconstructs accepted architecture evidence and requires exact byte agreement with the published architecture verification report.
It reads tracked `AGENTS.md` files only as canonical regular files and checks their declared file identities.

`ModuleKnowledgeProjection` binds the product version and source revision to accepted architecture, observed project dependencies, and scoped guides.
It retains each guide's content, scope directory, and SHA-256.
For each module it records governing guides in scope order and descendant guides separately.
Missing root guidance, duplicate guide paths, invalid paths, invalid release identity, and missing governing coverage remain finite projection failures.
These module and guide facts do not establish compiler identity, native qualification, or a successful global installation.

## Focused tests

- [KotlinDocumentationScannerTest.kt](../../build-logic/src/test/kotlin/KotlinDocumentationScannerTest.kt)
- [ModuleKnowledgeProjectionTest.kt](../../build-logic/src/test/kotlin/support/architecture/knowledge/ModuleKnowledgeProjectionTest.kt)

## Source owners

- [KotlinDocumentationScanner.kt](../../build-logic/src/main/kotlin/conventions/jsoncontracts/KotlinDocumentationScanner.kt)
- [KnowledgeSymbolsMain.kt](../../build-logic/src/main/kotlin/conventions/jsoncontracts/KnowledgeSymbolsMain.kt)
- [`kast.architecture.gradle.kts`](../../build-logic/src/main/kotlin/kast.architecture.gradle.kts)
- [ArchitectureTasks.kt](../../build-logic/src/main/kotlin/support/architecture/gradle/ArchitectureTasks.kt)
- [VerifyDistributionTasks.kt](../../build-logic/src/main/kotlin/support/tasks/verification/VerifyDistributionTasks.kt)
- [KastCliMain.kt](../../cli/src/main/kotlin/io/github/amichne/kast/cli/bootstrap/KastCliMain.kt)
- [ManagementCommand.kt](../../distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/ManagementCommand.kt)
- [ModuleKnowledgeProjection.kt](../../build-logic/src/main/kotlin/support/architecture/knowledge/ModuleKnowledgeProjection.kt)

## Read next

- [Contract projection](../development/contracts-and-generation.md)
- [Wiki upkeep](../development/knowledge-upkeep.md)
- [Public management](../distribution/management-and-connections.md)
