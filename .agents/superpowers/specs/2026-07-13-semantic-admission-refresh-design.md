# Semantic Admission Refresh Design

Date: 2026-07-13

Issue: [#335](https://github.com/amichne/kast/issues/335)

Status: Approved for autonomous implementation

## Objective

Make a successful focused refresh prove that every existing Kotlin file in the
request is immediately analyzable. Refresh must distinguish filesystem/VFS
discovery, source-module ownership, IDEA index admission, and Kotlin analysis
availability instead of acknowledging a path before those facts are true.

The defining regression is a new or moved Kotlin file for which
`raw/workspace-refresh` returns the absolute path but the immediately following
`raw/diagnostics` returns issue #332's `PENDING_INDEX` or `MISSING_ON_DISK`
incomplete evidence.

## Requirements

1. Every focused refresh path has one ordered admission ledger entry with four
   separately typed stage states.
2. Existing Kotlin paths enter `refreshedFiles` only after all stages succeed.
3. Removed paths enter `removedFiles` and require no semantic admission.
4. Pending VFS, index, or PSI state is retried at 25 millisecond intervals for
   at most 1.5 seconds and never beyond the backend request budget.
5. The result reports attempt count and elapsed milliseconds even when the
   deadline expires.
6. Persistent existing-file failure carries issue #332's
   `FileAnalysisStatus` and `SemanticAnalysisOutcome.INCOMPLETE` evidence.
7. A complete result followed immediately by diagnostics returns analyzed
   evidence for every admitted file. Ordinary compiler errors remain admitted.
8. New production files, new test files, moved files, deleted files, and
   Kast-created files have integration coverage.
9. A focused refresh of an already admitted file stays below one second.
10. Full refresh remains an explicit VFS invalidation and does not make an
    unbounded per-file admission claim.

## Considered Approaches

### Delay in the Rust diagnostics command

The CLI could sleep between refresh and diagnostics. This hides backend state,
cannot cover Kast mutations inside the server, and still cannot distinguish
module ownership from index or PSI admission. It is rejected.

### Retry diagnostics after an incomplete result

The diagnostics endpoint could retry `PENDING_INDEX`. This improves one caller
but leaves refresh free to make a false success claim and duplicates waiting in
every semantic operation. It is rejected as the primary contract.

### Typed refresh admission barrier

The selected approach strengthens `RefreshResult` with a per-file ledger and
makes the IDEA backend await pending admission. The server routes Kast
mutations through that barrier before import optimization and diagnostics.
This centralizes host-specific waiting, preserves issue #332's fail-closed
evidence, and gives clean files a one-probe fast path.

## Contract Model

`analysis-api` adds four enums:

- `FileSystemDiscoveryState`: `DISCOVERED`, `PENDING`, or `REMOVED`;
- `SourceModuleOwnershipState`: `OWNED`, `OUTSIDE_SOURCE_MODULES`, or
  `NOT_APPLICABLE`;
- `IndexAdmissionState`: `ADMITTED`, `PENDING`, or `NOT_APPLICABLE`;
- `AnalysisAvailabilityState`: `AVAILABLE`, `PENDING`, `FAILED`, or
  `NOT_APPLICABLE`.

`SemanticAdmissionStatus` is invariant-checked and has three factory paths:

- admitted: all four success states plus `FileAnalysisStatus.ANALYZED`;
- removed: `REMOVED` plus later stages `NOT_APPLICABLE`, without a semantic
  analysis status;
- incomplete: the observed stages plus a non-analyzed issue #332
  `FileAnalysisStatus`.

`RefreshResult.focused` derives `refreshedFiles`, `removedFiles`,
`SemanticAnalysisOutcome`, issue #332's requested/analyzed/skipped counts for
the existing admission candidates, a separate removed count, and validates the
reported bounded-wait progress. Removed paths are excluded from semantic
analysis counts because their confirmed absence is the requested refresh
result. `RefreshResult.full` represents the existing unbounded full workspace
invalidation with an empty per-file ledger.

The serialized change is additive and remains on the current schema version.
Protocol Markdown, OpenAPI, examples, and the internal command catalog are
regenerated from the Kotlin owners.

## IDEA Admission Flow

`IdeaSemanticAdmissionAwaiter` owns retry timing and is independent of IDEA
state. It probes all paths once, returns immediately when every status is
terminal, and re-probes only `PENDING` statuses until the deadline. A testable
clock and pause function make retry and deadline behavior deterministic without
production test hooks.

`KastPluginBackend` owns the probe:

1. `Files.notExists` produces a removed terminal state.
2. `LocalFileSystem.refreshAndFindFileByNioFile` proves VFS discovery; a miss
   while the disk file exists is pending discovery.
3. `ProjectFileIndex.isInSourceContent` proves source-module ownership.
4. smart mode plus membership in the Kotlin `FileTypeIndex` proves index
   admission.
5. `PsiManager.findFile` producing `KtFile`, followed by a successful Kotlin
   analysis session, proves analysis availability.

Process cancellation remains cancellation. Unexpected analysis exceptions
produce `BACKEND_FAILURE` evidence and are terminal. Diagnostics reuses the
same classification order so a complete refresh and immediate diagnostics
cannot disagree about what success means.

## Mutation Flow

After create, text edit, or rename application, `SkillRpcOrchestrator` calls
focused refresh for affected existing paths before import optimization or
diagnostics. If refresh is incomplete, later diagnostics preserve the same
typed incomplete evidence and the mutation remains not clean. A created file
cannot reach optimization or a successful response without crossing semantic
admission.

Delete remains a terminal removal. A later diagnostics request for that old
path is correctly incomplete with `MISSING_ON_DISK`.

The Rust semantic-evidence boundary validates both `raw/workspace-refresh` and
`raw/diagnostics`. Incomplete refresh stops the typed diagnostics workflow
before it can issue a contradictory analysis request, promotes the #332
summary to command output, and exits non-zero. The new serialized fields are
required; no compatibility default may turn absent admission evidence into
success.

## Testing

Tests prove contract invariants, retry progress and deadline exhaustion,
production/test source-root admission, move old/new classification, deletion,
immediate post-refresh diagnostics, sub-second clean refresh, and server
mutation ordering. Existing fake backends return complete admission evidence
so downstream tests remain deterministic.

Focused validation covers `analysis-api`, `analysis-server`, and
`backend-idea`. Final validation runs the full Gradle suite, generated-contract
checks, docs contracts, and diff hygiene.

## Non-Goals

- changing runtime readiness or daemon lifecycle;
- waiting for a Gradle sync to turn an arbitrary directory into a source root;
- adding a public retry-policy flag;
- treating ordinary compiler diagnostics as admission failures;
- redefining the persistent SQLite source index as the IDEA semantic-admission
  gate.
