---
type: System Explanation
title: Build roles and architecture enforcement
description: Explains module roles, dependency and bytecode evidence, effect admission,
  structural baselines, and CI scope.
tags:
  - kast
  - development
code_sources:
  - path: settings.gradle.kts
  - path: build-logic/src/main/kotlin/kast.architecture.gradle.kts
  - path: build-logic/src/main/kotlin/support/architecture/gradle/ArchitectureTasks.kt
  - path: build-logic/src/main/kotlin/support/architecture/validation/ModulePolicyValidator.kt
  - path: build-logic/src/main/kotlin/support/architecture/policy/KastCleanSlateModules.kt
  - path: build-logic/src/main/kotlin/support/architecture/policy/JvmEffectRules.kt
  - path: build-logic/src/main/kotlin/kast.kotlin-quality.gradle.kts
  - path: .github/scripts/ci/validation_scope.py
  - path: config/README.md
  - path: build-logic/src/test/kotlin/support/architecture/knowledge/ModuleKnowledgeProjectionTest.kt
  - path: build-logic/src/test/kotlin/support/knowledge/InstalledKnowledgeProjectionTest.kt
  - path: build-logic/src/main/kotlin/support/architecture/projection/ArchitectureProjection.kt
  - path: build-logic/src/main/kotlin/support/tasks/control/GenerateAgentToolsTask.kt
  - path: build-logic/src/main/kotlin/support/tasks/nativefixtures/NativeFixtureJvmArguments.kt
sources:
  - id: openwiki-source-3da7270665885f2d3fdf6d53
    resource: repo://.github/scripts/ci/validation_scope.py
  - id: openwiki-source-6d6c58a5bfeff1b2388a6735
    resource: repo://build-logic/src/main/kotlin/kast.architecture.gradle.kts
  - id: openwiki-source-82cd5564da960e7a275d876e
    resource: repo://build-logic/src/main/kotlin/kast.kotlin-quality.gradle.kts
  - id: openwiki-source-0fb50e2bf387542f1a9d1fd4
    resource: repo://build-logic/src/main/kotlin/support/architecture/gradle/ArchitectureTasks.kt
  - id: openwiki-source-492a0ca28ef350e203697356
    resource: repo://config/README.md
generated:
  by: codex
  at: '2026-10-15T00:59:49.803Z'
verified:
  - by: openwiki/0.6.1
    at: 2026-10-30T23:25:02.193Z
---



# Build roles and architecture enforcement

## From Gradle projects to accepted architecture

`settings.gradle.kts` declares the production modules and the included build logic.
The architecture convention collects production dependencies, exported project dependencies, module roles, and compiled class ownership.
The role plugin on each project supplies part of the admitted architecture input.
A directory name alone does not establish a role.

`VerifyKastArchitectureTask` parses these inputs against the canonical policy.
It then examines compiled bytecode for effects under the declared class owners.
`AcceptedArchitectureVerification` joins the dependency graph and effect evidence before a success report exists.
Invalid policy, missing observations, failed bytecode scans, and forbidden effects produce different rejection records.

Contracts own domain vocabulary and finite outcomes.
Services coordinate pure decisions and explicit ports.
IntelliJ, SQLite, process, and filesystem adapters own named effects under scoped policy.
The hosted runtime composes those owners.
Retired module entries cannot enter the active graph through a production dependency.

## Structural and generated-source gates

The shared quality convention applies ktfmt, Detekt, and Kotlin file-length gates.
Production files use a 400-line limit. Test files use a 600-line limit.
Reviewed baselines keep exact existing debt and per-file ceilings.
New findings, new oversized files, and growth past those ceilings reject.
The gates do not regenerate allowances to turn a failure into success.

The public-query generator owns several Kotlin projections.
Spotless excludes those exact generated files, while the generation gate examines byte parity.
An exclusion from formatting does not transfer source ownership to a manual editor.

## CI selection and evidence limits

CI selects work from an exact changed-path window.
Known independent inputs can select narrower jobs.
Unknown paths, invalid revisions, unreadable diffs, and source inputs outside those cases keep the full selection.
The required aggregate admits only the jobs selected by that decision.

Architecture acceptance establishes the observed dependency and effect policy.
It does not prove that a compiler query returns every semantic result or that an installed IDE accepts a lifecycle operation.
Use domain tests and native qualification for those separate claims.

## Focused tests

- [ModuleKnowledgeProjectionTest.kt](../../build-logic/src/test/kotlin/support/architecture/knowledge/ModuleKnowledgeProjectionTest.kt)
- [InstalledKnowledgeProjectionTest.kt](../../build-logic/src/test/kotlin/support/knowledge/InstalledKnowledgeProjectionTest.kt)

## Source owners

- [settings.gradle.kts](../../settings.gradle.kts)
- [kast.architecture.gradle.kts](../../build-logic/src/main/kotlin/kast.architecture.gradle.kts)
- [ArchitectureTasks.kt](../../build-logic/src/main/kotlin/support/architecture/gradle/ArchitectureTasks.kt)
- [ModulePolicyValidator.kt](../../build-logic/src/main/kotlin/support/architecture/validation/ModulePolicyValidator.kt)
- [KastCleanSlateModules.kt](../../build-logic/src/main/kotlin/support/architecture/policy/KastCleanSlateModules.kt)
- [JvmEffectRules.kt](../../build-logic/src/main/kotlin/support/architecture/policy/JvmEffectRules.kt)
- [kast.kotlin-quality.gradle.kts](../../build-logic/src/main/kotlin/kast.kotlin-quality.gradle.kts)
- [validation_scope.py](../../.github/scripts/ci/validation_scope.py)
- [README.md](../../config/README.md)

## Read next

- [Contract generation](contracts-and-generation.md)
- [Package routes](../architecture/functional-packages.md)
- [Installed knowledge](../knowledge/installed-projection-and-retrieval.md)

## Task adapters and projections

The architecture projection serializes admitted policy inputs for its report consumers.
Gradle task adapters own agent-tool generation and native fixture launch inputs.
`NativeFixtureJvmArguments` reads bounded product information for the selected host and build.
A rejected host, unavailable launch entry, or mismatched build cannot produce admitted JVM arguments.
These adapters supply effects and projections around the policy owners.
