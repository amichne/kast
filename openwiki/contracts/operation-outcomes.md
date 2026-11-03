---
type: API Contract
title: Operation outcomes
description: Semantic success is complete or explicitly qualified and carries either published or live evidence; rejection carries no successful payload.
resource: file://kernel/src/main/kotlin/io/github/amichne/kast/kernel/OperationOutcome.kt
tags: [outcome, evidence, failure]
code_sources:
  - path: protocol/contract/src/main/kotlin/io/github/amichne/kast/protocol/contract/ReadReferenceAcquisitions.kt
  - path: protocol/contract/src/main/kotlin/io/github/amichne/kast/protocol/contract/ReadRecoveryAction.kt
  - path: protocol/wire/src/main/kotlin/io/github/amichne/kast/protocol/wire/presentation/QueryRejectionCliDocument.kt
  - path: protocol/contract/src/main/kotlin/io/github/amichne/kast/protocol/contract/CanonicalQueryOperationModels.kt
  - path: protocol/contract/src/main/kotlin/io/github/amichne/kast/protocol/contract/QueryResultDocuments.kt
  - path: protocol/contract/src/main/kotlin/io/github/amichne/kast/protocol/contract/QueryWalkDocuments.kt
  - path: kernel/src/main/kotlin/io/github/amichne/kast/kernel/OperationOutcome.kt
    symbols: [OperationOutcome]
  - path: kernel/src/main/kotlin/io/github/amichne/kast/kernel/EvidenceEnvelope.kt
    symbols: [EvidenceEnvelope, EvidenceGeneration, EvidenceBasis, LiveReadEvidence]
  - path: protocol/wire/src/main/kotlin/io/github/amichne/kast/protocol/wire/OperationWireBinding.kt
  - path: protocol/contract/src/main/kotlin/io/github/amichne/kast/protocol/contract/AdmittedReadRejections.kt
  - path: protocol/contract/src/main/kotlin/io/github/amichne/kast/protocol/contract/ExecutionBudgetPresence.kt
  - path: query/contract/src/main/kotlin/io/github/amichne/kast/query/contract/QueryImpactLedgerModelConservation.kt
  - path: README.md
  - path: cli/src/main/kotlin/io/github/amichne/kast/cli/rpc/KastToolRpcMain.kt
  - path: cli/src/main/kotlin/io/github/amichne/kast/cli/mcp/McpStructuredResults.kt
sources:
  - id: openwiki-source-c58b3f38ca46d44dd30a8771
    resource: repo://cli/src/main/kotlin/io/github/amichne/kast/cli/direct/InstalledToolAdmission.kt
  - id: openwiki-source-32409ff9180fb1de21a3dea3
    resource: repo://cli/src/main/kotlin/io/github/amichne/kast/cli/mcp/KastMcpMain.kt
  - id: openwiki-source-03a0b1a7c53feb2ca69eaad8
    resource: repo://cli/src/main/kotlin/io/github/amichne/kast/cli/mcp/McpStructuredResults.kt
  - id: openwiki-source-1d161f3eb7933044519fd33a
    resource: repo://cli/src/main/kotlin/io/github/amichne/kast/cli/rpc/KastToolRpcMain.kt
  - id: openwiki-source-d69d2f9483e3ef07d3d7af62
    resource: repo://kernel/src/main/kotlin/io/github/amichne/kast/kernel/EvidenceEnvelope.kt
  - id: openwiki-source-1368a6d1e2827cd0501cec50
    resource: repo://kernel/src/main/kotlin/io/github/amichne/kast/kernel/OperationOutcome.kt
  - id: openwiki-source-185b35d3438d124da26fc35f
    resource: repo://protocol/contract/src/main/kotlin/io/github/amichne/kast/protocol/contract/ImpactPathClassificationDocument.kt
  - id: openwiki-source-01b72c0031ed69c6870649b2
    resource: repo://protocol/contract/src/main/kotlin/io/github/amichne/kast/protocol/contract/QueryCompletionDocument.kt
  - id: openwiki-source-a69c24d60edd91bff2c95c93
    resource: repo://protocol/contract/src/main/kotlin/io/github/amichne/kast/protocol/contract/QueryInvocationDocument.kt
  - id: openwiki-source-ddbecea6a66f516818cd6c13
    resource: repo://protocol/contract/src/main/kotlin/io/github/amichne/kast/protocol/contract/ReadRecoveryAction.kt
  - id: openwiki-source-298cd684b4ea067ededc36fe
    resource: repo://protocol/wire/src/main/kotlin/io/github/amichne/kast/protocol/wire/presentation/QueryCompletionRejectionCliDocument.kt
  - id: openwiki-source-f25255e9bb017d304eee3d17
    resource: repo://protocol/wire/src/main/kotlin/io/github/amichne/kast/protocol/wire/presentation/QueryRelationGraphCliDocument.kt
  - id: openwiki-source-9bbe0b51f46e3181b77fd01f
    resource: repo://protocol/wire/src/main/kotlin/io/github/amichne/kast/protocol/wire/presentation/QueryResultCliDocuments.kt
  - id: openwiki-source-c73506fa551638aea61f2ad6
    resource: repo://query/contract/src/main/kotlin/io/github/amichne/kast/query/contract/QueryImpactClassification.kt
  - id: openwiki-source-9fcd90db4b894268639790f3
    resource: repo://query/contract/src/main/kotlin/io/github/amichne/kast/query/contract/QueryImpactLedger.kt
  - id: openwiki-source-4be8493dd702830e9781ac13
    resource: repo://query/contract/src/main/kotlin/io/github/amichne/kast/query/contract/QueryImpactLedgerModelConservation.kt
  - id: openwiki-source-02b2e37271b094bf49925843
    resource: repo://query/contract/src/main/kotlin/io/github/amichne/kast/query/contract/QueryImpactLedgerValidation.kt
  - id: openwiki-source-3267e638d001b779dc0bb288
    resource: repo://query/contract/src/main/kotlin/io/github/amichne/kast/query/contract/QueryImpactPath.kt
  - id: openwiki-source-8abcd99add0585ea126a3883
    resource: repo://query/contract/src/main/kotlin/io/github/amichne/kast/query/contract/QueryImpactSiteAccounting.kt
  - id: openwiki-source-f59335e3d2864a9877a4b4c2
    resource: repo://query/protocol/src/main/kotlin/io/github/amichne/kast/query/protocol/QueryCallbackCompletion.kt
  - id: openwiki-source-c02d6c9ee1cca469a96beea5
    resource: repo://relation/intellij/src/main/kotlin/io/github/amichne/kast/relation/intellij/CallbackFlowRetention.kt
  - id: openwiki-source-4c00982fad30a76a898b74f1
    resource: repo://relation/intellij/src/main/kotlin/io/github/amichne/kast/relation/intellij/NativeStringContent.kt
  - id: openwiki-source-6b9226c3dcd93a43cae28064
    resource: repo://relation/intellij/src/main/kotlin/io/github/amichne/kast/relation/intellij/NativeStringConversion.kt
