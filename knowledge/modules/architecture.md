---
type: Kotlin Architecture
title: Verified module architecture
description: Gradle verifies module roles, dependencies, exports, and scoped agent guidance before projecting module knowledge.
resource: file://settings.gradle.kts
tags: [kotlin, gradle, architecture]
timestamp: 2026-09-11T00:00:00Z
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
  - path: build-logic/src/main/kotlin/support/architecture/knowledge/ModuleKnowledgeProjection.kt
    symbols: [ModuleKnowledgeProjection, ModuleKnowledgeDocument]
  - path: change/protocol/build.gradle.kts
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

The IntelliJ read adapter also depends on symbol contracts for the existing-IDE
[hosted query](../flows/hosted-query.md). This permits detached compiler evidence
without introducing an isolated workspace opener or importing implementation
dependencies from semantic service modules.

The separate `runtime:hosted` IDEA host has scoped endpoint-file and socket
effects. Its declared generalized-query dependencies include pure semantic
services, project-bound read adapters, and `query:protocol`. Runtime composition
also uses `query:protocol`, which depends only on contracts. Hosted composition
is excluded from isolated runtime composition. The workspace read adapter
remains the admitted project-epoch authority; transport adds no project-opening
or import permission. These declarations establish capability boundaries;
[native acceptance](../../docs/reviews/live-semantic-read-acceptance.md) separately
records the installed plugin and final default-route CLI/provider observations.

`change:protocol` similarly owns planning-request lowering and detached previews.
It depends only on `kernel`, `protocol:contract`, and `change:contract`; the
plan-storage interface is defined in `change:contract`. Its dependency closure
cannot acquire workspace startup, import, IntelliJ, SQLite, or isolated-runtime
capabilities. The retained installed adapter supplies published admission.

## Formatting and structural checks

`kast.kotlin-library` applies the shared `kast.kotlin-quality` convention.
Module `check` tasks depend on Spotless, type-resolved Detekt for production
and test source sets, and file-length checks with 400-line production and
600-line test limits. The Detekt configuration defines the structural rules.
These gates are independent of accepted module-dependency evidence.

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
