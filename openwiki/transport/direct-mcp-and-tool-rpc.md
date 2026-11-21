---
type: System Explanation
title: Direct MCP
description: Explains the native Kotlin MCP entrypoint, shared tool registration, protocol-era admission, per-request workspace leases, and canonical result preservation.
tags:
  - kast
  - transport
code_sources:
  - path: cli/src/main/kotlin/io/github/amichne/kast/cli/mcp/KastMcpMain.kt
  - path: cli/src/main/kotlin/io/github/amichne/kast/cli/mcp/KastMcpServer.kt
  - path: cli/src/main/kotlin/io/github/amichne/kast/cli/mcp/McpStructuredResults.kt
  - path: cli/src/main/kotlin/io/github/amichne/kast/cli/direct/KastDirectToolSession.kt
  - path: cli/src/main/kotlin/io/github/amichne/kast/cli/direct/ManagedToolInvocation.kt
  - path: cli/src/main/kotlin/io/github/amichne/kast/cli/direct/DirectToolRegistration.kt
  - path: cli/src/main/kotlin/io/github/amichne/kast/cli/direct/DirectCatalogArguments.kt
  - path: cli/src/main/kotlin/io/github/amichne/kast/cli/direct/DirectToolDocument.kt
  - path: cli/src/main/kotlin/io/github/amichne/kast/cli/CanonicalLiveOperationSupport.kt
  - path: cli/src/main/kotlin/io/github/amichne/kast/cli/KastControlMain.kt
  - path: cli/src/main/control-launchers/bin/kast-mcp
  - path: cli/src/main/kotlin/io/github/amichne/kast/cli/publicshell/KastPublicCliMain.kt
  - path: cli/src/main/kotlin/io/github/amichne/kast/cli/rpc/QueryCostPlan.kt
  - path: cli/src/main/kotlin/io/github/amichne/kast/cli/rpc/QuerySessionRejectionDetail.kt
  - path: distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/ManagedWorkspaceQuery.kt
  - path: cli/src/test/kotlin/io/github/amichne/kast/cli/mcp/KastMcpServerTest.kt
  - path: cli/src/test/kotlin/io/github/amichne/kast/cli/rpc/QueryCostPlanTest.kt
  - path: cli/src/test/kotlin/io/github/amichne/kast/cli/rpc/QuerySessionRejectionDetailTest.kt
sources:
  - id: openwiki-source-eee64eb8ca3aa3550601ed99
    resource: repo://app-server/src/main/kotlin/io/github/amichne/kast/appserver/WorkspaceOperationAdmission.kt
  - id: openwiki-source-ae1ffead9bda416bfbaf2250
    resource: repo://cli/src/main/kotlin/io/github/amichne/kast/cli/direct/DirectCatalogArguments.kt
  - id: openwiki-source-7685e27063adfdefffa21252
    resource: repo://cli/src/main/kotlin/io/github/amichne/kast/cli/direct/KastDirectToolSession.kt
  - id: openwiki-source-e2eb0f864f441066834169f4
    resource: repo://cli/src/main/kotlin/io/github/amichne/kast/cli/direct/ManagedToolInvocation.kt
  - id: openwiki-source-7904a856aedff77e554352c6
    resource: repo://cli/src/main/kotlin/io/github/amichne/kast/cli/KastControlMain.kt
  - id: openwiki-source-32409ff9180fb1de21a3dea3
    resource: repo://cli/src/main/kotlin/io/github/amichne/kast/cli/mcp/KastMcpMain.kt
  - id: openwiki-source-59b559d53d91096a82b485a2
    resource: repo://cli/src/main/kotlin/io/github/amichne/kast/cli/mcp/KastMcpServer.kt
  - id: openwiki-source-a566d74d2962e0f1901a1d38
    resource: repo://cli/src/main/kotlin/io/github/amichne/kast/cli/publicshell/KastPublicCliMain.kt
  - id: openwiki-source-80668d86bee80c4df5797fa0
    resource: repo://cli/src/main/kotlin/io/github/amichne/kast/cli/publicshell/PublicHookPreparation.kt
  - id: openwiki-source-210e5a1fdf9d35bc022594a2
    resource: repo://cli/src/main/kotlin/io/github/amichne/kast/cli/rpc/QueryCostPlan.kt
  - id: openwiki-source-ed6011399144254764b19542
    resource: repo://cli/src/test/kotlin/io/github/amichne/kast/cli/publicshell/PublicHookManagedPreparationTest.kt
  - id: openwiki-source-ecb21af09f4a49f9e40342dd
    resource: repo://distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/ManagedWorkspaceQuery.kt
generated: { by: "codex", at: "2026-11-21T22:16:17.145Z" }
verified:
  - by: openwiki/0.9.0
    at: 2026-11-21T22:16:17.145Z
---

# Direct MCP

## One MCP entry

An agent runs the MCP server as `kast --internal-entrypoint=mcp`, usually through the `agents.toml` entry that dotagents applies.
The Go Control executable passes that entry to `KastControlMain`, which dispatches it to `KastMcpMain`.
The installed `kast-mcp` launcher selects the same entry.
`KastMcpMain` accepts an optional `--catalog=COMMON` or `--catalog=COMPLETE` selector; `COMMON` is the default and any other catalog argument rejects.
The MCP client owns the stdio process. The transport starts no App Server or broker.
Tool RPC and its query session are removed; MCP and the public CLI are the only direct tool transports.