generated: { by: "codex", at: "2026-11-03T06:27:58.730Z" }
verified:
  - by: openwiki/0.6.1
    at: 2026-11-03T06:27:58.730Z
---

# Operation outcomes

## Public result interpretation

The public response separates semantic completion, producer progress, and output delivery.
`kast.query.run.v9` identifies the RUN response contract.
Each public RUN uses `COMPLETE_ONLY` within `COMPILER_RESOLVED_STATIC_V1`.
Complete coverage describes the declared supported domain.
Semantic completion also needs the model's proof obligations.
Qualified output without rows does not prove absence.

Source-symbol TRACE uses `SOURCE_RELATIONSHIPS` with `callbackEffects: EVIDENCE_ONLY`.
Source filters and deduplication preserve that interpretation before TRACE.
Explicit relation operations, traversal, composition, retained sources, and impact sources keep stricter callback proof.
Compiler mapping failures, stale dependency evidence, and exhausted grants still prevent completion.
Static source relationships never prove runtime execution.

`COMPLETION_UNPROVEN` keeps its specific cause and original coverage.
Successful retention adds a result reference, preview, original question, and public `nextQuery` arguments.
The canonical question has a different grammar from those public arguments.
`policyProgress: EVIDENCE_ONLY` and `POLICY_REJECTED_EVIDENCE` preserve rejection through later output reads.
An optional `seedQuery` starts another investigation from proved preview identities.
It does not cover omitted rows or unfinished scope.
Failed retention reports the storage cause without a result reference.

## Rows, evidence, and handles

`invocation.accumulated_row_count` counts logical rows across admitted pages.
`INLINE` includes all produced rows. `PREFIX` includes an ordered portion within the row and encoded-byte limits.
The preview records its row count and the UTF-8 bytes of its encoded items array.
An output preview never changes producer coverage.

Row and evidence cursors advance independently.
An evidence-only page can advance `evidence_window` without changing its row cursor.
Evidence order is failures, omissions, walk, reference, discovery, then relation observations.
`MORE` keeps the next evidence position. `FINAL` marks the stored end.
After the last row, evidence can advance while the row cursor stays at the accumulated count.

Public `RESUME` accepts an issued `query-output:v1` token and only shows produced output.
`READ_RESULT` shows immutable retained rows and evidence.
Neither operation visits new files or restarts discovery.
`retention_unavailable` records loss of the remaining suffix.
A missing continuation never proves completion.

