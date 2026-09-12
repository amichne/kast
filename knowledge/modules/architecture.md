---
type: Kotlin Architecture
title: Verified module architecture
description: Gradle verifies module roles, dependencies, exports, and scoped agent guidance before projecting module knowledge.
resource: file://settings.gradle.kts
tags: [kotlin, gradle, architecture]
timestamp: 2026-09-12T00:00:00Z
code_sources:
  - path: docs/reviews/live-semantic-read-acceptance.md
  - path: build-logic/src/main/kotlin/kast.kotlin-library.gradle.kts
  - path: build-logic/src/main/kotlin/kast.kotlin-quality.gradle.kts
  - path: build-logic/src/main/kotlin/conventions/KotlinFileLengthTask.kt
  - path: build-logic/src/main/kotlin/conventions/KotlinFileLengthBaseline.kt
  - path: config/kotlin/file-length-baseline.tsv
  - path: config/detekt/detekt.yml
  - path: settings.gradle.kts
  - path: build-logic/src/main/kotlin/kast.architecture.gradle.kts
  - path: build-logic/src/main/kotlin/support/architecture/policy/KastCleanSlateModules.kt
    symbols: [KastCleanSlateModules]
  - path: build-logic/src/main/kotlin/support/architecture/validation/ModulePolicyValidator.kt
    symbols: [ModuleRoleConvention, ValidatedModulePolicy]
  - path: build-logic/src/main/kotlin/support/architecture/policy/JvmEffectRules.kt
  - path: build-logic/src/main/kotlin/support/architecture/knowledge/ModuleKnowledgeProjection.kt
    symbols: [ModuleKnowledgeProjection, ModuleKnowledgeDocument]
  - path: change/protocol/build.gradle.kts
  - path: query/protocol/build.gradle.kts
  - path: runtime/hosted/build.gradle.kts
  - path: build-logic/src/main/kotlin/support/architecture/policy/KastQueryModules.kt
---

# Verified module architecture

The root [settings](../../settings.gradle.kts) declares the active Gradle projects. The [architecture plugin](../../build-logic/src/main/kotlin/kast.architecture.gradle.kts) observes their production dependencies, exported dependencies, compiled classes, and role conventions before verification.

`generateKastModuleKnowledge` consumes accepted architecture evidence. Its output records the source revision, policy, observed edges, tracked `AGENTS.md` content hashes, and the governing guide set for each module. Generated build output is verification evidence, not a checked-in source of truth.

Every module role requires its matching Gradle convention. Validated module policy retains that convention directly; there is no unmarked host role or exemption from effect classification.

## Ownership shape

- Contract modules own domain identity and closed outcomes.
- Service modules own orchestration without platform authority.
- `query:protocol` has the service role and owns shared semantic-read request admission, reference codecs, and canonical projection using contract dependencies.
- IntelliJ and filesystem/SQLite modules own explicit effects.
- The existing-IDE host connects proven contracts to effectful adapters.
- App Server and CLI expose broker, installation and transport boundaries.

The IntelliJ read adapter also depends on symbol contracts for the existing-IDE
[hosted query](../flows/hosted-query.md). This permits detached compiler evidence
without introducing an isolated workspace opener or importing implementation
dependencies from semantic service modules.

`runtime:hosted` is the sole active semantic host. Its declared dependencies include all active semantic contracts, services, read adapters and controlled change adapters. The former composition, server, telemetry, indexer, workspace importer/coordinator and topology modules remain explicit retired policy entries with no dependencies or effects. No active module can acquire a retired owner through its production dependency graph. Project opening, Gradle import, topology build and topology publication have no permitted owner.

`change:protocol` similarly owns planning-request lowering and detached previews.
It depends only on `kernel`, `protocol:contract`, and `change:contract`; the
plan-storage interface is defined in `change:contract`. Its dependency closure
cannot acquire workspace startup, import, IntelliJ, SQLite, or isolated-runtime
capabilities. The hosted coordinator composes the separate live plan, approval, guarded write,
verification and recovery adapters. Their IntelliJ and SQLite effects remain
explicit host dependencies; a live plan does not acquire worker-start authority.

## Formatting and structural checks

`kast.kotlin-library` applies the shared `kast.kotlin-quality` convention.
Module `check` tasks depend on Spotless, Detekt's aggregate type-resolved
analysis of every Kotlin source set, and file-length checks with 400-line
production and 600-line test limits. Source-set-specific Detekt tasks remain
available for focused diagnosis but are not duplicate `check` dependencies.
The Detekt configuration defines the structural rules. These gates are
independent of accepted module-dependency evidence.

Checked-in [baselines](../../config/README.md) admit existing Detekt findings
and fixed per-file ceilings for oversized files. New Detekt finding identities,
growth beyond recorded file ceilings, and new files exceeding the default
limits fail. Baseline changes require deliberate review; checks never regenerate
them. Detekt's finding identities do not bound growth within an existing finding.

Spotless uses ktfmt Kotlinlang style at 120 columns for authored Kotlin and
module Gradle scripts. The public-query generator owns `PublicQueryDocuments.kt`,
`PublicToolDocuments.kt`, and `PublicToolIdentity.kt`; Spotless excludes those
files and `verifyPublicQueryGeneration` checks their exact generator parity.

## Verify

```shell
./gradlew verifyKastArchitecture
./gradlew generateKastModuleKnowledge \
  -Pversion=<release-semver> \
  -PkastSourceRevision=<40-character-git-revision>
```

Read [runtime and hosts](runtime-hosts.md) for process ownership or [protocol](protocol.md) for operation ownership.
