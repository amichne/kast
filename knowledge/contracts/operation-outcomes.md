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
  - path: README.md
---

# Operation outcomes

`OperationOutcome` is exhaustive:

- `Complete` carries an evidence envelope with no limitation.
- `Qualified` carries the same evidence plus a domain-owned qualification.
- `Rejected` carries only a closed rejection reason.

An evidence envelope has one closed basis: published generation or detached live
IDE provenance. Live evidence retains root, host identity, epoch revision, schema
version, and saved, PSI-committed content view. It does not prove a workspace
publication or grant mutation authority. The wire boundary rejects missing or
simultaneously supplied published/live evidence.

Transport success does not imply semantic completeness. A host must preserve the distinction when projecting output, and it must not attach a successful payload to rejection. The [README](../../README.md) exposes the same complete/qualified/rejected semantics to users.
