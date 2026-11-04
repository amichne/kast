---
type: System Explanation
title: IDEA lifecycle and explicit refresh
description: Explains project ownership, joined open requests, explicit refresh effects,
  task-success evidence, and passive read preparation.
tags:
- kast
- runtime
code_sources:
- path: runtime/hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/lifecycle/IdeLifecycleState.kt
- path: runtime/hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/lifecycle/HostedProjectAdmission.kt
- path: runtime/hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/workspace/HostedWorkspaceRefresh.kt
- path: runtime/hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/workspace/WorkspaceRefreshService.kt
- path: runtime/hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/workspace/WorkspaceRefreshTaskTrigger.kt
- path: runtime/hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/workspace/WorkspaceRefreshImportCompletion.kt
- path: runtime/hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/workspace/WorkspaceRefreshModelBoundary.kt
- path: runtime/hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/lifecycle/AutomaticReadinessAction.kt
- path: runtime/hosted/src/test/kotlin/io/github/amichne/kast/runtime/hosted/HostedVfsRefreshTest.kt
- path: runtime/hosted/src/test/kotlin/io/github/amichne/kast/runtime/hosted/HostedGradleRevisionTest.kt
sources:
  - id: openwiki-source-88f09847a1cc4b4d806ffe37
    resource: repo://app-server/src/main/kotlin/io/github/amichne/kast/appserver/InstalledWorkspacePreparation.kt
  - id: openwiki-source-9c073d03ec5fb55608850dd9
    resource: repo://app-server/src/main/kotlin/io/github/amichne/kast/appserver/runtime/NativePreparationAwaiter.kt
  - id: openwiki-source-d5cbfb02164ed016b4a98505
    resource: repo://app-server/src/main/kotlin/io/github/amichne/kast/appserver/runtime/WorkspaceExecution.kt
  - id: openwiki-source-8ae73fa39bc2bae22080593c
    resource: repo://app-server/src/main/kotlin/io/github/amichne/kast/appserver/runtime/WorkspaceExecutionPolicy.kt
  - id: openwiki-source-63714f19f4c87d1962b654ac
    resource: repo://app-server/src/main/kotlin/io/github/amichne/kast/appserver/runtime/WorkspacePreparations.kt
  - id: openwiki-source-d49b78c58a23da108be9efe5
    resource: repo://distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/ManagementShutdown.kt
  - id: openwiki-source-681368e4942d2fb35f298a68
    resource: repo://distribution/contract/src/main/kotlin/io/github/amichne/kast/distribution/contract/configuration/KastConfigurationCatalogue.kt
  - id: openwiki-source-beadba0f0b5f11f102a7ab98
    resource: repo://protocol/registry/src/main/kotlin/io/github/amichne/kast/protocol/registry/OperationExecutionBudget.kt
  - id: openwiki-source-b711ac75af08966d13a95b22
    resource: repo://runtime/hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/HostedEndpointService.kt
  - id: openwiki-source-9713af85ff6a629a6ce68bf3
    resource: repo://runtime/hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/lifecycle/IdeLifecycleNative.kt
  - id: openwiki-source-594b73dd64e36b813f7ed5b2
    resource: repo://runtime/hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/lifecycle/IdeLifecycleState.kt
  - id: openwiki-source-91a4a9e4b4052c946d881372
    resource: repo://runtime/hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/workspace/HostedWorkspaceRefresh.kt
  - id: openwiki-source-9d7191bc271f115a5c0315b0
    resource: repo://runtime/hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/workspace/WorkspaceRefreshImportCompletion.kt
  - id: openwiki-source-c3d765f6a0f5ed9757d16a3a
    resource: repo://runtime/hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/workspace/WorkspaceRefreshService.kt
generated: { by: "codex", at: "2026-11-04T23:27:44.986Z" }
verified:
  - by: openwiki/0.6.1
    at: 2026-11-04T23:27:44.986Z
---

# IDEA lifecycle and explicit refresh

## Application state and project state differ

`IdeLifecycleState` keeps application-level project identities and bounded operation records.
Each project record keeps its exact root, host identity, ownership, and client users.
Repeated request identity selects the existing operation only when its command agrees.
A conflicting command, wrong host, stale project, shutdown, or capacity limit remains a finite rejection.

Joined open operations preserve each caller's own request identity.
Project inspection and native effects occur outside the bookkeeping monitor.
Native project ownership distinguishes borrowed, managed, and presented projects.
A cleanup action cannot treat a user-owned project as an automatically owned resource.

## Explicit refresh owns import effects

`HostedWorkspaceRefresh` connects the project to one `WorkspaceRefreshService` and native refresh port.
It tracks imported model inputs and configured Gradle task triggers.
The command model distinguishes configuration, a requested effect, and status lookup for an existing request.
Request identifiers receive bounded admission before they enter the service.

The service owns queued, effect, and final admission stages.
Native admission or examination of an existing import occurs when the effect reaches its turn.
A Gradle task callback does not establish a successful refreshed model by itself.
The completion and model owners join the exact task result, request, and current model evidence.
Failed tasks, unsaved documents, unavailable roots, disposed projects, and deadlines remain distinct outcomes.

## Passive reads keep a smaller effect scope

Read preparation can request the existing file-comparison refresh path.
It does not gain permission to start an arbitrary model import.
The hosted wrapper maps the terminal refresh result into the read owner's finite freshness causes.
Pending refresh state never becomes a terminal ready result.

