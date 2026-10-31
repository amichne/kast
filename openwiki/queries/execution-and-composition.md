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
  - id: openwiki-source-22c063698f200893b9da9c83
    resource: repo://query/service/src/main/kotlin/io/github/amichne/kast/query/service/QueryJoins.kt
  - id: openwiki-source-4b8532fbc1b8ccbdd0176d20
    resource: repo://query/service/src/main/kotlin/io/github/amichne/kast/query/service/QueryJoinStage.kt
  - id: openwiki-source-0968f2c7eeb2df4c3eb5a5c3
    resource: repo://query/service/src/main/kotlin/io/github/amichne/kast/query/service/QueryService.kt
generated: { by: "codex", at: "2026-10-15T00:57:31.735Z" }
verified:
  - by: openwiki/0.6.1
    at: 2026-10-30T23:25:02.193Z
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

`QueryService` runs the admitted plan through an ordered deque of typed tasks.
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

## Focused tests

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