Exact symbol references select fresh `SYMBOL_REFS` queries with current compiler admission.
Retained-result references identify original results. Row IDs belong to their owning result.
An anonymous `candidateSelector` supplies `READ_SOURCE.candidateRef`.
Source reads check the issued snapshot and do not reacquire exact-reference authority.
Owner retirement, a changed epoch, expiry, and eviction can block retained output.

## Value-path relationships

Full paths and compact findings keep classification separate from evidence authority.
ESTABLISHED with EXACT means that each admitted step preserves the tracked value.
ESTABLISHED with DERIVED keeps established content construction.
NOT_ESTABLISHED identifies the first step without proved correspondence and its cause.
A later exact step cannot restore missing proof or erase earlier derivation.
Compiler, reviewed-library, and supplied model evidence remain distinct.
A modeled representation or modeled interface alone produces NOT_ESTABLISHED.

Kotlin string templates consume the established result of each embedded expression.
A nullable String contributes content only under its non-null condition.
Its null alternative constructs literal null without source-content contribution.
A null-only result keeps an explicit constant-construction record.
Repeated template entries keep distinct occurrences even when they reach the same requested site.
A constant getter or conversion can discard its input while its own String result contributes content.
Implicit object conversion selects the applicable `kotlin.Any.toString` member or override.
An unsupported conversion keeps its exact entry and selected-member evidence.

## Compact output and transport envelopes

Callback proof records consume their admitted result grant.
Their conservative byte estimates describe storage without a separate byte quota or encoded-output allowance.
A result-limit rejection keeps its admitted prefix and unresolved proof.
The `kast_semantic_read` receipt keeps diagnostic estimates and counters separately from serialized response size.
These estimates do not measure heap use or establish semantic completion.

Compact output is the default. `verbose: true` adds detail without stronger semantic evidence.
Compact row node IDs join `relation_graph.nodes`.
Connection IDs join `connection_witnesses`. Reference-edge witness IDs join `witnesses`.
These IDs belong to the graph's retained basis and cannot select live declarations.
Each node keeps its exact selector. `relation_graph.detail` supplies a detailed retained read.
Table joins do not advance row or evidence cursors.
`knownMinimum` counts proved logical output, including sent rows, rather than candidates or remaining pages.

Direct MCP returns the operation document in `result.structuredContent` and text.
Tool RPC and completed hosted calls carry `document`.
A distinct MCP rejection sets `result.isError: true` and reports `type: MCP_REJECTED`, `status: rejected`, and `error`.
`INVOCATION_IN_PROGRESS` rejects overlapping tool calls on that connection.
It does not report a semantic rejection or establish that the active query failed.
`health_check` retains its separate readiness shape.

The [public results reference](../../docs/public/reference/responses.mdx) keeps reader-specific tables, examples, and lookup links.
Its five-row preview example is an illustration, without a captured execution claim.

## Finite query rejections

Text discovery uses existing exact-symbol query outcomes. Composition rejects
more than 1000 distinct lexical match exemplars for one owner with the finite
`TEXT_MATCH_LIMIT_EXCEEDED` execution reason. Wire and installed projections
keep that reason and its `ADJUST_BUDGET_OR_SCOPE` recovery action; they do not
turn it into an unknown or internal failure.

`OperationOutcome` is exhaustive:

- `Complete` carries an evidence envelope with no limitation.
- `Qualified` carries the same evidence plus a domain-owned qualification.
- `Rejected` carries an operation-owned closed failure and no successful payload.

Query, source, and diagnostic reads distinguish their finite rejection reason from
an admitted rejection carrying that reason and a required execution-budget report.
Wire and CLI projections preserve the reason shape and place `execution_budget`
beside it. Missing report metadata retains the variant without an admitted grant; explicit null
or invalid report metadata is rejected. These reports describe admission, not
successful semantic evidence.

## Evidence qualification

An evidence envelope has one closed basis: published generation or detached live
IDE provenance. Live evidence retains root, host identity, epoch revision, schema
version, and saved, PSI-committed content view. It does not prove a workspace
publication or grant mutation authority. The wire boundary rejects missing or
simultaneously supplied published/live evidence.

## Transport projection

The Kast MCP transport validates the operation-owned semantic document and
returns it unchanged in `structuredContent` and text. Its `complete`,
`qualified`, or `rejected` status, coverage, references, finite failures, and
recovery evidence keep their native meaning. A boundary invocation failure
uses the MCP error flag separately and does not invent a semantic result.

Transport success does not imply semantic completeness. A host must preserve the distinction when projecting output, and it must not attach a successful payload to rejection. The public [result guide](../../docs/public/reference/responses.mdx) gives the user-facing interpretation.

