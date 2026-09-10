---
type: Kotlin Architecture
title: Verified module architecture
description: Gradle verifies module roles, dependencies, exports, and scoped agent guidance before projecting module knowledge.
resource: file://settings.gradle.kts
tags: [kotlin, gradle, architecture]
timestamp: 2026-09-10T00:00:00Z
code_sources:
  - path: settings.gradle.kts
  - path: build-logic/src/main/kotlin/kast.architecture.gradle.kts
  - path: build-logic/src/main/kotlin/support/architecture/policy/KastCleanSlateModules.kt
    symbols: [KastCleanSlateModules]
  - path: build-logic/src/main/kotlin/support/architecture/knowledge/ModuleKnowledgeProjection.kt
    symbols: [ModuleKnowledgeProjection, ModuleKnowledgeDocument]
  - path: query/protocol/build.gradle.kts
  - path: runtime/hosted/build.gradle.kts
  - path: runtime/composition/build.gradle.kts
---

# Verified module architecture

The root [settings](../../settings.gradle.kts) declares the active Gradle projects. The [architecture plugin](../../build-logic/src/main/kotlin/kast.architecture.gradle.kts) observes their production dependencies, exported dependencies, compiled classes, and role conventions before verification.

`generateKastModuleKnowledge` consumes accepted architecture evidence. Its output records the source revision, policy, observed edges, tracked `AGENTS.md` content hashes, and the governing guide set for each module. Generated build output is verification evidence, not a checked-in source of truth.

## Ownership shape

- Contract modules own domain identity and closed outcomes.
- Service modules own orchestration without platform authority.
- `query:protocol` has the service role and owns shared semantic-read request admission, reference codecs, and canonical projection using contract dependencies.
- IntelliJ and filesystem/SQLite modules own explicit effects.
- Runtime composition connects proven contracts to effectful adapters.
- Server, indexer, App Server, and CLI modules expose transport and process boundaries.

The IntelliJ read adapter also depends on symbol contracts for the experimental
[hosted query](../flows/hosted-query.md). This permits detached compiler evidence
without introducing an isolated workspace opener or importing implementation
dependencies from semantic service modules.

The separate `runtime:hosted` IDEA host has scoped endpoint-file and socket
effects. Its declared generalized-query dependencies include pure semantic
services, project-bound read adapters, and `query:protocol`. Runtime composition
also uses `query:protocol`, which depends only on contracts. Hosted composition
is excluded from isolated runtime composition. The workspace read adapter
remains the admitted project-epoch authority; transport adds no project-opening
or import permission. These dependency declarations do not establish native
cutover or manual acceptance of the new route.

## Verify

```shell
./gradlew verifyKastArchitecture
./gradlew generateKastModuleKnowledge \
  -Pversion=<release-semver> \
  -PkastSourceRevision=<40-character-git-revision>
```

Read [runtime and hosts](runtime-hosts.md) for process ownership or [protocol](protocol.md) for operation ownership.
