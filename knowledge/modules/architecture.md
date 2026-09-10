---
type: Kotlin Architecture
title: Verified module architecture
description: Gradle verifies module roles, dependencies, exports, and scoped agent guidance before projecting module knowledge.
resource: file://settings.gradle.kts
tags: [kotlin, gradle, architecture]
timestamp: 2026-09-09T00:00:00Z
code_sources:
  - path: settings.gradle.kts
  - path: build-logic/src/main/kotlin/kast.architecture.gradle.kts
  - path: build-logic/src/main/kotlin/support/architecture/policy/KastCleanSlateModules.kt
    symbols: [KastCleanSlateModules]
  - path: build-logic/src/main/kotlin/support/architecture/knowledge/ModuleKnowledgeProjection.kt
    symbols: [ModuleKnowledgeProjection, ModuleKnowledgeDocument]
---

# Verified module architecture

The root [settings](../../settings.gradle.kts) declares the active Gradle projects. The [architecture plugin](../../build-logic/src/main/kotlin/kast.architecture.gradle.kts) observes their production dependencies, exported dependencies, compiled classes, and role conventions before verification.

`generateKastModuleKnowledge` consumes accepted architecture evidence. Its output records the source revision, policy, observed edges, tracked `AGENTS.md` content hashes, and the governing guide set for each module. Generated build output is verification evidence, not a checked-in source of truth.

## Ownership shape

- Contract modules own domain identity and closed outcomes.
- Service modules own orchestration without platform authority.
- IntelliJ and filesystem/SQLite modules own explicit effects.
- Runtime composition connects proven contracts to effectful adapters.
- Server, indexer, App Server, and CLI modules expose transport and process boundaries.

The IntelliJ read adapter also depends on symbol contracts for the experimental
[hosted query](../flows/hosted-query.md). This permits detached compiler evidence
without introducing an isolated workspace opener or importing implementation
dependencies from semantic service modules.

The separate `runtime:hosted` IDEA host has scoped endpoint-file and socket
effects and depends on the read adapter plus contracts. It is excluded from
isolated runtime composition. The read adapter remains the sole admitted
project-epoch authority; hosted transport adds no project-opening or import
permission.

## Verify

```shell
./gradlew verifyKastArchitecture
./gradlew generateKastModuleKnowledge \
  -Pversion=<release-semver> \
  -PkastSourceRevision=<40-character-git-revision>
```

Read [runtime and hosts](runtime-hosts.md) for process ownership or [protocol](protocol.md) for operation ownership.
