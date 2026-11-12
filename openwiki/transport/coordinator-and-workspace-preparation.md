---
type: System Explanation
title: Coordinator and workspace preparation
description: Traces installed coordinator admission, selected-IDE preparation, retained
  operation identity, workspace lanes, and safe presemantic recovery.
tags:
- kast
- transport
code_sources:
- path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/InstalledCoordinator.kt
- path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/runtime/WorkspacePreparations.kt
- path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/runtime/WorkspaceDemand.kt
- path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/runtime/WorkspaceExecution.kt
- path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/runtime/WorkspaceExecutionPolicy.kt
- path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/InstalledWorkspacePreparation.kt
- path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/runtime/DaemonWorkspacePreparation.kt
- path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/AppServerManagement.kt
- path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/core/AgentSessionBootstrap.kt
- path: distribution/managed/src/main/kotlin/io/github/amichne/kast/distribution/managed/CanonicalRoot.kt
- path: distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/ManagedWorkspaceQuery.kt
- path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/provider/BrokerProcess.kt
- path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/runtime/BrokerControlRoute.kt
- path: app-server/src/test/kotlin/io/github/amichne/kast/appserver/runtime/WorkspacePreparationsTest.kt
- path: app-server/src/test/kotlin/io/github/amichne/kast/appserver/runtime/WorkspacePreparationWaitRecoveryTest.kt
- path: app-server/src/test/kotlin/io/github/amichne/kast/appserver/runtime/WorkspaceExecutionBudgetTest.kt
sources:
  - id: openwiki-source-775e1d6befa87f41b21c6f0b
    resource: repo://app-server/src/main/kotlin/io/github/amichne/kast/appserver/runtime/WorkspaceDemand.kt
  - id: openwiki-source-d5cbfb02164ed016b4a98505
    resource: repo://app-server/src/main/kotlin/io/github/amichne/kast/appserver/runtime/WorkspaceExecution.kt
  - id: openwiki-source-8ae73fa39bc2bae22080593c
    resource: repo://app-server/src/main/kotlin/io/github/amichne/kast/appserver/runtime/WorkspaceExecutionPolicy.kt
  - id: openwiki-source-63714f19f4c87d1962b654ac
    resource: repo://app-server/src/main/kotlin/io/github/amichne/kast/appserver/runtime/WorkspacePreparations.kt
  - id: openwiki-source-eee64eb8ca3aa3550601ed99
    resource: repo://app-server/src/main/kotlin/io/github/amichne/kast/appserver/WorkspaceOperationAdmission.kt
  - id: openwiki-source-ecb21af09f4a49f9e40342dd
    resource: repo://distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/ManagedWorkspaceQuery.kt
  - id: openwiki-source-8f099dc46f272100d18b0f6f
    resource: repo://distribution/managed/src/main/kotlin/io/github/amichne/kast/distribution/managed/CanonicalRoot.kt
generated: { by: "amp", at: "2026-11-12T21:31:26.144Z" }
verified:
  - by: openwiki/0.9.0
    at: 2026-11-12T22:50:57.382Z
---

# Coordinator and workspace preparation

## Managed request admission precedes preparation

`distribution/managed` owns `CanonicalRoot` and nearest Gradle-settings root discovery.
Discovery resolves the physical directory and stops at a nested checkout boundary rather than borrowing another repository's settings.
CLI, MCP, and tool RPC use `ManagedWorkspaceQuery` to select the installed product and private workspace generation before semantic execution.
The first explicit preparation request grants trust only to that exact root. Retained-reference access requires an existing record.
The request owns the query lease and private HOME; MCP initialization and session-start context remain passive.
Saved installation and workspace configuration are admitted by the shared managed configuration owner.
Queries do not acquire release assets or discover desktop IDEA installations.

## Admit one installed coordinator

`InstalledCoordinatorConfiguration` admits the executable, installation lifecycle fence, user home, configuration, private directories, readiness authority, and public endpoint.
The admitted options preserve this process's readiness owner when later host admission occurs.
A discovered socket path alone does not establish that owner.

`InstalledWorkspacePreparation` composes one selected-IDE lifecycle client for management and semantic demand.
Its enrollment store and native host observations supply separate evidence for hosted service status.
The coordinator cannot treat an observed IDEA process as an enrolled exact repository.

## Preparation owns native effects once

