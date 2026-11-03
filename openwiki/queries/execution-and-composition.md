---
type: System Explanation
title: Query stages and composition
description: Explains typed row transitions, the single stage interpreter, retained
  composition, join evidence, and bounded execution.
tags:
- kast
- queries
code_sources:
- path: query/contract/src/main/kotlin/io/github/amichne/kast/query/contract/QueryPlan.kt
- path: query/contract/src/main/kotlin/io/github/amichne/kast/query/contract/QueryRowAdmission.kt
- path: query/contract/src/main/kotlin/io/github/amichne/kast/query/contract/QueryRows.kt
- path: query/service/src/main/kotlin/io/github/amichne/kast/query/service/QueryService.kt
- path: query/service/src/main/kotlin/io/github/amichne/kast/query/service/PipelineCheckpoint.kt
- path: query/service/src/main/kotlin/io/github/amichne/kast/query/service/QueryJoins.kt
- path: query/service/src/main/kotlin/io/github/amichne/kast/query/service/QueryJoinStage.kt
- path: query/contract/src/main/kotlin/io/github/amichne/kast/query/contract/QueryCheckpointPartialSymbols.kt
- path: query/service/src/test/kotlin/io/github/amichne/kast/query/service/QueryJoinCompositionTest.kt
- path: query/protocol/src/test/kotlin/io/github/amichne/kast/query/protocol/QueryRetainedRowAdmissionTest.kt
- path: query/service/src/test/kotlin/io/github/amichne/kast/query/service/QueryRetainedCompositionTest.kt
sources:
  - id: openwiki-source-b99ee1925605f819ed25dd38
    resource: repo://query/contract/src/main/kotlin/io/github/amichne/kast/query/contract/QueryRowAdmission.kt
  - id: openwiki-source-8bba742d3ecbc32c906815e6
    resource: repo://query/service/src/main/kotlin/io/github/amichne/kast/query/service/PipelineCheckpoint.kt
  - id: openwiki-source-2055be6c2f31ab5f9c9cda31
    resource: repo://query/service/src/main/kotlin/io/github/amichne/kast/query/service/QueryImpactTasks.kt
  - id: openwiki-source-22c063698f200893b9da9c83
    resource: repo://query/service/src/main/kotlin/io/github/amichne/kast/query/service/QueryJoins.kt
  - id: openwiki-source-4b8532fbc1b8ccbdd0176d20
    resource: repo://query/service/src/main/kotlin/io/github/amichne/kast/query/service/QueryJoinStage.kt
  - id: openwiki-source-0968f2c7eeb2df4c3eb5a5c3
    resource: repo://query/service/src/main/kotlin/io/github/amichne/kast/query/service/QueryService.kt
  - id: openwiki-source-884fbecd15a59fc68fc423de
    resource: repo://query/service/src/main/kotlin/io/github/amichne/kast/query/service/QueryTraceTasks.kt
  - id: openwiki-source-7fb52b2012eb836868d8299c
    resource: repo://relation/intellij/src/main/kotlin/io/github/amichne/kast/relation/intellij/IntellijReferenceInventoryReuse.kt
  - id: openwiki-source-d681a34840e8a5d442cc3f25
    resource: repo://symbol/intellij/src/main/kotlin/io/github/amichne/kast/symbol/intellij/IntellijExactNameCandidateFiles.kt
generated: { by: "codex", at: "2026-11-03T05:45:59.282Z" }
verified:
  - by: openwiki/0.6.1
    at: 2026-11-03T05:45:59.282Z
---

# Query stages and composition

## Admission determines legal row transitions

A query source establishes its initial row kind before semantic effects.
Declaration, text, location, and exact-reference sources produce symbol rows.
Retained sources preserve their admitted symbol, occurrence, binding, or value-path kind.
`IMPACT` produces value paths.
`QueryRowAdmission` rejects a step or output that does not accept the established kind.

An inner join produces binding rows with named left and right cells.
`PROJECT_BINDING` selects an admitted cell before later symbol stages.
Unknown names and symbol-only stages applied directly to bindings reject.
Occurrence and value-path streams do not silently become symbol streams.

## One interpreter owns execution

`QueryService` runs the admitted plan through an ordered double-ended queue of typed tasks.
Discovery, exact lookup, source reads, relations, traversal, joins, and impact work use their existing domain operations.
The interpreter owns task order, emitted rows, work and byte accounting, qualifications, and continuation construction.
`PipelineCheckpoint` keeps the detached pending work and existing stage history.
It does not keep PSI objects or create a second execution owner.

Failures, omissions, relation observations, and traversal observations travel through explicit tasks.
Their output costs remain visible under the same grant.
A result limit cannot discard inconvenient failure evidence and claim complete coverage.
Deadline, work, byte, and checkpoint limits keep their finite qualifications or rejection.

## Retained set and join inputs

