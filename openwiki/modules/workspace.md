---
type: Kotlin Module Group
title: Workspace
description: Workspace contracts distinguish published leases from original-owner live IDE reads and preserve their separate admission effects. Hosted read preparation refreshes imported inputs through bounded recursive and flat VFS sessions before document preparation, while explicit file refresh force-marks only the workspace root.
resource: file://workspace
tags: [kotlin, workspace, lifecycle, intellij]
code_sources:
  - path: workspace/intellij-read/src/main/kotlin/io/github/amichne/kast/workspace/intellij/read/ProjectReadEpochDiagnostics.kt
    symbols: [ProjectReadEpochDiagnostics, ProjectReadEpochSignal]
  - path: workspace/intellij-read/src/main/kotlin/io/github/amichne/kast/workspace/intellij/read/hosted/HostedReadFreshnessOwner.kt
  - path: runtime/hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/HostedEpochStore.kt
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
sources:
  - id: openwiki-source-9713af85ff6a629a6ce68bf3
    resource: repo://runtime/hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/lifecycle/IdeLifecycleNative.kt
  - id: openwiki-source-30ec45e5d5abc40f250cc287
    resource: repo://runtime/hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/lifecycle/IdeLifecycleProjectOpen.kt
  - id: openwiki-source-51ae3f05868141e4abf0b0ef
    resource: repo://runtime/hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/workspace/IntellijWorkspaceRefreshPort.kt
  - id: openwiki-source-0272a3ddd46fa2ebe2918453
    resource: repo://runtime/hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/workspace/IntellijWorkspaceRefreshScope.kt
  - id: openwiki-source-64600940b0005f8522858e57
    resource: repo://runtime/hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/workspace/WorkspaceRefreshScope.kt
  - id: openwiki-source-90acef2c033e1847cabc529e
    resource: repo://runtime/hosted/src/nativeTest/kotlin/io/github/amichne/kast/runtime/hosted/NativeProjectRefreshTest.kt
  - id: openwiki-source-33148f7629d4743e447a438a
    resource: repo://runtime/hosted/src/test/kotlin/io/github/amichne/kast/runtime/hosted/workspace/WorkspaceRefreshScopeTest.kt
  - id: openwiki-source-903d531c5eecaec62a353b32
    resource: repo://workspace/intellij-read/src/main/kotlin/io/github/amichne/kast/workspace/intellij/read/epoch/ProjectReadEpochObservation.kt
  - id: openwiki-source-d7a75676401a9fcbe00c8e6f
    resource: repo://workspace/intellij-read/src/main/kotlin/io/github/amichne/kast/workspace/intellij/read/epoch/ProjectReadEpochVfsListener.kt
  - id: openwiki-source-26adf1e57264a6d43e0f22c1
    resource: repo://workspace/intellij-read/src/main/kotlin/io/github/amichne/kast/workspace/intellij/read/hosted/HostedReadFreshnessOwner.kt
generated: { by: "amp", at: "2026-11-14T06:28:38.719Z" }
verified:
  - by: openwiki/0.9.0
    at: 2026-11-14T06:28:38.719Z
---

# Workspace

The active workspace modules are `workspace:contract` and `workspace:intellij-read`. The production plugin consumes the original open IDEA project. It captures the existing imported model, source-root ownership, saved content and one read epoch without running Gradle import or opening another project.

`SemanticReadAuthority` distinguishes historical published leases from `LiveSemanticReadAuthority`. Live authority retains an opaque IDE epoch and the original host identity. Its detached reference can be restored only by that owner against newly admitted freshness. Retirement is terminal; a moved epoch never revives an earlier reference. IDE counters do not become published generations.

`HostedQueryService` creates and ends a request-scoped context, validates authority before and after evaluation, and drains each request before releasing its independently owned permit. Multiple reads can overlap within `HOST_CONNECTIONS`. Before epoch admission, the host can wait when IDEA preempts a sample or the sample moves. After model capture, the host acquires native read access, makes sure that the expected epoch remains valid, and admits its authority in one synchronized transition.

Canonical dispatch uses a single evaluation: epoch movement rejects the query rather than silently replacing its scope. A fresh request can admit the next epoch. Older requests cannot clear newer continuation or reference storage, and retirement cannot resurrect storage.

A live read epoch is an optimistic consistency boundary, not a frozen copy of files or an MVCC snapshot. Its signals include PSI modification, root-filtered VFS batches, project/workspace-model and root-model changes, indexing cycles, project/Gradle root identity and import timestamps. Saved documents and committed PSI remain admission requirements; disposal or unavailable/indexing state rejects observation. A change can invalidate queries in the workspace even when their selected files were untouched. Epoch revisions are owner-local admission identities, not a count of edits.

`ProjectReadEpochDiagnostics` observes that same production sample boundary and
emits `kast_project_read_epoch` records with the host identity and finite changed
signal labels. It retains the last successful detached sample across rejected
observations, suppresses identical repeats, and distinguishes baseline, movement,
rejection and unchanged recovery. No path, source text, raw counter or import
timestamp crosses the log boundary. Diagnostic state cannot issue or renew an
epoch; ordinary admission remains the authority.

Successful publication proves that the observed epoch remained equal through final freshness validation. It does not freeze the workspace after that point: a later edit can invalidate delivered references and continuations. Query stages compose within one request's authority, model and scope. After epoch movement, rerun the original query.

