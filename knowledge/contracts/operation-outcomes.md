---
type: API Contract
title: Operation outcomes
description: Semantic success is complete or explicitly qualified and carries either published or live evidence; rejection carries no successful payload.
resource: file://kernel/src/main/kotlin/io/github/amichne/kast/kernel/OperationOutcome.kt
tags: [outcome, evidence, failure]
timestamp: 2026-09-10T00:00:00Z
code_sources:
  - path: kernel/src/main/kotlin/io/github/amichne/kast/kernel/OperationOutcome.kt
    symbols: [OperationOutcome]
  - path: kernel/src/main/kotlin/io/github/amichne/kast/kernel/EvidenceEnvelope.kt
    symbols: [EvidenceEnvelope, EvidenceGeneration, EvidenceBasis, LiveReadEvidence]
  - path: protocol/wire/src/main/kotlin/io/github/amichne/kast/protocol/wire/OperationWireBinding.kt
  - path: protocol/contract/src/main/kotlin/io/github/amichne/kast/protocol/contract/AdmittedReadRejections.kt
  - path: protocol/contract/src/main/kotlin/io/github/amichne/kast/protocol/contract/ExecutionBudgetPresence.kt
  - path: README.md
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

Transport success does not imply semantic completeness. A host must preserve the distinction when projecting output, and it must not attach a successful payload to rejection. The [README](../../README.md) exposes the same complete/qualified/rejected semantics to users.