`WorkspacePreparations` keeps one current preparation per canonical root and bounded historical operation records.
Repeated preparation joins a current operation.
Observation reads its state without another native effect.
Semantic demand can create a new incarnation after a terminal rejection or blocked outcome.
The native owner admits that authorized retry.

Eviction selects terminal historical records without changing an already held terminal result.
Invalidation retires only the matching ready incarnation.
An unknown historical identifier proves neither prior issuance nor an original outcome.
Management preparation accepts the exact supplied canonical root and rejects an implicit containing workspace.

`WorkspaceOperationAdmission` selects preparation for fresh name, text, or location discovery and diagnostics.
Issued source and retained-reference operations use existing authority without another preparation effect.
They still need current Host and reference admission.
`PreparedWorkspaceDemand` waits for terminal preparation and inspects the current host and exact target before dispatch of an operation that needs preparation.
A changed host permits one fresh preparation because no semantic operation reached the former target.
Model-reload recovery also needs an explicit presemantic recovery result and an eligible operation.
Ambiguous semantic effects cannot become an automatic retry.

## One semantic permit per workspace

`WorkspaceExecution` owns idle, busy, and recovery-required lanes.
Each lane has one active semantic request and a bounded waiting set.
Its policy keeps queue wait inside one aggregate interaction allowance.
Queue admission does not renew the execution budget.
Passive observation uses its explicit access path.

Capacity, deadline, cancellation, and uncertain settlement keep distinct outcomes and payload-free stage evidence.
A lane that needs recovery rejects later semantic work until its recovery owner settles the protected state.
Focused coroutine tests exercise ownership, budget use, and settlement transitions.
They do not prove a fresh installed IDEA model or external process readiness.

## Focused tests

- [WorkspacePreparationsTest.kt](../../app-server/src/test/kotlin/io/github/amichne/kast/appserver/runtime/WorkspacePreparationsTest.kt)
- [WorkspacePreparationWaitRecoveryTest.kt](../../app-server/src/test/kotlin/io/github/amichne/kast/appserver/runtime/WorkspacePreparationWaitRecoveryTest.kt)
- [WorkspaceExecutionBudgetTest.kt](../../app-server/src/test/kotlin/io/github/amichne/kast/appserver/runtime/WorkspaceExecutionBudgetTest.kt)

## Source owners

- [InstalledCoordinator.kt](../../app-server/src/main/kotlin/io/github/amichne/kast/appserver/InstalledCoordinator.kt)
- [WorkspacePreparations.kt](../../app-server/src/main/kotlin/io/github/amichne/kast/appserver/runtime/WorkspacePreparations.kt)
- [WorkspaceDemand.kt](../../app-server/src/main/kotlin/io/github/amichne/kast/appserver/runtime/WorkspaceDemand.kt)
- [WorkspaceExecution.kt](../../app-server/src/main/kotlin/io/github/amichne/kast/appserver/runtime/WorkspaceExecution.kt)
- [WorkspaceExecutionPolicy.kt](../../app-server/src/main/kotlin/io/github/amichne/kast/appserver/runtime/WorkspaceExecutionPolicy.kt)
- [InstalledWorkspacePreparation.kt](../../app-server/src/main/kotlin/io/github/amichne/kast/appserver/InstalledWorkspacePreparation.kt)
- [DaemonWorkspacePreparation.kt](../../app-server/src/main/kotlin/io/github/amichne/kast/appserver/runtime/DaemonWorkspacePreparation.kt)
- [AppServerManagement.kt](../../app-server/src/main/kotlin/io/github/amichne/kast/appserver/AppServerManagement.kt)
- [AgentSessionBootstrap.kt](../../app-server/src/main/kotlin/io/github/amichne/kast/appserver/core/AgentSessionBootstrap.kt)
- [CanonicalRoot.kt](../../distribution/managed/src/main/kotlin/io/github/amichne/kast/distribution/managed/CanonicalRoot.kt)
- [ManagedWorkspaceQuery.kt](../../distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/ManagedWorkspaceQuery.kt)
- [BrokerProcess.kt](../../app-server/src/main/kotlin/io/github/amichne/kast/appserver/provider/BrokerProcess.kt)
- [BrokerControlRoute.kt](../../app-server/src/main/kotlin/io/github/amichne/kast/appserver/runtime/BrokerControlRoute.kt)

## Read next

- [IDEA lifecycle](../runtime/idea-lifecycle-and-refresh.md)
- [Invocation history](invocations-and-durable-records.md)
- [Management](../distribution/management-and-connections.md)