## Share native tool composition

`KastDirectToolSession` composes compiled registration, exact root discovery, workspace preparation, and public tool invocation.
Compiled registration owns invocation admission.
Schema projection supplies discovery documents without a second routing owner.
Installation lifecycle admission precedes startup and invocation.
A stopped or unproved installation returns a typed rejection.

The session routes public changes to the shared change owner.
Read operations use the project socket client and the admitted public request.
CLI and MCP invocation select preparation through typed operation admission.
Fresh discovery and diagnostics prepare the workspace.
Issued source and retained-reference reads use existing authority without an eager session preparation step.
`ManagedToolInvocation` opens the shared `ManagedWorkspaceQuery` lease for each request, not for the lifetime of the transport session.
It rechecks Control admission after acquiring the lease, binds the private HOME and generation, and closes request-owned preparation jobs after execution.
The first explicit preparation request can create the exact-root trust record; retained reads require that record already to exist.
Explicit internal `workspace wait` uses this same preparation admission and lease, including inherited-state rejection.
Its typed preparation failures remain distinct from semantic CLI output, and session-start hooks remain passive.
Installed product and runtime selection is local. Missing runtime bytes reject rather than downloading a release.
The public CLI and MCP choose different client protocols around that same session.
Neither transport creates another semantic implementation.

## MCP admits one protocol era

`KastMcpServer` admits either legacy initialization or the supported modern metadata envelope.
The connection cannot switch eras after admission.
Modern requests need the supported protocol version.
The server distinguishes initialization, discovery, tool listing, calls, and unsupported methods.

Legacy initialization and modern discovery are passive.
Only an explicit semantic or health invocation enters managed workspace admission and preparation when its operation requires it.
Session-start hooks also remain passive; an internal explicit wait path is a distinct lifecycle request.
Tool annotations describe the preparation effect as well as the tool's nominal read behavior.
Preparation can save editor buffers and refresh the project.
A read query therefore does not imply an entirely effect-free client invocation.
`McpStructuredResults` preserves canonical structured results through the output contract.
`INVOCATION_IN_PROGRESS` rejects an overlapping tool call on the same connection without reporting the active query as failed.

## Request-only query advice

The public `kast query plan` command reads public RUN JSON before installation access or host preparation.
`QueryCostPlan` reuses public admission and preserves the canonical request, scopes, expansion domains, and requested budget.
Its basis is `REQUEST_SHAPE_ONLY`, and its estimate is `NOT_MEASURED`.
Advice identifies broad discovery, fuzzy names, late filters, unrestricted expansion, and unbounded walk depth.
It does not measure cost, admit an execution budget, or change the request.
READ_SOURCE, READ_RESULT, and RESUME reject with RUN_REQUIRED.

`QuerySessionRejectionDetail` keeps typed output-allowance, detail-selector, and authority details beside a tool rejection.
The rejection keeps expected and requested authority and the next permitted local action.
The canonical owner still decides semantic freshness.

## Focused tests

- [KastMcpServerTest.kt](../../cli/src/test/kotlin/io/github/amichne/kast/cli/mcp/KastMcpServerTest.kt)
- [QueryCostPlanTest.kt](../../cli/src/test/kotlin/io/github/amichne/kast/cli/rpc/QueryCostPlanTest.kt)
- [QuerySessionRejectionDetailTest.kt](../../cli/src/test/kotlin/io/github/amichne/kast/cli/rpc/QuerySessionRejectionDetailTest.kt)

## Source owners

- [KastMcpMain.kt](../../cli/src/main/kotlin/io/github/amichne/kast/cli/mcp/KastMcpMain.kt)
- [KastMcpServer.kt](../../cli/src/main/kotlin/io/github/amichne/kast/cli/mcp/KastMcpServer.kt)
- [McpStructuredResults.kt](../../cli/src/main/kotlin/io/github/amichne/kast/cli/mcp/McpStructuredResults.kt)
- [KastDirectToolSession.kt](../../cli/src/main/kotlin/io/github/amichne/kast/cli/direct/KastDirectToolSession.kt)
- [ManagedToolInvocation.kt](../../cli/src/main/kotlin/io/github/amichne/kast/cli/direct/ManagedToolInvocation.kt)
- [DirectToolRegistration.kt](../../cli/src/main/kotlin/io/github/amichne/kast/cli/direct/DirectToolRegistration.kt)
- [DirectCatalogArguments.kt](../../cli/src/main/kotlin/io/github/amichne/kast/cli/direct/DirectCatalogArguments.kt)
- [KastControlMain.kt](../../cli/src/main/kotlin/io/github/amichne/kast/cli/KastControlMain.kt)
- [QueryCostPlan.kt](../../cli/src/main/kotlin/io/github/amichne/kast/cli/rpc/QueryCostPlan.kt)

## Read next

- [Public request admission](../queries/public-admission.md)
- [Agent context and skill delivery](harness-delivery.md)
