---
type: API Contract
title: Operation outcomes
description: Semantic success is complete or explicitly qualified and carries either published or live evidence; rejection carries no successful payload.
resource: file://kernel/src/main/kotlin/io/github/amichne/kast/kernel/OperationOutcome.kt
tags: [outcome, evidence, failure]
timestamp: 2026-09-16T00:00:00Z
code_sources:
  - path: protocol/contract/src/main/kotlin/io/github/amichne/kast/protocol/contract/ReadRecoveryGuidance.kt
  - path: protocol/contract/src/main/kotlin/io/github/amichne/kast/protocol/contract/ReadReferenceAcquisitions.kt
  - path: protocol/contract/src/main/kotlin/io/github/amichne/kast/protocol/contract/ReadRecoveryAction.kt
  - path: kernel/src/main/kotlin/io/github/amichne/kast/kernel/OperationOutcome.kt
    symbols: [OperationOutcome]
  - path: kernel/src/main/kotlin/io/github/amichne/kast/kernel/EvidenceEnvelope.kt
    symbols: [EvidenceEnvelope, EvidenceGeneration, EvidenceBasis, LiveReadEvidence]
  - path: protocol/wire/src/main/kotlin/io/github/amichne/kast/protocol/wire/OperationWireBinding.kt
  - path: protocol/contract/src/main/kotlin/io/github/amichne/kast/protocol/contract/AdmittedReadRejections.kt
  - path: protocol/contract/src/main/kotlin/io/github/amichne/kast/protocol/contract/ExecutionBudgetPresence.kt
  - path: README.md
  - path: cli/src/main/kotlin/io/github/amichne/kast/cli/rpc/KastToolRpcMain.kt
  - path: cli/src/main/kotlin/io/github/amichne/kast/cli/mcp/McpReadPresentation.kt
  - path: cli/src/main/kotlin/io/github/amichne/kast/cli/mcp/McpSourceCompleteness.kt
---

# Operation outcomes

`OperationOutcome` is exhaustive:

- `Complete` carries an evidence envelope with no limitation.
- `Qualified` carries the same evidence plus a domain-owned qualification.
- `Rejected` carries an operation-owned closed failure and no successful payload.

The four canonical reads distinguish their existing finite rejection reason from
an admitted rejection carrying that reason and a required execution-budget report.
Wire and CLI projections preserve the reason shape and place `execution_budget`
beside it. Missing report metadata retains the unadmitted variant; explicit null
or invalid report metadata is rejected. These reports describe admission, not
successful semantic evidence.

An evidence envelope has one closed basis: published generation or detached live
IDE provenance. Live evidence retains root, host identity, epoch revision, schema
version, and saved, PSI-committed content view. It does not prove a workspace
publication or grant mutation authority. The wire boundary rejects missing or
simultaneously supplied published/live evidence.

The Kast MCP transport projects each semantic read into
`structuredContent` with a consistent `complete`, `partial`, `rejected`, or
`unavailable` status. The advertised output schema is validated before return. It keeps the entire canonical document as opaque `data`
or rejection evidence and also as the second text item. Its summary is display
text, not an authority. `coverage.exhaustive` and `basis` sit beside `data`;
diagnostic counts and source region/truncation state appear in coverage when
available. Compact and expanded source shapes are decoded before completeness
is asserted; withheld or unrecognized source output remains partial. Native
semantic incompleteness has its own stop reason rather than
being mislabeled as budget exhaustion or host interruption.

Transport success does not imply semantic completeness. A host must preserve the distinction when projecting output, and it must not attach a successful payload to rejection. The [README](../../README.md) exposes the same complete/qualified/rejected semantics to users.

The one-shot tool RPC returns a closed `complete`, `qualified`, `rejected_document`, or boundary `rejected` variant. It preserves the canonical result document under `document` for semantic outcomes, so Copilot and Pi adapters cannot turn qualified evidence into a complete result. The catalog marks `change` as `WRITE` and all direct reads as `READ`.

Canonical query, source, relation and traversal failures derive a closed
`ReadRecoveryAction`. Their CLI/tool rejected documents require `next_action`
for both unadmitted and budget-bearing rejections. The wire retains the finite
failure and budget; projection derives the action again after decoding rather
than accepting a separate action as authority.

An appended query reference rejection retains both the step and reference positions. It uses the same finite reference reason and recovery action as a source reference rejection.

| Action | Required direction |
| --- | --- |
| `reacquire_authority` | Obtain fresh authority for a stale, foreign, malformed or otherwise unusable opaque reference; do not repair its text. |
| `restart_read` | Start a new read without an unavailable continuation, under fresh admission. |
| `adjust_budget_or_scope` | Inspect the reported work or time exhaustion and effective grant; narrow the request or increase a caller limit within its ceiling. |
| `correct_request` | Correct unsupported controls or restore the original continuation-bound request; changed semantics require a new read. |
| `wait_for_workspace` | Observe workspace readiness before a later request. |
| `save_source` | Save source and allow IDE document-to-PSI synchronization before a later request. |
| `report_failure` | Report compiler, discovery or internal contract failure when the reason proves no recovery prerequisite. |

These directions grant no automatic retry, source write, workspace opening,
import, or refresh capability. Qualified progress keeps its separate continuation
actions; rejection recovery does not assert successful or complete output.

Relation and traversal qualifications retain accumulated facts with explicit
incomplete coverage. Their `recovery` list names the exhausted field and offers
budget increases only when the reported effective limit is below the operator
ceiling without a clamp. Otherwise it recommends reducing scope. Resumable
qualifications direct the caller to the supplied continuation. Depth, frontier,
indexing, omissions and provider failures have distinct guidance. None of this
promises that a larger budget will establish completeness.

Hosted hard deadline exhaustion cannot publish an unvalidated result. It returns
`reduce_read_work` with the available admission budget; cancellation is separately
identified and does not claim a timeout. A semantic provider that stops within
its grant can return accumulated facts as qualified evidence.
