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
  - id: openwiki-source-594b73dd64e36b813f7ed5b2
    resource: repo://runtime/hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/lifecycle/IdeLifecycleState.kt
  - id: openwiki-source-91a4a9e4b4052c946d881372
    resource: repo://runtime/hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/workspace/HostedWorkspaceRefresh.kt
  - id: openwiki-source-9d7191bc271f115a5c0315b0
    resource: repo://runtime/hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/workspace/WorkspaceRefreshImportCompletion.kt
  - id: openwiki-source-c3d765f6a0f5ed9757d16a3a
    resource: repo://runtime/hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/workspace/WorkspaceRefreshService.kt
generated: { by: "codex", at: "2026-10-15T00:33:05.932Z" }
verified:
  - by: openwiki/0.6.1
    at: 2026-10-30T23:25:02.193Z
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