Set stages and joins restore their right input from an admitted retained result.
Exact symbol identity determines equality.
Display names and token text do not substitute for that identity.
Selected issued row identifiers keep their original owning result and qualification.

Difference and anti join need a complete right input before effects.
An incomplete right input cannot prove absence.
Semi and anti joins keep symbol output. Inner joins keep both cells and their independent evidence.
Join indexing and probing remain tasks inside the existing interpreter and checkpoint model.

`TRACE` must be the last step with symbol output.
Its specialized fan-out still uses this task owner and the original grant.
Public automatic accumulation and final completion belong to the canonical protocol around the interpreter.

The focused tests distinguish row admission, complete-right rejection, and resumed composition.
They establish interpreter behavior with explicit domain operations, without installed-host semantic proof.

## Query work reuse

Reuse preserves the original scope, identity, and evidence rather than replacing their authority.
A file inventory does not prove a relationship. Shared reads must keep each route's proof.

For exact names, a complete native word-index read can produce a candidate-file exclusion proof.
The bounded VFS inventory still examines excluded children.
Ordinary noncandidate files avoid declaration work. Candidate files still need compiler refinement.
Invalid files and files too large for code intelligence keep their original admission and coverage.
Names without a sound index route use exhaustive discovery, including spaced backtick names and implicit Companion discovery.
A failed or stopped index read produces no exclusion object.
File, work, and retained-byte limits remain independent.

TRACE groups matching callable arrivals before one downstream CALLERS read.
Each group keeps every incoming reference, caller, and implementation occurrence.
Exact capabilities must match before expansion is shared.
The topology still stops after its fixed caller layer. WALK selects another depth.
Type-member discovery never proves a call from the type to its member.

One invocation can keep a prepared native reference inventory for REFERENCES and CALLERS.
Its key contains current authority, subject, scope, and evidence purpose.
Eligible inventories start at cursor zero and fit the retained-byte allowance.
The next eligible inventory can replace the slot.
The receiving collector still needs enough capacity and must establish its relation meaning.
A changed key returns a miss. Insufficient capacity returns unavailable.
The slot is neither a project-wide semantic cache nor a continuation store.

IMPACT can share a settled one-hop read at the same exact value site.
The interpreter expands its transfers separately for each producer and ordered route history.
Finite rejection evidence stays a rejection rather than an empty successful transfer set.
An unfinished read continues under the existing grant. Its prefix does not prove settlement.
The original ledger keeps branches, terminals, models, and unresolved obligations.
Completion needs that whole ledger even when a preview selects one path.

The [public reuse explanation](../../docs/public/architecture/query-reuse.mdx) keeps the four diagrams and their reader-specific presentation.
Its source links at `722ae03fc1` describe that recorded revision and do not establish a new native run.

## Focused tests

- [ExactNameCandidateFilesTest.kt](../../symbol/intellij/src/test/kotlin/io/github/amichne/kast/symbol/intellij/ExactNameCandidateFilesTest.kt)
- [ReferenceInventoryReuseTest.kt](../../relation/intellij/src/test/kotlin/io/github/amichne/kast/relation/intellij/ReferenceInventoryReuseTest.kt)

- [QueryJoinCompositionTest.kt](../../query/service/src/test/kotlin/io/github/amichne/kast/query/service/QueryJoinCompositionTest.kt)
- [QueryRetainedRowAdmissionTest.kt](../../query/protocol/src/test/kotlin/io/github/amichne/kast/query/protocol/QueryRetainedRowAdmissionTest.kt)
- [QueryRetainedCompositionTest.kt](../../query/service/src/test/kotlin/io/github/amichne/kast/query/service/QueryRetainedCompositionTest.kt)

## Source owners

- [QueryPlan.kt](../../query/contract/src/main/kotlin/io/github/amichne/kast/query/contract/QueryPlan.kt)
- [QueryRowAdmission.kt](../../query/contract/src/main/kotlin/io/github/amichne/kast/query/contract/QueryRowAdmission.kt)
- [QueryRows.kt](../../query/contract/src/main/kotlin/io/github/amichne/kast/query/contract/QueryRows.kt)
- [QueryService.kt](../../query/service/src/main/kotlin/io/github/amichne/kast/query/service/QueryService.kt)
- [PipelineCheckpoint.kt](../../query/service/src/main/kotlin/io/github/amichne/kast/query/service/PipelineCheckpoint.kt)
- [QueryJoins.kt](../../query/service/src/main/kotlin/io/github/amichne/kast/query/service/QueryJoins.kt)
- [QueryJoinStage.kt](../../query/service/src/main/kotlin/io/github/amichne/kast/query/service/QueryJoinStage.kt)
- [QueryCheckpointPartialSymbols.kt](../../query/contract/src/main/kotlin/io/github/amichne/kast/query/contract/QueryCheckpointPartialSymbols.kt)

## Read next

- [Public admission](public-admission.md)
- [Strict completion](trace-and-completion.md)
- [Retained output](retention-and-presentation.md)