Existing explicit reference reacquisition can locate a current declaration, but it does not revive an old continuation or promise an old snapshot. Epoch movement returns the existing `restart_read` recovery guidance and keeps any admitted execution budget. No epoch migration or background replay is introduced.

Published lease and lifecycle types remain available for historical protocol contracts and tests. Their former `workspace:service` transition coordinator and `workspace:intellij` importer are retired and absent from the build. The retained topology publisher accepts complete generations but is not wired into the production plugin. See [historical publication](../flows/workspace-publication.md) and [existing-IDE reads](../flows/hosted-query.md).

Before evaluation, HostedReadDeadline derives a positive HostedSemanticTimeAllowance from remaining host time with a completion reserve. The context carries that allowance into query and diagnostic-scope budgets. An exhausted allowance rejects before the evaluator starts; final freshness validation and cancellation drainage remain required.

Before semantic admission, `HostedReadPublicationAdmission` verifies that typed containment failures carrying the candidate execution report fit the hard hosted-frame cap. Capacity rejection retains the candidate only in diagnostic evidence and publishes no admitted report. Once admitted, the executor retains the actual report through timeout, cancellation and final freshness failures; this does not grant permission to evaluate after the request deadline.

The VFS listener retains prior ownership for moves, renames and deletions, then checks every affected endpoint after the batch. A bounded batch preserves the invalidation signal only when every endpoint is irrelevant. An endpoint outside the admitted root is irrelevant unless it contains that root. An in-root endpoint is irrelevant only for a regular file proven outside imported source and library roots. Changes to the root, its ancestors or imported files advance the signal. Unknown ownership and oversized batches invalidate conservatively. Oversized batches advance without traversing events; they do not poison a healthy observer or confer fresh read authority. Subsequent reads still require ordinary model, saved-content, PSI, smart-mode and final freshness admission. Malformed bounded paths and exhausted counters remain terminal failures; no reset revives earlier evidence.

## Refresh before imported-input reads

Automatic read preparation refreshes imported inputs within the canonical workspace root. It derives scope from declared source, test and resource roots, local library roots, linked Gradle module directories and Gradle settings inputs. Only the absence of any declared in-workspace source root selects recursive whole-workspace refresh. A declared but missing source root still selects imported scope.

Imported scope uses two kinds of refresh roots:

- Recursive roots cover existing declared source, test and resource trees and in-workspace library roots. Descendants already covered by an ancestor are removed.
- Flat roots refresh one directory level: the workspace root, linked Gradle module directories, parents of Gradle settings inputs, and nearest VFS-existing ancestors of missing declared sources. Flat roots already covered recursively are removed.

The adapter launches each non-empty session and waits for all callbacks. A later EDT turn prepares project documents before read preparation completes. Explicit `FILE_REFRESH` instead force-marks the single canonical workspace root dirty and refreshes it recursively, independently of watcher notifications.

`WorkspaceRefreshScopeTest` checks root selection and fallback. `WorkspaceRefreshVfsOrderTest` checks session and document-preparation ordering. The native `NativeProjectRefreshTest` case checks source edits, new source files, Gradle input edits and newly created declared roots through the real refresh queue. It also checks that an unowned tree is skipped until an explicit whole-root refresh.

The hosted workspace lifecycle boundary retains file refresh and Gradle model reload for the authorized open, trusted project and its already linked root. The public agent lifecycle request no longer selects either effect. Native effects run asynchronously; status reports completion only after the effect callback and fresh existing admission. Pending status is bounded independently of semantic budgets. Exact request IDs are idempotent, equivalent work coalesces, newer requests queue, and disposal is terminal. Project-owned unsaved documents are saved on the EDT before an effect; a failed save rejects. Semantic dispatch and lifecycle opening save project-owned documents and wait for read-preparation refresh sessions and document preparation to complete. Explicit file refresh retains forced dirty marking for external writes. Watcher events advance invalidation but do not establish complete disk reconciliation.

The internal per-host task rule maps one exact IDE-observed successful single-task Gradle invocation to the same lifecycle service. New hosts default off; imports, unsuccessful tasks, other roots and other projects cannot trigger it. Gradle callbacks schedule the request without waiting for readiness. Existing epoch listeners remain the semantic freshness authority.

Application lifecycle opens one canonical root through the native project opener.
Desktop mode requests a new frame.
Managed headless mode admits private project storage before it opens a project without a frame.
Project reuse does not change window focus.
The owner examines admission, then waits for a native import already in progress.
If the cached model stays missing or incomplete, it requests one linked Gradle model reload.

New managed projects use the existing refresh/readiness owner. The owner applies import data in the background for the first link operation and later reloads. It keeps tool-window activation and error navigation disabled. A project-scoped initial-import operation suppresses native automatic imports during this sequence and restores its prior state afterward. Trust and global unsaved-document checks remain blockers. Background presentation is best effort.

Project use is recorded by coordinator thread identity. Presentation protects a managed project before activating its frame. Release only removes the caller's interest. Close fences new Kast work, rejects other users and in-flight native work, saves project-owned documents on the EDT, and invokes normal veto-respecting close/dispose. A veto restores admission. Success requires project disposal and retirement of that project's endpoint; its result survives at application scope. Borrowed/presented closure additionally requires a verified exact-target controller assertion. Reopening the root creates a distinct project incarnation.
