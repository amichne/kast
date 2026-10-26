---
type: Module Guide
title: "Kast build-logic"
description: "Defines Gradle conventions, effect ownership, architecture gates, generated contracts, and installed knowledge projections."
resource: "repo://build-logic"
tags: [kast, navigation]
timestamp: "2026-10-26T11:36:07Z"
source_revision: 371fb8bfcb7c2c22a12ab373b3d7c49d1fbb528a
code_sources:
  - path: build-logic/src/main/kotlin/support/tasks/nativefixtures/CompileDependencyFixture.kt
  - path: build-logic/build.gradle.kts
  - path: build-logic/src/main/kotlin/support/tasks/VerificationReportTask.kt
  - path: build-logic/src/test/kotlin/VerificationReportTaskTest.kt
  - path: build-logic/src/main/kotlin/conventions/GenerateKnowledgeDocsTask.kt
  - path: build-logic/src/main/kotlin/conventions/VerifyJsonContractsTask.kt
  - path: build-logic/src/main/kotlin/conventions/jsoncontracts/KnowledgeSymbolsMain.kt
  - path: build-logic/src/main/kotlin/kast.architecture.gradle.kts
  - path: build-logic/src/main/kotlin/support/architecture/ModuleImpact.kt
  - path: build-logic/src/main/kotlin/support/architecture/gradle/TopologyImpactTask.kt
  - path: build-logic/src/main/kotlin/kast.kotlin-quality.gradle.kts
  - path: build-logic/src/main/kotlin/kast.public-query-contract.gradle.kts
  - path: build-logic/src/main/kotlin/support/architecture/policy/KastCleanSlateModules.kt
  - path: build-logic/src/main/kotlin/support/knowledge/GenerateInstalledKnowledgeTask.kt
  - path: build-logic/src/test/kotlin/support/knowledge/InstalledKnowledgeProjectionTest.kt
---

<!-- AGENTS.md: generated navigation; keep this file checked in -->
<!-- generated: 2026-10-14 | hash: bd814345dab3 -->

# build-logic

## Purpose

Defines Gradle conventions, effect ownership, architecture gates, generated contracts, and installed knowledge projections.

## Key Files

- [Architecture convention](src/main/kotlin/kast.architecture.gradle.kts) - module architecture and installed guide wiring.
- [ModuleImpact.kt](src/main/kotlin/support/architecture/ModuleImpact.kt) - selection and shortest dependency paths over the declared production graph.
- [TopologyImpactTask.kt](src/main/kotlin/support/architecture/gradle/TopologyImpactTask.kt) - opt-in report without source compilation or compiler authority.
- [KastCleanSlateModules.kt](src/main/kotlin/support/architecture/policy/KastCleanSlateModules.kt) - the admitted module topology.
- [Kotlin quality convention](src/main/kotlin/kast.kotlin-quality.gradle.kts) - format, `Detekt`, and file-length gates.
- [CompileDependencyFixture.kt](src/main/kotlin/support/tasks/nativefixtures/CompileDependencyFixture.kt) - declared compiler inputs and atomic dependency archives for native fixtures.
- [VerificationReportTask.kt](src/main/kotlin/support/tasks/VerificationReportTask.kt) - explicit process inputs, local task history, and diagnostic reports.
- [Public query convention](src/main/kotlin/kast.public-query-contract.gradle.kts) - public schema generation parity and its regression gate.
- [VerifyJsonContractsTask.kt](src/main/kotlin/conventions/VerifyJsonContractsTask.kt) - typed requests for the isolated JSON syntax scanner.
- [KnowledgeSymbolsMain.kt](src/main/kotlin/conventions/jsoncontracts/KnowledgeSymbolsMain.kt) - Kotlin declaration evidence for OpenWiki citations.
- [GenerateKnowledgeDocsTask.kt](src/main/kotlin/conventions/GenerateKnowledgeDocsTask.kt) - isolated Kotlin documentation extraction.
- [GenerateInstalledKnowledgeTask.kt](src/main/kotlin/support/knowledge/GenerateInstalledKnowledgeTask.kt) - installed module, declaration, and scoped-guide projection.

## Subdirectories

- [src/main/kotlin/conventions/](src/main/kotlin/conventions/) - shared JVM conventions and verification tasks.
- [src/main/kotlin/conventions/jsoncontracts/](src/main/kotlin/conventions/jsoncontracts/) - isolated Kotlin syntax parsers.
- [src/main/kotlin/kast/role/](src/main/kotlin/kast/role/) - module roles and permitted effects.
- [src/main/kotlin/support/architecture/](src/main/kotlin/support/architecture/) - architecture facts, policy, and projections.
- [src/main/kotlin/support/knowledge/](src/main/kotlin/support/knowledge/) - installed knowledge projection and admission.
- [src/test/](src/test/) - policy and projection tests.

## Entry Points

- Root `settings.gradle.kts` includes this separate Gradle build.
- Run `./gradlew verifyJsonContracts` from the repository root for the JSON syntax gate.
- Run `mise exec -- ./gradlew :distribution:cli:scopedCheck --continue` for one module's quality guards and unit tests. Keep product assembly in the full gate.
- Run `./gradlew topologyImpact -PkastTopologyModules=:topology:contract` for declared downstream module impact.
- Set `kastTopologyEnabled=false` to disable that report. Read [the command guide](../docs/topology-impact.md) for evidence limits.
- `generateInstalledKnowledgeBundle` projects tracked `AGENTS.md` files into the installed knowledge bundle.
- `verifyKnowledgeBase` examines OpenWiki. It does not examine the scoped guide format.
- [InstalledKnowledgeProjectionTest.kt](src/test/kotlin/support/knowledge/InstalledKnowledgeProjectionTest.kt) - scoped guide and declaration projection cases.

## Navigation Hints

- For dependency failures, start with the module role and the reported architecture policy.
- For installed guide scope, read architecture projections and `support/knowledge`.
- For existing structural debt, read `config/README.md` from the repository root. Do not enlarge allowances to pass a gate.
- Release assembly belongs to `distribution/release` and `.github/scripts/release`.
- Read the [root guide](../AGENTS.md) for engineering rules and optional OpenWiki retrieval.
