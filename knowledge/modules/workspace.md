---
type: Kotlin Module Group
title: Workspace
description: Workspace contracts distinguish published leases from original-owner live IDE reads and preserve their separate admission effects.
resource: file://workspace
tags: [kotlin, workspace, lifecycle, intellij]
timestamp: 2026-09-16T00:00:00Z
code_sources:
  - path: runtime/hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/lifecycle/IdeLifecycleNative.kt
  - path: runtime/hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/lifecycle/IdeLifecycleReadiness.kt
  - path: runtime/hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/HostedProjectDocuments.kt
  - path: runtime/hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/HostedVfsRefreshOutcome.kt
  - path: runtime/hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/workspace/WorkspaceRefreshService.kt
  - path: runtime/hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/workspace/IntellijWorkspaceRefreshPort.kt
  - path: runtime/hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/workspace/WorkspaceRefreshTaskTrigger.kt
  - path: protocol/contract/src/main/kotlin/io/github/amichne/kast/protocol/contract/WorkspaceLifecycleRequest.kt
  - path: protocol/contract/src/main/kotlin/io/github/amichne/kast/protocol/contract/WorkspaceRefreshDocuments.kt
  - path: workspace/intellij-read/src/main/kotlin/io/github/amichne/kast/workspace/intellij/read/epoch/ProjectReadEpochObservation.kt
  - path: workspace/intellij-read/src/main/kotlin/io/github/amichne/kast/workspace/intellij/read/epoch/ProjectReadEpochVfsListener.kt
  - path: workspace/contract/src/main/kotlin/io/github/amichne/kast/workspace/contract/WorkspaceTransitionState.kt
    symbols: [WorkspaceLifecycle, WorkspaceTransitionSnapshot]
  - path: workspace/contract/src/main/kotlin/io/github/amichne/kast/workspace/contract/epoch/SemanticReadLease.kt
    symbols: [SemanticReadLease, SemanticReadLeaseGuard]
  - path: workspace/contract/src/main/kotlin/io/github/amichne/kast/workspace/contract/epoch/SemanticReadAuthority.kt
    symbols: [SemanticReadAuthority, SemanticReadIdentity, LiveSemanticReadAuthority, LiveSemanticReadOwner, SemanticReadValidationPort]
  - path: workspace/intellij-read/src/main/kotlin/io/github/amichne/kast/workspace/intellij/read/DetachedModelCapture.kt
    symbols: [captureDetachedModelObserved]
  - path: workspace/intellij-read/src/main/kotlin/io/github/amichne/kast/workspace/intellij/read/hosted/HostedReadPublicationAdmission.kt
  - path: workspace/intellij-read/src/main/kotlin/io/github/amichne/kast/workspace/intellij/read/hosted/HostedQueryExecutor.kt
  - path: workspace/intellij-read/src/main/kotlin/io/github/amichne/kast/workspace/intellij/read/hosted/HostedQueryService.kt
  - path: workspace/intellij-read/src/main/kotlin/io/github/amichne/kast/workspace/intellij/read/hosted/HostedEpochAdmission.kt
  - path: workspace/intellij-read/src/main/kotlin/io/github/amichne/kast/workspace/intellij/read/hosted/HostedReadReplayPolicy.kt
---

# Workspace

The active workspace modules are `workspace:contract` and `workspace:intellij-read`. The production plugin consumes the original open IDEA project. It captures the existing imported model, source-root ownership, saved content and one read epoch without running Gradle import or opening another project.

`SemanticReadAuthority` distinguishes historical published leases from `LiveSemanticReadAuthority`. Live authority retains an opaque IDE epoch and the original host identity. Its detached reference can be restored only by that owner against newly admitted freshness. Retirement is terminal; a moved epoch never revives an earlier reference. IDE counters do not become published generations.

`HostedQueryService` creates and ends a request-scoped context, validates authority before and after evaluation, and drains work before releasing its permit. Before model capture it waits within the host deadline when IDEA preempts an epoch sample or the sampled epoch moves during freshness admission. Canonical semantic reads may discard and repeat an evaluation if final freshness detects a moved VFS epoch; change workflows retain one evaluation. Both loops share the original host deadline and report bounded diagnostic counters. Other epoch failures remain exact rejections. Semantic objects stay inside the admitted read lifetime. Saved documents, committed PSI and source ownership remain explicit obligations.

Published lease and lifecycle types remain available for historical protocol contracts and tests. Their former `workspace:service` transition coordinator and `workspace:intellij` importer are retired and absent from the build. The retained topology publisher accepts complete generations but is not wired into the production plugin. See [historical publication](../flows/workspace-publication.md) and [existing-IDE reads](../flows/hosted-query.md).

Before evaluation, HostedReadDeadline derives a positive HostedSemanticTimeAllowance from remaining host time with a completion reserve. The context carries that allowance into query and diagnostic-scope budgets. An exhausted allowance rejects before the evaluator starts; final freshness validation and cancellation drainage remain required.

Before semantic admission, `HostedReadPublicationAdmission` verifies that typed containment failures carrying the candidate execution report fit the hard hosted-frame cap. Capacity rejection retains the candidate only in diagnostic evidence and publishes no admitted report. Once admitted, the executor retains the actual report through timeout, cancellation and final freshness failures; this does not grant permission to evaluate after the request deadline.

A bounded VFS batch proven outside the root preserves the invalidation signal. A root-touching batch advances it. An oversized batch has unknown relevance and advances conservatively without traversing events; it does not poison a healthy observer or confer fresh read authority. Subsequent reads still require ordinary model, saved-content, PSI, smart-mode and final freshness admission. Malformed bounded paths and exhausted counters remain terminal failures; no reset revives earlier evidence.

The hosted workspace lifecycle boundary retains file refresh and Gradle model reload for the authorized open, trusted project and its already linked root. The public agent lifecycle request no longer selects either effect. Native effects run asynchronously; status reports completion only after the effect callback and fresh existing admission. Pending status is bounded independently of semantic budgets. Exact request IDs are idempotent, equivalent work coalesces, newer requests queue, and disposal is terminal. Project-owned unsaved documents are saved on the EDT before an effect; a failed save rejects. Semantic dispatch and lifecycle opening save project-owned documents and wait for native incremental recursive VFS refresh. Explicit file refresh retains forced dirty marking for external writes. Watcher events advance invalidation but do not establish complete disk reconciliation.

The internal per-host task rule maps one exact IDE-observed successful single-task Gradle invocation to the same lifecycle service. New hosts default off; imports, unsuccessful tasks, other roots and other projects cannot trigger it. Gradle callbacks schedule the request without waiting for readiness. Existing epoch listeners remain the semantic freshness authority.

Explicit application lifecycle opening uses ordinary new-frame project opening for one canonical root. Reuse does not activate the window. It observes admission, waits for a native import already in progress, and requests one linked Gradle model reload if the cached model is still missing or incomplete. New managed projects use the existing refresh/readiness owner: first linking and subsequent reloads share background import-data application with tool-window activation and error navigation disabled. A project-scoped initial-import operation suppresses native automatic imports during this sequence and restores its prior state afterward. Trust and global unsaved-document checks remain blockers. Background presentation is best effort.

Project use is recorded by coordinator thread identity. Presentation protects a managed project before activating its frame. Release only removes the caller's interest. Close fences new Kast work, rejects other users and in-flight native work, saves project-owned documents on the EDT, and invokes normal veto-respecting close/dispose. A veto restores admission. Success requires project disposal and retirement of that project's endpoint; its result survives at application scope. Borrowed/presented closure additionally requires a verified exact-target controller assertion. Reopening the root creates a distinct project incarnation.