Automatic readiness actions use the existing admitted preparation policy.
They do not replace current workspace enrollment with a cached status document.
Project disposal retires the project-owned endpoint and its retained state.
Later callers need a new owner and fresh admission.

The focused state tests establish command conflicts, joined identities, refresh sequencing, and finite failures.
Only the native fixture can establish an actual IDEA project or Gradle import transition.

## Public lifecycle responsibilities

Optional MCP registration, client sessions, workspace preparation, semantic requests, and the coordinator daemon have distinct owners.
Registration proves an owned adapter or resource. It does not prove a usable workspace.
Client protocol admission permits catalog discovery and tool calls for that session.
Workspace readiness permits semantic admission without proving each declaration identity.
A live socket does not prove compatible contracts.
A transport reply does not prove semantic completion.

Public input admission passes through PublicToolContract before the selected IDEA project and current read state supply semantic authority.
HostedCanonicalQuery connects that authority to CanonicalQueryProtocol.
The protocol owns internal semantic continuation under one grant.
Public RESUME and READ_RESULT only present existing evidence.
Scope, source state, question, and output remain fixed throughout a run.
A failure cannot silently restart discovery with another question.

Hosted App Server keeps one semantic permit and a bounded queue per workspace lane.
Queue wait consumes the interaction allowance. Saturation or expired queue wait rejects before execution.
Cancellation before execution records that phase.
After an uncertain write, the lane blocks further source effects until settlement.
An uncertain read does not create that write fence.
These hosted lane rules do not impose identical concurrency on every transport.

MCP starts a stdio session and exposes tools through tools/list.
Tool RPC supplies its installed catalog before a one-shot call and can admit a bounded `querySession`.
Hosted dynamic calls also need client protocol, thread ownership, and workspace binding.
Disconnect removes the recorded client connection without automatically retiring the shared daemon or IDEA project.
Upgrades need new catalogs and the selected Host loaded before semantic admission.

WorkspacePreparations owns one sequence per canonical root.
Callers can share pending or completed preparation.
One open effect precedes status reads for its issued operation.
Preparation shares one configured allowance across open, import, VFS refresh, indexing, and final admission.
The default is 17 minutes. `KAST_WORKSPACE_PREPARATION_WAIT` accepts positive milliseconds or `indefinite`.
An indefinite wait has no elapsed-time deadline; cancellation, actual failure, and editor closure still end it.
Lifecycle VFS refresh and its nested waiter receive the remaining allowance.
They cannot replace it with a shorter fixed timeout.
If an earlier shared native wait expires, `NativePreparationAwaiter` checks the same Host, root, and project.
It then uses a fresh native request ID while keeping its original preparation deadline.
Changed identity or a real lifecycle failure ends that wait.
Lifecycle frames keep separate 5-second transport limits.
EXCHANGE_DEADLINE_EXCEEDED reports command, stage, cause, and timing without proving that IDEA exited.
Semantic execution has separate limits after preparation.

A settled import can still need MODEL_RELOAD_REQUIRED recovery.
Preparation can reload once within its allowance.
Later source-input changes remain pending until an import covers them.
Past blockers do not permanently establish current readiness.
Operation history has finite capacity. Reclaimed operation IDs return UNKNOWN_OPERATION.
Trust, unsaved files, incompatible plugins, failed imports, and indexing can prevent readiness.

Daemon startup admits the installed executable, selected runtime, settings, and identity under its shared installation lock.
Readiness records and a socket exchange identify a service generation.
An old record or occupied socket alone cannot establish that generation.
Stop, reset, and replacement fences reject automatic startup when their state is unknown.
`kast stop` fences new calls and automatic restart before retirement.
A daemon restart never replays writes. A Host restart ends its live authority.

The [public lifecycle reference](../../docs/public/reference/lifecycles.mdx) keeps its owner table, request diagram, and operational lookup links.

## Focused tests

- [HostedVfsRefreshTest.kt](../../runtime/hosted/src/test/kotlin/io/github/amichne/kast/runtime/hosted/HostedVfsRefreshTest.kt)
- [HostedGradleRevisionTest.kt](../../runtime/hosted/src/test/kotlin/io/github/amichne/kast/runtime/hosted/HostedGradleRevisionTest.kt)

## Source owners

- [IdeLifecycleState.kt](../../runtime/hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/lifecycle/IdeLifecycleState.kt)
- [HostedProjectAdmission.kt](../../runtime/hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/lifecycle/HostedProjectAdmission.kt)
- [HostedWorkspaceRefresh.kt](../../runtime/hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/workspace/HostedWorkspaceRefresh.kt)
- [WorkspaceRefreshService.kt](../../runtime/hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/workspace/WorkspaceRefreshService.kt)
- [WorkspaceRefreshTaskTrigger.kt](../../runtime/hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/workspace/WorkspaceRefreshTaskTrigger.kt)
- [WorkspaceRefreshImportCompletion.kt](../../runtime/hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/workspace/WorkspaceRefreshImportCompletion.kt)
- [WorkspaceRefreshModelBoundary.kt](../../runtime/hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/workspace/WorkspaceRefreshModelBoundary.kt)
- [AutomaticReadinessAction.kt](../../runtime/hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/lifecycle/AutomaticReadinessAction.kt)

## Read next

- [Imported model freshness](../semantics/workspace/model-and-freshness.md)
- [Coordinator preparation](../transport/coordinator-and-workspace-preparation.md)
- [Native qualification](../development/testing-and-native-qualification.md)
