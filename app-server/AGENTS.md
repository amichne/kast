---
type: Module Guide
title: "Kast app-server"
description: "Owns the shared coordinator, workspace preparation, host adapters, Codex transport, and public tool admission."
resource: "repo://app-server"
tags: [kast, navigation]
timestamp: "2026-10-22T05:31:45Z"
source_revision: ebc805014ada9e3eb1b85ef611e41bd176af8d80
code_sources:
  - path: app-server/README.md
  - path: app-server/build.gradle.kts
  - path: app-server/docs/compatibility.md
  - path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/InstalledCoordinator.kt
  - path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/KastCodexMain.kt
  - path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/ide/ExistingIdeDocuments.kt
  - path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/ide/ExistingIdeSocketClient.kt
  - path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/provider/KastInvocationInput.kt
  - path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/runtime/WorkspaceExecutionAccess.kt
  - path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/query/PublicToolContract.kt
  - path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/runtime/DaemonUpgradeGate.kt
  - path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/runtime/WorkspacePreparations.kt
  - path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/storage/PrivateRecordFiles.kt
  - path: app-server/src/main/resources/io/github/amichne/kast/appserver/query/tools.schema.json
  - path: app-server/src/test/kotlin/io/github/amichne/kast/appserver/query/PublicToolCompletionPolicyTest.kt
  - path: app-server/src/test/kotlin/io/github/amichne/kast/appserver/query/PublicToolContractTest.kt
---

<!-- AGENTS.md: generated navigation; keep this file checked in -->
<!-- generated: 2026-10-14 | hash: 79fb7323575e -->

# app-server

## Purpose

Owns the shared coordinator, workspace preparation, host adapters, Codex transport, and public tool admission.

## Key Files

- [README.md](README.md) - coordinator lifecycle and integration architecture.
- [compatibility.md](docs/compatibility.md) - compatibility evidence and release gates.
- [KastCodexMain.kt](src/main/kotlin/io/github/amichne/kast/appserver/KastCodexMain.kt) - the Codex-facing process entry.
- [InstalledCoordinator.kt](src/main/kotlin/io/github/amichne/kast/appserver/InstalledCoordinator.kt) - installed coordinator composition.
- [WorkspacePreparations.kt](src/main/kotlin/io/github/amichne/kast/appserver/runtime/WorkspacePreparations.kt) - coalesced IDEA preparation and retained outcomes.
- [DaemonUpgradeGate.kt](src/main/kotlin/io/github/amichne/kast/appserver/runtime/DaemonUpgradeGate.kt) - session and management admission during upgrades.
- [ExistingIdeDocuments.kt](src/main/kotlin/io/github/amichne/kast/appserver/ide/ExistingIdeDocuments.kt) - endpoint identity and process ownership admission.
- [PublicToolContract.kt](src/main/kotlin/io/github/amichne/kast/appserver/query/PublicToolContract.kt) - public syntax admission and schema identity.
- [tools.schema.json](src/main/resources/io/github/amichne/kast/appserver/query/tools.schema.json) - the authored public schema and examples.
- [PrivateRecordFiles.kt](src/main/kotlin/io/github/amichne/kast/appserver/storage/PrivateRecordFiles.kt) - private durable records, locks, and atomic writes.

## Subdirectories

- [src/main/kotlin/io/github/amichne/kast/appserver/core/](src/main/kotlin/io/github/amichne/kast/appserver/core/) - broker and session types.
- [Host adapters](src/main/kotlin/io/github/amichne/kast/appserver/host/AGENTS.md) - Codex host adapters and authored rules.
- [Public query admission](src/main/kotlin/io/github/amichne/kast/appserver/query/AGENTS.md) - public admission and canonical mappings with authored rules.
- [src/main/kotlin/io/github/amichne/kast/appserver/ide/](src/main/kotlin/io/github/amichne/kast/appserver/ide/) - existing-IDE endpoints and live compatibility.
- [src/main/kotlin/io/github/amichne/kast/appserver/protocol/](src/main/kotlin/io/github/amichne/kast/appserver/protocol/) - Codex and Copilot transport projections.
- [src/main/kotlin/io/github/amichne/kast/appserver/runtime/](src/main/kotlin/io/github/amichne/kast/appserver/runtime/) - registration, preparation, session, and invocation control.
- [src/main/kotlin/io/github/amichne/kast/appserver/provider/](src/main/kotlin/io/github/amichne/kast/appserver/provider/) - provider requests and projections.

## Entry Points

- Gradle project: `:app-server`.
- Run `./gradlew :app-server:verifyPublicQueryGeneration` after public schema or generator changes.
- Private service entry points live in `cli`. Direct MCP and tool RPC share `KastDirectToolSession` there.
- [PublicToolContractTest.kt](src/test/kotlin/io/github/amichne/kast/appserver/query/PublicToolContractTest.kt) - public admission cases.
- [PublicToolCompletionPolicyTest.kt](src/test/kotlin/io/github/amichne/kast/appserver/query/PublicToolCompletionPolicyTest.kt) - strict completion policy cases.

## Navigation Hints

- For host faults, read the nested host guide before changing transport arguments or process ownership.
- For public syntax, read the nested query guide, authored schema, and canonical mappings.
- For preparation failures, trace the exact root through `WorkspacePreparations` into the existing-IDE client.
- Coordinator readiness alone does not establish workspace enrollment or semantic availability.
- `KastInvocationInput` keeps the admitted execution mode through provider dispatch. `WorkspaceExecutionAccess.RunnerControlledQuery` carries an unlimited semantic lifetime only for a schema-admitted query with a read effect. Provider dispatch and the workspace lane omit semantic deadlines for that access. Queue admission and output limits keep their existing grants.
- `ExistingIdeSocketClient` bounds connection and request writes. For an admitted `RUNNER_CONTROLLED` query, it waits for the semantic response until caller cancellation.
- Read the [root guide](../AGENTS.md) for engineering rules and optional OpenWiki retrieval.
