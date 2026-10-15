---
type: System Explanation
title: Direct MCP and tool RPC
description: Explains shared tool registration, protocol-era admission, one-shot RPC,
  invocation-scoped query sessions, and canonical result preservation.
tags:
- kast
- transport
code_sources:
- path: cli/src/main/kotlin/io/github/amichne/kast/cli/direct/KastDirectToolSession.kt
- path: cli/src/main/kotlin/io/github/amichne/kast/cli/mcp/KastMcpServer.kt
- path: cli/src/main/kotlin/io/github/amichne/kast/cli/rpc/KastToolRpcMain.kt
- path: cli/src/main/kotlin/io/github/amichne/kast/cli/rpc/KastToolRpcQuerySession.kt
- path: cli/src/main/kotlin/io/github/amichne/kast/cli/mcp/McpStructuredResults.kt
- path: cli/src/main/kotlin/io/github/amichne/kast/cli/direct/DirectToolRegistration.kt
- path: cli/src/main/kotlin/io/github/amichne/kast/cli/CanonicalLiveOperationSupport.kt
- path: cli/src/main/kotlin/io/github/amichne/kast/cli/command/CliOptions.kt
- path: cli/src/main/kotlin/io/github/amichne/kast/cli/command/appserver/AppServerCommands.kt
- path: cli/src/main/kotlin/io/github/amichne/kast/cli/command/change/ChangeCommands.kt
- path: cli/src/main/kotlin/io/github/amichne/kast/cli/command/codex/CodexCommands.kt
- path: cli/src/main/kotlin/io/github/amichne/kast/cli/command/ide/IdeCommands.kt
- path: cli/src/main/kotlin/io/github/amichne/kast/cli/command/product/ProductCommands.kt
- path: cli/src/main/kotlin/io/github/amichne/kast/cli/command/source/SourceCommands.kt
- path: cli/src/main/kotlin/io/github/amichne/kast/cli/command/tool/PublicToolCommands.kt
- path: cli/src/main/kotlin/io/github/amichne/kast/cli/command/workspace/IndexCommands.kt
- path: cli/src/main/kotlin/io/github/amichne/kast/cli/direct/DirectToolDocument.kt
- path: cli/src/main/kotlin/io/github/amichne/kast/cli/ide/ExistingIdeCli.kt
- path: cli/src/main/kotlin/io/github/amichne/kast/cli/mcp/KastMcpMain.kt
- path: cli/src/test/kotlin/io/github/amichne/kast/cli/mcp/KastMcpServerTest.kt
- path: cli/src/test/kotlin/io/github/amichne/kast/cli/rpc/KastToolRpcBridgeTest.kt
- path: cli/src/test/kotlin/io/github/amichne/kast/cli/rpc/KastToolRpcQuerySessionProcessTest.kt
verified:
  - by: openwiki/0.6.1
    at: 2026-10-15T00:33:05.932Z
sources:
  - id: openwiki-source-7685e27063adfdefffa21252
    resource: repo://cli/src/main/kotlin/io/github/amichne/kast/cli/direct/KastDirectToolSession.kt
  - id: openwiki-source-59b559d53d91096a82b485a2
    resource: repo://cli/src/main/kotlin/io/github/amichne/kast/cli/mcp/KastMcpServer.kt
  - id: openwiki-source-1d161f3eb7933044519fd33a
    resource: repo://cli/src/main/kotlin/io/github/amichne/kast/cli/rpc/KastToolRpcMain.kt
  - id: openwiki-source-021e60454b473f51dc29603a
    resource: repo://cli/src/main/kotlin/io/github/amichne/kast/cli/rpc/KastToolRpcQuerySession.kt
generated: { by: "codex", at: "2026-10-15T00:33:05.932Z" }
---

# Direct MCP and tool RPC

## Share native tool composition

`KastDirectToolSession` composes compiled registration, exact root discovery, workspace preparation, and public tool invocation.
Compiled registration owns invocation admission.
Schema projection supplies discovery documents without a second routing owner.
Installation lifecycle admission precedes startup and invocation.
A stopped or unproved installation returns a typed rejection.

The session routes public changes to the shared change owner.
Read operations use the existing-IDE client and admitted public request.
MCP and harness-neutral RPC choose different client protocols around that same session.
Neither transport creates another semantic implementation.

## MCP admits one protocol era

`KastMcpServer` admits either legacy initialization or the supported modern metadata envelope.
The connection cannot switch eras after admission.
Modern requests need the supported protocol version.
The server distinguishes initialization, discovery, tool listing, calls, and unsupported methods.

