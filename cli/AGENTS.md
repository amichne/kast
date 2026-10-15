---
type: Module Guide
title: "Kast cli"
description: "Owns private JVM entry points, direct MCP and tool RPC sessions, installation workflows, and typed output projections."
resource: "repo://cli"
tags: [kast, navigation]
timestamp: "2026-10-14T23:25:10Z"
source_revision: e014a489bfd7e69cbf2c4b61140ee771d857a03e
code_sources:
  - path: cli/build.gradle.kts
  - path: cli/src/main/js/query-delivery.mjs
  - path: cli/src/main/kotlin/io/github/amichne/kast/cli/KastDaemonMain.kt
  - path: cli/src/main/kotlin/io/github/amichne/kast/cli/KastServiceMain.kt
  - path: cli/src/main/kotlin/io/github/amichne/kast/cli/bootstrap/InstalledServerProjectionDocuments.kt
  - path: cli/src/main/kotlin/io/github/amichne/kast/cli/bootstrap/KastCliMain.kt
  - path: cli/src/main/kotlin/io/github/amichne/kast/cli/direct/KastDirectToolSession.kt
  - path: cli/src/main/kotlin/io/github/amichne/kast/cli/installation/InstallationWorkflow.kt
  - path: cli/src/main/kotlin/io/github/amichne/kast/cli/knowledge/InstalledKnowledge.kt
  - path: cli/src/main/kotlin/io/github/amichne/kast/cli/mcp/KastMcpMain.kt
  - path: cli/src/main/kotlin/io/github/amichne/kast/cli/mcp/McpStructuredResults.kt
  - path: cli/src/main/kotlin/io/github/amichne/kast/cli/rpc/KastToolRpcMain.kt
  - path: cli/src/main/kotlin/io/github/amichne/kast/cli/rpc/KastToolRpcQuerySession.kt
  - path: cli/src/test/kotlin/io/github/amichne/kast/cli/rpc/KastToolRpcQuerySessionTest.kt
  - path: cli/src/test/kotlin/io/github/amichne/kast/cli/rpc/KastToolRpcResultAdmissionTest.kt
---

<!-- AGENTS.md: generated navigation; keep this file checked in -->
<!-- generated: 2026-10-14 | hash: 71ef615ec17d -->

# cli

## Purpose

Owns private JVM entry points, direct MCP and tool RPC sessions, installation workflows, and typed output projections.

## Key Files

- [KastMcpMain.kt](src/main/kotlin/io/github/amichne/kast/cli/mcp/KastMcpMain.kt) - the client-owned stdio MCP process.
- [KastToolRpcMain.kt](src/main/kotlin/io/github/amichne/kast/cli/rpc/KastToolRpcMain.kt) - catalog, one-shot invocation, and query-session entry.
- [KastDirectToolSession.kt](src/main/kotlin/io/github/amichne/kast/cli/direct/KastDirectToolSession.kt) - shared direct tool admission and existing-IDE composition.
- [KastToolRpcQuerySession.kt](src/main/kotlin/io/github/amichne/kast/cli/rpc/KastToolRpcQuerySession.kt) - bounded query framing and output successors.
- [query-delivery.mjs](src/main/js/query-delivery.mjs) - portable query delivery shared by generated harness extensions.
- [McpStructuredResults.kt](src/main/kotlin/io/github/amichne/kast/cli/mcp/McpStructuredResults.kt) - schema-backed MCP result projection.
- [InstallationWorkflow.kt](src/main/kotlin/io/github/amichne/kast/cli/installation/InstallationWorkflow.kt) - Control installation, prior retirement, activation, and recovery.
- [KastCliMain.kt](src/main/kotlin/io/github/amichne/kast/cli/bootstrap/KastCliMain.kt) - the private installer version entry.
- [KastServiceMain.kt](src/main/kotlin/io/github/amichne/kast/cli/KastServiceMain.kt) - private installed service control.
- [KastDaemonMain.kt](src/main/kotlin/io/github/amichne/kast/cli/KastDaemonMain.kt) - the private managed daemon entry.
- [InstalledServerProjectionDocuments.kt](src/main/kotlin/io/github/amichne/kast/cli/bootstrap/InstalledServerProjectionDocuments.kt) - installed schemas from canonical serializers.
- [InstalledKnowledge.kt](src/main/kotlin/io/github/amichne/kast/cli/knowledge/InstalledKnowledge.kt) - local installed knowledge reads.

## Subdirectories

- [src/main/kotlin/io/github/amichne/kast/cli/direct/](src/main/kotlin/io/github/amichne/kast/cli/direct/) - shared native tool composition.
- [src/main/kotlin/io/github/amichne/kast/cli/mcp/](src/main/kotlin/io/github/amichne/kast/cli/mcp/) - MCP transport and support tools.
- [src/main/kotlin/io/github/amichne/kast/cli/rpc/](src/main/kotlin/io/github/amichne/kast/cli/rpc/) - tool RPC and query sessions.
- [src/main/kotlin/io/github/amichne/kast/cli/installation/](src/main/kotlin/io/github/amichne/kast/cli/installation/) - installation and recovery workflows.
- [src/main/kotlin/io/github/amichne/kast/cli/command/](src/main/kotlin/io/github/amichne/kast/cli/command/) - private command graph and migration code.
- [src/main/js/](src/main/js/) - portable delivery and presentation owners.

## Entry Points

- Gradle project: `:cli`.
- The public native `kast` executable lives in `distribution/cli`.
- The private installer entry in `KastCliMain.kt` admits only `--version`.
- The installation places private service control at `share/kast/libexec/kast-service`.
- [KastToolRpcQuerySessionTest.kt](src/test/kotlin/io/github/amichne/kast/cli/rpc/KastToolRpcQuerySessionTest.kt) - query framing and retained delivery.
- [KastToolRpcResultAdmissionTest.kt](src/test/kotlin/io/github/amichne/kast/cli/rpc/KastToolRpcResultAdmissionTest.kt) - result admission and rejection cases.

## Navigation Hints

- Trace MCP and tool RPC through `KastDirectToolSession`. The MCP transport does not start an App Server broker.
- MCP starts preparation at legacy initialization or its first admitted modern request. Semantic calls wait for the selected workspace endpoint.
- For query output, trace canonical serializers into `InstalledServerProjectionDocuments` and the shared JavaScript delivery owner.
- For generated Copilot and Pi sections, change the delivery owner or public-query generator. Preserve authored extension adapters.
- Read the [root guide](../AGENTS.md) for engineering rules and optional OpenWiki retrieval.
