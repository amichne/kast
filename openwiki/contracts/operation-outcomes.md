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
  - id: openwiki-source-1d161f3eb7933044519fd33a
    resource: repo://cli/src/main/kotlin/io/github/amichne/kast/cli/rpc/KastToolRpcMain.kt
  - id: openwiki-source-d69d2f9483e3ef07d3d7af62
    resource: repo://kernel/src/main/kotlin/io/github/amichne/kast/kernel/EvidenceEnvelope.kt
  - id: openwiki-source-1368a6d1e2827cd0501cec50
    resource: repo://kernel/src/main/kotlin/io/github/amichne/kast/kernel/OperationOutcome.kt
  - id: openwiki-source-ddbecea6a66f516818cd6c13
    resource: repo://protocol/contract/src/main/kotlin/io/github/amichne/kast/protocol/contract/ReadRecoveryAction.kt
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
generated: { by: "codex", at: "2026-10-03T17:08:58.136Z" }
verified:
  - by: openwiki/0.6.1
    at: 2026-10-03T17:08:58.136Z
---

# Operation outcomes

Text discovery uses existing exact-symbol query outcomes. Composition rejects
more than 1000 distinct lexical match exemplars for one owner with the finite
`TEXT_MATCH_LIMIT_EXCEEDED` execution reason. Wire and installed projections
retain that reason and its `ADJUST_BUDGET_OR_SCOPE` recovery action; they do not
turn it into an unknown or internal failure.

`OperationOutcome` is exhaustive:

- `Complete` carries an evidence envelope with no limitation.
- `Qualified` carries the same evidence plus a domain-owned qualification.
- `Rejected` carries an operation-owned closed failure and no successful payload.

Query, source, and diagnostic reads distinguish their finite rejection reason from
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

The Kast MCP transport validates the operation-owned semantic document and
returns it unchanged in `structuredContent` and text. Its `complete`,
`qualified`, or `rejected` status, coverage, references, finite failures, and
recovery evidence retain their native meaning. A boundary invocation failure
uses the MCP error flag separately and does not invent a semantic result.

Transport success does not imply semantic completeness. A host must preserve the distinction when projecting output, and it must not attach a successful payload to rejection. The public [result guide](../../docs/public/reference/responses.mdx) gives the user-facing interpretation.

The one-shot tool RPC returns a closed `complete`, `qualified`, `rejected_document`, or boundary `rejected` variant. It preserves the canonical result document under `document` for semantic outcomes, so Copilot and Pi adapters cannot turn qualified evidence into a complete result. The catalog marks `add_declaration` as `WRITE` and all direct reads as `READ`. A shutdown fence returns the closed `INSTALLATION_STOPPED` boundary failure before workspace discovery, preparation, or semantic dispatch.

Canonical query and source failures derive a closed
`ReadRecoveryAction`. Their CLI/tool rejected documents require `next_action`
for both unadmitted and budget-bearing rejections. The wire retains the finite
failure and budget; projection derives the action again after decoding rather
than accepting a separate action as authority.

A `concat` exact-reference rejection retains both the step and reference positions. It uses the same finite reference reason and recovery action as a source reference rejection. An unavailable retained row or incomplete right operand is a request correction, while an unavailable or stale retained result directs a fresh read.

Query admission has distinct finite reasons for unknown projected binding names and mismatched output kinds. A retained binding-row result presented as symbols rejects rather than returning an empty symbol stream. An incomplete anti-join right input rejects before execution because absence is unproven.

| Action | Required direction |
| --- | --- |
| `reacquire_authority` | Obtain fresh authority for a stale, foreign, malformed or otherwise unusable opaque reference; do not repair its text. |
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

Qualified query results retain discovery and reference observations, detailed relation omissions, and walk observations alongside
known rows. Positive compiler-confirmed occurrences survive unavailable declaration ownership; file-scoped imports do not acquire invented declaration identity. Each walk observation preserves depth, frontier progress, partial
expansions, inherited omission provenance, independent non-expandable occurrences, and complete, resumable, or terminal incomplete coverage. Query
qualification carries the execution continuation when unfinished work is
resumable. The query execution budget limits further work; a larger grant does
not by itself establish completeness.

Hosted hard deadline exhaustion cannot publish an unvalidated result. It returns
`reduce_read_work` with the available admission budget; cancellation is separately
identified and does not claim a timeout. A semantic provider that stops within
its grant can return accumulated facts as qualified evidence.

Impact completion additionally requires the conserved investigation ledger. Every admitted producer, compiler branch, read rejection, model arrival and required terminal obligation remains accounted for. A drained execution with required native-flow, boundary, representation, execution-boundary or producer-identity obligations stays qualified. `impact_accounting` retains the original counts and closure independently from the displayed page; a selected subset cannot establish the original closure.

Ledger construction also checks every applicable reviewed origin, transfer, transformation, consumer expectation and boundary alternative. Native branch accounting keeps each route's exact origin history and preceding model steps, so one modeled route cannot supply another route's missing branch. Omission rejects with `MISSING_BRANCH`. Nonmatching or unvisited models remain retained without creating a route; an explicit execution cutoff retains its unresolved obligation.

Impact source admission has closed reference and admission failures. Each admission cause owns its recovery direction, while execution and presentation failures retain their finite owner-specific shapes. These failures do not become empty successful results.

Requested sites form an independent, native-admitted target universe. Each target retains one accounting outcome derived from the original ledger paths: reached paths, exact site and domain scope exclusions, or an unproven relationship. A reached target also retains any separate proven exclusions. Native site admission proves the selected site identity; it does not prove a relationship to a producer. An unmatched target adds the required `REQUESTED_SITE_RELATIONSHIP` obligation, so a drained investigation cannot report semantic completion while that relationship remains unproven.

A guarded peer continuation retains the independently completed target read and the reviewed model edge. It ends with `PEER_FLOW_NOT_INVESTIGATED` and a required `BOUNDARY` obligation. No compiler observation for the peer target is admitted into the source investigation, so completing execution cannot discharge that obligation. The completed target receipt is historical evidence, not a claim that the two roots share an atomic epoch.
