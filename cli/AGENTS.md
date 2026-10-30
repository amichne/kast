---
type: Module Guide
title: "Kast cli"
description: "Owns the public semantic CLI, private JVM entry points, direct tool sessions, installation workflows, and typed output projections."
resource: "repo://cli"
tags: [kast, navigation]
timestamp: "2026-10-29T23:41:55Z"
source_revision: 90ef99b6c9edfd6c75d687865985546be29cdb16
code_sources:
  - path: build.gradle.kts
  - path: cli/src/main/kotlin/io/github/amichne/kast/cli/publicshell/KastPublicCliMain.kt
  - path: cli/src/main/kotlin/io/github/amichne/kast/cli/publicshell/PublicCliArguments.kt
  - path: cli/src/main/kotlin/io/github/amichne/kast/cli/publicshell/PublicCliDiscovery.kt
  - path: cli/src/main/kotlin/io/github/amichne/kast/cli/publicshell/PublicCliInvocation.kt
  - path: cli/src/main/kotlin/io/github/amichne/kast/cli/publicshell/PublicInvocationOwner.kt
  - path: cli/src/main/kotlin/io/github/amichne/kast/cli/publicshell/PublicShellExit.kt
  - path: cli/build.gradle.kts
  - path: cli/src/main/js/query-delivery.mjs
  - path: cli/src/main/js/query-presentation.mjs
  - path: cli/src/main/kotlin/io/github/amichne/kast/cli/KastDaemonMain.kt
  - path: cli/src/main/kotlin/io/github/amichne/kast/cli/KastServiceMain.kt
  - path: cli/src/main/kotlin/io/github/amichne/kast/cli/bootstrap/InstalledServerProjectionDocuments.kt
  - path: cli/src/main/kotlin/io/github/amichne/kast/cli/bootstrap/KastCliMain.kt
  - path: cli/src/main/kotlin/io/github/amichne/kast/cli/direct/KastDirectToolSession.kt
  - path: cli/src/main/kotlin/io/github/amichne/kast/cli/installation/InstallationWorkflow.kt
  - path: cli/src/main/kotlin/io/github/amichne/kast/cli/knowledge/InstalledKnowledge.kt
  - path: cli/src/main/kotlin/io/github/amichne/kast/cli/mcp/KastMcpMain.kt
  - path: cli/src/main/kotlin/io/github/amichne/kast/cli/mcp/McpRunnerInvocationOwner.kt
  - path: cli/src/main/kotlin/io/github/amichne/kast/cli/mcp/McpStructuredResults.kt
  - path: cli/src/main/kotlin/io/github/amichne/kast/cli/rpc/KastToolRpcMain.kt
  - path: cli/src/main/kotlin/io/github/amichne/kast/cli/rpc/KastToolRpcQuerySession.kt
  - path: cli/src/main/kotlin/io/github/amichne/kast/cli/rpc/QueryCostPlan.kt
  - path: cli/src/main/kotlin/io/github/amichne/kast/cli/rpc/QuerySessionRejectionDetail.kt
  - path: cli/src/test/kotlin/io/github/amichne/kast/cli/rpc/KastToolRpcQuerySessionTest.kt
  - path: cli/src/test/kotlin/io/github/amichne/kast/cli/rpc/KastToolRpcResultAdmissionTest.kt
---

<!-- AGENTS.md: generated navigation; keep this file checked in -->
<!-- generated: 2026-10-15 | hash: b96782f52fd4 -->

# CLI

## Purpose

Owns the public semantic CLI, private JVM entry points, direct tool sessions, installation workflows, and typed output projections.

## Key Files

- [KastPublicCliMain.kt](src/main/kotlin/io/github/amichne/kast/cli/publicshell/KastPublicCliMain.kt) - public semantic commands through shared admission and direct execution.
- [PublicCliArguments.kt](src/main/kotlin/io/github/amichne/kast/cli/publicshell/PublicCliArguments.kt) - flags, JSON input modes, and exact catalog selection.
- [PublicCliDiscovery.kt](src/main/kotlin/io/github/amichne/kast/cli/publicshell/PublicCliDiscovery.kt) - descriptors, schemas, and examples from packaged contracts.
- [PublicInvocationOwner.kt](src/main/kotlin/io/github/amichne/kast/cli/publicshell/PublicInvocationOwner.kt) - public terminal-event precedence and output delivery.
- [PublicShellExit.kt](src/main/kotlin/io/github/amichne/kast/cli/publicshell/PublicShellExit.kt) - public shell status codes and outcome classification.
- [KastMcpMain.kt](src/main/kotlin/io/github/amichne/kast/cli/mcp/KastMcpMain.kt) - the client-owned stdio MCP process.
- [McpRunnerInvocationOwner.kt](src/main/kotlin/io/github/amichne/kast/cli/mcp/McpRunnerInvocationOwner.kt) - one query worker, exact cancellation, and worker cleanup.
- [KastToolRpcMain.kt](src/main/kotlin/io/github/amichne/kast/cli/rpc/KastToolRpcMain.kt) - catalog, request-only plans, one-shot invocation, and query sessions.
- [QueryCostPlan.kt](src/main/kotlin/io/github/amichne/kast/cli/rpc/QueryCostPlan.kt) - request-shape advice before installation access or host effects.
- [KastDirectToolSession.kt](src/main/kotlin/io/github/amichne/kast/cli/direct/KastDirectToolSession.kt) - shared direct tool admission and existing-IDE composition.
- [KastToolRpcQuerySession.kt](src/main/kotlin/io/github/amichne/kast/cli/rpc/KastToolRpcQuerySession.kt) - bounded query framing and output successors.
- [query-delivery.mjs](src/main/js/query-delivery.mjs) - portable query delivery shared by generated harness extensions.
- [McpStructuredResults.kt](src/main/kotlin/io/github/amichne/kast/cli/mcp/McpStructuredResults.kt) - schema-backed MCP result projection.
- [InstallationWorkflow.kt](src/main/kotlin/io/github/amichne/kast/cli/installation/InstallationWorkflow.kt) - Control installation, prior retirement, activation, and recovery.
- [KastCliMain.kt](src/main/kotlin/io/github/amichne/kast/cli/bootstrap/KastCliMain.kt) - the private installer version entry.
- [KastServiceMain.kt](src/main/kotlin/io/github/amichne/kast/cli/KastServiceMain.kt) - private installed service control.
- [KastDaemonMain.kt](src/main/kotlin/io/github/amichne/kast/cli/KastDaemonMain.kt) - the private managed daemon entry.
- [InstalledServerProjectionDocuments.kt](src/main/kotlin/io/github/amichne/kast/cli/bootstrap/InstalledServerProjectionDocuments.kt) - installed schemas from canonical types.
- [InstalledKnowledge.kt](src/main/kotlin/io/github/amichne/kast/cli/knowledge/InstalledKnowledge.kt) - local installed knowledge reads.

