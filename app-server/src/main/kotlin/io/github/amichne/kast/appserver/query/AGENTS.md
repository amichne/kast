---
type: Engineering Guide
title: "Public query engineering rules"
description: "Defines schema ownership, typed query admission, strict completion, and distinct retained-output capabilities."
resource: "repo://app-server/src/main/kotlin/io/github/amichne/kast/appserver/query"
tags: [kast, navigation]
timestamp: "2026-10-22T05:29:46Z"
source_revision: ebc805014ada9e3eb1b85ef611e41bd176af8d80
code_sources:
  - path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/query/PublicToolContract.kt
  - path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/query/PublicToolMapping.kt
  - path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/query/PublicToolTrace.kt
  - path: app-server/src/main/resources/io/github/amichne/kast/appserver/query/tools.schema.json
  - path: app-server/src/test/kotlin/io/github/amichne/kast/appserver/query/PublicToolCompletionPolicyTest.kt
  - path: app-server/src/test/kotlin/io/github/amichne/kast/appserver/query/PublicToolTraceContractTest.kt
  - path: app-server/src/test/kotlin/io/github/amichne/kast/appserver/query/PublicRunnerControlledExecutionTest.kt
  - path: packaging/generate-public-query.py
  - path: protocol/contract/src/main/kotlin/io/github/amichne/kast/protocol/contract/QueryCompletionDocument.kt
---

# Public query interface

Follow the root Engineering Dictum and `openwiki/contracts/public-tools.md`.
The authored schema owns parameter metadata and defaults; generated files are not
editable authorities. Run `:app-server:verifyPublicQueryGeneration` after schema
changes, and focused PublicTool contract/schema tests after admission changes.

A public request is intent, not compiler evidence. Admit the sole public query
grammar in `tools.schema.json` into `AdmittedPublicTool` through its private
construction boundary. Never reintroduce a raw JSON query payload, candidate
output, an INSPECT stage, magic wildcard, or implicit retry. The closed query
request admits exactly one of four actions:

- `RUN` accepts a source and optional ordered steps.
- `RESUME` accepts a typed retained-output continuation and an optional presentation budget.
- `READ_RESULT` accepts a retained-result reference and a distinct presentation cursor.
- `READ_SOURCE` accepts an issued candidate reference for its exact source anchor.

Source reads reuse the canonical source owner and preserve its authority and freshness failures.
Keep candidate references, exact-symbol
references, result references, execution continuations, and result cursors
separate in the admitted syntax. The public `RESUME` schema admits only the
`query-output:v1` token family. Internal `query:v1` continuations, malformed tokens, and
retained-result tokens reject before canonical lowering. `RESUME` only delivers already-produced output.
It cannot continue semantic execution or change its grant. Schema admission does not establish issued authority.

`RUN.executionMode` defaults to `BOUNDED`.
`RUNNER_CONTROLLED` uses caller cancellation to stop semantic execution.
Its request accepts output grants and rejects `maxElapsedMs` or `maxWorkUnits`.
Keep finite output pages separate from semantic execution.

The intent tools in `tools.schema.json` allow default controls to be omitted;
normalize omission or explicit null once through the generated defaults before canonical construction. Admit
those requests through `AdmittedPublicTool`, retaining tool identity and schema
identity in addition to the canonical operation. Invalid and empty are not synonyms for omitted. Preserve ordered stages and exact token bytes. Keep all workspace,
generation, semantic identity and completeness checks in their existing owners.
The `WHERE` step lowers its typed visibility or primitive predicate into the canonical predicate.
The full public schema bounds primitive literals and rejects unknown fields and operators.
`CONCAT` reuses the exact-reference and retained-result source DTOs. Set stages accept
only a retained-result right operand. Issued row IDs select retained rows without
reconstructing weaker exact references or treating a subset as complete coverage.
The `WALK` stage admits exhaustive traversal or an explicit depth and strategy.
It projects traversal records, frontier progress, partial expansions, and coverage through the sole query operation.
`JOIN` accepts a retained symbol result as the right operand.
An inner join yields typed binding rows with both exact symbols and their independent evidence.
`PROJECT_BINDING` selects one named cell before later symbol stages.
Retained binding results can seed another run, including selected issued row IDs.
Semi and anti joins continue as symbol streams. An anti join admits only a complete right operand.

Encoding is a transport projection of retained typed syntax, not a reverse parser
for arbitrary canonical requests. A public constructor or copy must not bypass admission.
Schema constraints and canonical syntax guards remain enforced even when a provider
supports a weaker generation schema. Passing a JSON Schema is not a capability.

## Completion and trace

Every public `RUN` uses `COMPLETE_ONLY` under `COMPILER_RESOLVED_STATIC_V1`.
Completion needs exhausted execution, no item failures, and no omitted evidence.
Callback graphs and original impact investigations keep their own proof obligations.
Unproved completion preserves typed rejection and recovery evidence.

`TRACE` has a fixed topology for references, implementations or overrides, direct callers, and one further caller layer.
It accepts an expansion scope, without arbitrary depth controls.

## Navigation

- [tools.schema.json](../../../../../../../resources/io/github/amichne/kast/appserver/query/tools.schema.json)
- [PublicToolContract.kt](PublicToolContract.kt)
- [PublicToolMapping.kt](PublicToolMapping.kt)
- [PublicToolTrace.kt](PublicToolTrace.kt)
- [PublicToolCompletionPolicyTest.kt](../../../../../../../../test/kotlin/io/github/amichne/kast/appserver/query/PublicToolCompletionPolicyTest.kt)
- [PublicToolTraceContractTest.kt](../../../../../../../../test/kotlin/io/github/amichne/kast/appserver/query/PublicToolTraceContractTest.kt)
- [generate-public-query.py](../../../../../../../../../../packaging/generate-public-query.py)
- [QueryCompletionDocument.kt](../../../../../../../../../../protocol/contract/src/main/kotlin/io/github/amichne/kast/protocol/contract/QueryCompletionDocument.kt)
