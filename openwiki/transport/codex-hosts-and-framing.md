---
type: System Explanation
title: Codex hosts and protocol framing
description: Explains protocol qualification, shared broker ownership, desktop JSONL
  framing, remote clients, and finite protocol rejection.
tags:
- kast
- transport
code_sources:
- path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/protocol/codex/CodexProtocolQualifier.kt
- path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/protocol/codex/CodexProtocolAdapter.kt
- path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/host/DesktopStdioHost.kt
- path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/host/CliRemoteClientHost.kt
- path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/core/AgentSessionBootstrap.kt
- path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/host/admission/CodexHostExecutable.kt
- path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/protocol/codex/BrokerFailureDocument.kt
- path: app-server/src/test/kotlin/io/github/amichne/kast/appserver/protocol/codex/CodexProtocolQualifierTest.kt
- path: app-server/src/test/kotlin/io/github/amichne/kast/appserver/host/DesktopStdioHostTest.kt
- path: app-server/src/test/kotlin/io/github/amichne/kast/appserver/host/DesktopStdioProtocolIntegrationTest.kt
verified:
  - by: openwiki/0.6.1
    at: 2026-10-15T00:33:05.932Z
sources:
  - id: openwiki-source-fd0bb1368910225038bc043b
    resource: repo://app-server/src/main/kotlin/io/github/amichne/kast/appserver/host/CliRemoteClientHost.kt
  - id: openwiki-source-0a2b4cfd52d20673cdc79143
    resource: repo://app-server/src/main/kotlin/io/github/amichne/kast/appserver/host/DesktopStdioHost.kt
  - id: openwiki-source-41fd3211f83e8818b2bbfa99
    resource: repo://app-server/src/main/kotlin/io/github/amichne/kast/appserver/protocol/codex/CodexProtocolAdapter.kt
  - id: openwiki-source-bfcf45ac084c05e7ecc799ce
    resource: repo://app-server/src/main/kotlin/io/github/amichne/kast/appserver/protocol/codex/CodexProtocolQualifier.kt
generated: { by: "codex", at: "2026-10-15T00:33:05.932Z" }
---

# Codex hosts and protocol framing

## Qualify the protocol before host selection

`CodexProtocolQualifier` admits the upstream executable, Codex home, temporary root, and collection limits.
It collects schemas from that executable and derives a digest from the exact schema paths and bytes.
Version text alone does not establish compatibility.
The owned schema inventory and compiled schema contracts constrain the integration's supported protocol operations.

`AgentSessionBootstrap` qualifies the hosted tool catalog and its policy.
`CodexProtocolAdapter` connects that qualified catalog to thread operations and dynamic tool calls.
It keeps pending thread starts, resumes, and forks under explicit binding state.
Owned request fields and result projections pass through their schema owners.
Malformed frames, missing owned fields, rejected bindings, and invalid projections produce finite protocol failures.

## Two hosts reach the shared broker

`CliRemoteClientHost` starts one real Codex client with the broker's Unix endpoint through `--remote`.
The admitted executable, arguments, and canonical working directory remain explicit inputs.
Owned client cleanup also closes the integration.

`DesktopStdioHost` provides the JSONL facade when Codex Desktop starts `kast-codex` as its App Server.
It connects to the shared broker and owns both transport pumps.
When either pump ends, the host closes input and cancels and joins both pumps.
One lifetime owner closes the connection and integration across normal exit and JVM shutdown.
A shutdown timeout remains a rejected integration result.

## Keep frame admission separate from semantic success

Desktop input uses a bounded UTF-8 line decoder.
Empty records, malformed encoding, oversized input, and unterminated records fail admission.
The output pump rejects embedded line separators and messages beyond the byte limit.
A successful frame exchange does not establish a successful tool result or a current workspace.

Dynamic tool results preserve canonical outcome and certainty through the protocol projection.
Thread registration does not prove workspace enrollment or compiler readiness.
The focused protocol and transport tests establish framing, owned schema behavior, and cleanup paths.
A real client handshake and fresh installed semantic query need separate evidence.

## Focused tests

- [CodexProtocolQualifierTest.kt](../../app-server/src/test/kotlin/io/github/amichne/kast/appserver/protocol/codex/CodexProtocolQualifierTest.kt)
- [DesktopStdioHostTest.kt](../../app-server/src/test/kotlin/io/github/amichne/kast/appserver/host/DesktopStdioHostTest.kt)
- [DesktopStdioProtocolIntegrationTest.kt](../../app-server/src/test/kotlin/io/github/amichne/kast/appserver/host/DesktopStdioProtocolIntegrationTest.kt)

## Source owners

- [CodexProtocolQualifier.kt](../../app-server/src/main/kotlin/io/github/amichne/kast/appserver/protocol/codex/CodexProtocolQualifier.kt)
- [CodexProtocolAdapter.kt](../../app-server/src/main/kotlin/io/github/amichne/kast/appserver/protocol/codex/CodexProtocolAdapter.kt)
- [DesktopStdioHost.kt](../../app-server/src/main/kotlin/io/github/amichne/kast/appserver/host/DesktopStdioHost.kt)
- [CliRemoteClientHost.kt](../../app-server/src/main/kotlin/io/github/amichne/kast/appserver/host/CliRemoteClientHost.kt)
- [AgentSessionBootstrap.kt](../../app-server/src/main/kotlin/io/github/amichne/kast/appserver/core/AgentSessionBootstrap.kt)
- [CodexHostExecutable.kt](../../app-server/src/main/kotlin/io/github/amichne/kast/appserver/host/admission/CodexHostExecutable.kt)
- [BrokerFailureDocument.kt](../../app-server/src/main/kotlin/io/github/amichne/kast/appserver/protocol/codex/BrokerFailureDocument.kt)

## Read next

- [Coordinator ownership](coordinator-and-workspace-preparation.md)
- [Durable records](invocations-and-durable-records.md)