## Subdirectories

- [src/main/kotlin/io/github/amichne/kast/cli/publicshell/](src/main/kotlin/io/github/amichne/kast/cli/publicshell/) - public semantic parsing, discovery, output, exit policy, and invocation lifecycle.
- [src/main/kotlin/io/github/amichne/kast/cli/direct/](src/main/kotlin/io/github/amichne/kast/cli/direct/) - shared native tool composition.
- [src/main/kotlin/io/github/amichne/kast/cli/mcp/](src/main/kotlin/io/github/amichne/kast/cli/mcp/) - MCP transport and support tools.
- [src/main/kotlin/io/github/amichne/kast/cli/rpc/](src/main/kotlin/io/github/amichne/kast/cli/rpc/) - tool RPC and query sessions.
- [src/main/kotlin/io/github/amichne/kast/cli/installation/](src/main/kotlin/io/github/amichne/kast/cli/installation/) - installation and recovery workflows.
- [src/main/kotlin/io/github/amichne/kast/cli/command/](src/main/kotlin/io/github/amichne/kast/cli/command/) - private command graph and migration code.
- [src/main/js/](src/main/js/) - portable delivery and presentation owners.

## Entry Points

- Gradle project: `:cli`.
- Focused public CLI cases: `mise exec -- ./gradlew :cli:test --tests 'io.github.amichne.kast.cli.publicshell.*'`. This task excludes the native launcher suite.
- Native launcher composition: `mise exec -- ./gradlew :publicLauncherTest`. The root task owns the real launcher, JVM child, and local-peer composition.
- The public native `kast` executable lives in `distribution/cli`.
- The private installer entry in `KastCliMain.kt` admits only `--version`.
- The installation places private service control at `share/kast/libexec/kast-service`.
- [KastToolRpcQuerySessionTest.kt](src/test/kotlin/io/github/amichne/kast/cli/rpc/KastToolRpcQuerySessionTest.kt) - query framing and retained delivery.
- [KastToolRpcResultAdmissionTest.kt](src/test/kotlin/io/github/amichne/kast/cli/rpc/KastToolRpcResultAdmissionTest.kt) - result admission and rejection cases.

## Navigation Hints

- Public shell commands default to `COMPLETE`. Keep explicit `COMMON` restrictions without promotion.
- Packaged discovery and request planning use installed contracts before workspace or host composition.
- Keep `publicshell` exit and signal policy separate from private RPC and MCP behavior.
- `PublicInvocationOwner` accepts the first terminal event. `PublicCliInvocation` owns its worker and signal handling.
- Cancellation requests and child exit do not prove native semantic settlement.
- Direct MCP and tool RPC sessions default to `COMMON`. Select `--catalog=COMPLETE` before the command for advanced grammar.
- Keep the selected catalog stable through discovery, public admission, and retained reads. Pi native MCP registration explicitly selects `COMPLETE`.

- Trace MCP and tool RPC through `KastDirectToolSession`. The MCP transport does not start an App Server broker.
- Fresh and replacement installations select private endpoints and do not enable a daemon. Legacy retirement and recovery keep their ownership rules.
- MCP starts preparation at legacy initialization or its first admitted modern request. Semantic calls wait for the selected workspace endpoint.
- In `RUNNER_CONTROLLED`, the caller owns semantic cancellation. The input owner accepts `notifications/cancelled` for the exact request identifier. End-of-input cancels the active query and drains its worker. A cancelled query emits no MCP response.
- For query output, trace canonical types into `InstalledServerProjectionDocuments` and the shared JavaScript delivery and presentation owners.
- For source reads and session rejection, examine `KastToolRpcQuerySession` and `QuerySessionRejectionDetail`. Issued selectors keep their current authority.
- For generated Copilot and Pi sections, change the delivery owner or public-query generator. Preserve authored extension adapters.
- Read the [root guide](../AGENTS.md) for engineering rules and optional OpenWiki retrieval.