Initialization starts workspace preparation once.
Tool annotations describe the preparation effect as well as the tool's nominal read behavior.
Preparation can save editor buffers and refresh the project.
A read query therefore does not imply an entirely effect-free client invocation.
`McpStructuredResults` preserves canonical structured results through the output contract.

## RPC owns bounded invocation scope

`KastToolRpcMain` exposes `catalog`, `call`, and `query-session` to agent adapters.
It bounds input bytes before JSON and public contract admission.
Unknown tools, malformed arguments, unavailable observation, stopped installation, and invalid results remain finite failures.
The bridge does not rewrite a canonical rejection as success.

`KastToolRpcQuerySession` owns one caller-owned child and at most 64 request records.
After its first query, the session accepts only detail reads or output continuation issued by the preceding result.
A second RUN, foreign result handle, or foreign continuation rejects the transition.
The child ends when the state closes or the fixed request allowance ends.

This session saves repeated process composition during one logical invocation.
It does not permit semantic replay, arbitrary handle adoption, or continued execution through an expired authority.
Focused bridge and process tests cover these transport contracts.
Installed compiler authority remains a separate host obligation.

## Focused tests

- [KastMcpServerTest.kt](../../cli/src/test/kotlin/io/github/amichne/kast/cli/mcp/KastMcpServerTest.kt)
- [KastToolRpcBridgeTest.kt](../../cli/src/test/kotlin/io/github/amichne/kast/cli/rpc/KastToolRpcBridgeTest.kt)
- [KastToolRpcQuerySessionProcessTest.kt](../../cli/src/test/kotlin/io/github/amichne/kast/cli/rpc/KastToolRpcQuerySessionProcessTest.kt)

## Source owners

- [KastDirectToolSession.kt](../../cli/src/main/kotlin/io/github/amichne/kast/cli/direct/KastDirectToolSession.kt)
- [KastMcpServer.kt](../../cli/src/main/kotlin/io/github/amichne/kast/cli/mcp/KastMcpServer.kt)
- [KastToolRpcMain.kt](../../cli/src/main/kotlin/io/github/amichne/kast/cli/rpc/KastToolRpcMain.kt)
- [KastToolRpcQuerySession.kt](../../cli/src/main/kotlin/io/github/amichne/kast/cli/rpc/KastToolRpcQuerySession.kt)
- [McpStructuredResults.kt](../../cli/src/main/kotlin/io/github/amichne/kast/cli/mcp/McpStructuredResults.kt)
- [DirectToolRegistration.kt](../../cli/src/main/kotlin/io/github/amichne/kast/cli/direct/DirectToolRegistration.kt)
- [CanonicalLiveOperationSupport.kt](../../cli/src/main/kotlin/io/github/amichne/kast/cli/CanonicalLiveOperationSupport.kt)
- [CliOptions.kt](../../cli/src/main/kotlin/io/github/amichne/kast/cli/command/CliOptions.kt)
- [AppServerCommands.kt](../../cli/src/main/kotlin/io/github/amichne/kast/cli/command/appserver/AppServerCommands.kt)
- [ChangeCommands.kt](../../cli/src/main/kotlin/io/github/amichne/kast/cli/command/change/ChangeCommands.kt)
- [CodexCommands.kt](../../cli/src/main/kotlin/io/github/amichne/kast/cli/command/codex/CodexCommands.kt)
- [IdeCommands.kt](../../cli/src/main/kotlin/io/github/amichne/kast/cli/command/ide/IdeCommands.kt)
- [ProductCommands.kt](../../cli/src/main/kotlin/io/github/amichne/kast/cli/command/product/ProductCommands.kt)
- [SourceCommands.kt](../../cli/src/main/kotlin/io/github/amichne/kast/cli/command/source/SourceCommands.kt)
- [PublicToolCommands.kt](../../cli/src/main/kotlin/io/github/amichne/kast/cli/command/tool/PublicToolCommands.kt)
- [IndexCommands.kt](../../cli/src/main/kotlin/io/github/amichne/kast/cli/command/workspace/IndexCommands.kt)
- [DirectToolDocument.kt](../../cli/src/main/kotlin/io/github/amichne/kast/cli/direct/DirectToolDocument.kt)
- [ExistingIdeCli.kt](../../cli/src/main/kotlin/io/github/amichne/kast/cli/ide/ExistingIdeCli.kt)
- [KastMcpMain.kt](../../cli/src/main/kotlin/io/github/amichne/kast/cli/mcp/KastMcpMain.kt)

## Read next

- [Public request admission](../queries/public-admission.md)
- [Agent adapters](harness-delivery.md)
