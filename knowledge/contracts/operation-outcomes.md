---
type: API Contract
title: Operation outcomes
description: Semantic success is either complete or explicitly qualified and always carries generation-bound evidence; rejection carries no successful payload.
resource: file://kernel/src/main/kotlin/io/github/amichne/kast/kernel/OperationOutcome.kt
tags: [outcome, evidence, failure]
timestamp: 2026-09-09T00:00:00Z
code_sources:
  - path: kernel/src/main/kotlin/io/github/amichne/kast/kernel/OperationOutcome.kt
    symbols: [OperationOutcome]
  - path: kernel/src/main/kotlin/io/github/amichne/kast/kernel/EvidenceEnvelope.kt
    symbols: [EvidenceEnvelope, EvidenceGeneration]
  - path: README.md
---

# Operation outcomes

`OperationOutcome` is exhaustive:

- `Complete` carries an evidence envelope with no limitation.
- `Qualified` carries the same evidence plus a domain-owned qualification.
- `Rejected` carries only a closed rejection reason.

Transport success does not imply semantic completeness. A host must preserve the distinction when projecting output, and it must not attach a successful payload to rejection. The [README](../../README.md) exposes the same complete/qualified/rejected semantics to users.
