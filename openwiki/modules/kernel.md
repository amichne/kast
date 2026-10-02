---
type: Kotlin Module
title: Kernel
description: Small shared types carry proof, published or live evidence, finite outcomes, validation, budgets, and observability capabilities.
resource: file://kernel
tags: [kotlin, kernel, invariants]
code_sources:
  - path: kernel/src/main/kotlin/io/github/amichne/kast/kernel/OperationOutcome.kt
    symbols: [OperationOutcome]
  - path: kernel/src/main/kotlin/io/github/amichne/kast/kernel/EvidenceEnvelope.kt
    symbols: [EvidenceGeneration, EvidenceEnvelope, EvidenceBasis, LiveReadEvidence]
  - path: kernel/src/main/kotlin/io/github/amichne/kast/kernel/Refinement.kt
    symbols: [Refinement]
  - path: kernel/src/main/kotlin/io/github/amichne/kast/kernel/ResourceBudget.kt
    symbols: [ResourceBudget]
verified:
  - by: openwiki/0.6.1
    at: 2026-10-02T02:00:02.467Z
sources:
  - id: openwiki-source-d69d2f9483e3ef07d3d7af62
    resource: repo://kernel/src/main/kotlin/io/github/amichne/kast/kernel/EvidenceEnvelope.kt
  - id: openwiki-source-1368a6d1e2827cd0501cec50
    resource: repo://kernel/src/main/kotlin/io/github/amichne/kast/kernel/OperationOutcome.kt
generated: { by: "codex", at: "2026-10-02T02:00:02.467Z" }
---

# Kernel

The kernel contains cross-domain types whose meaning must survive module boundaries. [OperationOutcome](../../kernel/src/main/kotlin/io/github/amichne/kast/kernel/OperationOutcome.kt) separates complete evidence, qualified evidence, and rejection. [EvidenceEnvelope](../../kernel/src/main/kotlin/io/github/amichne/kast/kernel/EvidenceEnvelope.kt) binds a successful payload to its operation and a closed `EvidenceBasis`.

`EvidenceBasis.Published` carries a non-negative publication generation.
`EvidenceBasis.Live` carries a detached root, original host identity, positive IDE
epoch revision, version, and saved, PSI-committed content view. The envelope has no
common generation getter. Live provenance is data; it grants no execution or
publication authority.

The module does not own symbol, workspace, protocol, or change semantics. Those meanings remain in their domain contracts and use kernel types only for common proof shapes.

## Read next

- [Operation outcomes](../contracts/operation-outcomes.md)
- [Refinement](../glossary/refinement.md)
- [Evidence generation](../glossary/evidence-generation.md)