The one-shot tool RPC returns a closed `complete`, `qualified`, `rejected_document`, or boundary `rejected` variant. It preserves the canonical result document under `document` for semantic outcomes, so Copilot and Pi adapters cannot turn qualified evidence into a complete result. The catalog marks `add_declaration` as `WRITE` and all direct reads as `READ`. A shutdown fence or remaining replacement journal returns `INSTALLATION_STOPPED` before workspace discovery, preparation, or semantic dispatch. An empty journal or dangling journal link also blocks admission.

## Recovery directions

Canonical query and source failures derive a closed
`ReadRecoveryAction`. Their CLI/tool rejected documents require `next_action`
for rejections with and without an admitted grant. The wire retains the finite
failure and budget; projection derives the action again after decoding rather
than accepting a separate action as authority.

A `concat` exact-reference rejection retains both the step and reference positions. It uses the same finite reference reason and recovery action as a source reference rejection. An unavailable retained row or incomplete right operand is a request correction, while an unavailable or stale retained result directs a fresh read.

Query admission has distinct finite reasons for unknown projected binding names and mismatched output kinds. A retained binding-row result presented as symbols rejects rather than returning an empty symbol stream. An incomplete anti-join right input rejects before execution because absence is unproven.

| Action | Required direction |
| --- | --- |
| `reacquire_authority` | Get fresh authority for a stale, foreign, malformed or otherwise unusable opaque reference; do not repair its text. |
| `restart_read` | Start a new read without an unavailable continuation, under fresh admission. |
| `adjust_budget_or_scope` | Inspect the reported work or time exhaustion and effective grant; narrow the request or increase a caller limit within its ceiling. |
| `correct_request` | Correct unsupported controls or restore the original continuation-bound request; changed semantics require a new read. |
| `wait_for_checkpoint` | Wait for the active owner of this checkpoint before retrying; no competing successor is published. |
| `wait_for_workspace` | Observe workspace readiness before a later request. |
| `save_source` | Save source and allow IDE document-to-PSI synchronization before a later request. |
| `report_failure` | Report compiler, discovery or internal contract failure when the reason proves no recovery prerequisite. |

These directions grant no automatic retry, source write, workspace opening,
import, or refresh capability. Qualified progress keeps its separate continuation
actions; rejection recovery does not assert successful or complete output.

## Qualified execution

Qualified query results keep discovery and reference observations, detailed relation omissions, and walk observations alongside
known rows. Positive compiler-confirmed occurrences survive unavailable declaration ownership; file-scoped imports do not acquire invented declaration identity. Each walk observation preserves depth, frontier progress, partial
expansions, inherited omission provenance, independent non-expandable occurrences, and complete, resumable, or terminal incomplete coverage. Query
qualification carries the execution continuation when unfinished work is
resumable. The query execution budget limits further work; a larger grant does
not by itself establish completeness.

Hosted hard deadline exhaustion cannot publish a result without established validity. It returns
`reduce_read_work` with the available admission budget; cancellation is separately
identified and does not claim a timeout. A semantic provider that stops within
its grant can return accumulated facts as qualified evidence.

## Impact completion

Impact completion additionally requires the conserved investigation ledger. Every admitted producer, compiler branch, read rejection, model arrival and required terminal obligation remains accounted for. A drained execution with required native-flow, boundary, representation, execution-boundary or producer-identity obligations stays qualified. `impact_accounting` retains the original counts and closure independently from the displayed page; a selected subset cannot establish the original closure.

Ledger construction also checks every applicable reviewed origin, transfer, transformation, consumer expectation and boundary alternative. Native branch accounting keeps each route's exact origin history and preceding model steps, so one modeled route cannot supply another route's missing branch. Omission rejects with `MISSING_BRANCH`. Models without a match or visit remain retained without creating a route; an explicit execution cutoff retains its unresolved obligation.

Impact source admission has closed reference and admission failures. Each admission cause owns its recovery direction, while execution and presentation failures keep their finite owner-specific shapes. These failures do not become empty successful results.

Requested sites form an independent, native-admitted target universe. Each target retains one accounting outcome derived from the original ledger paths: reached paths, exact site and domain scope exclusions, or an unproven relationship. A reached target also retains any separate proven exclusions. Native site admission proves the selected site identity; it does not prove a relationship to a producer. An unmatched target adds the required `REQUESTED_SITE_RELATIONSHIP` obligation, so a drained investigation cannot report semantic completion while that relationship remains unproven.

A guarded peer continuation retains the independently completed target read and the reviewed model edge. It ends with `PEER_FLOW_NOT_INVESTIGATED` and a required `BOUNDARY` obligation. No compiler observation for the peer target is admitted into the source investigation, so completing execution cannot discharge that obligation. The completed target receipt is historical evidence, not a claim that the two roots share an atomic epoch.
