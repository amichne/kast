---
type: Kotlin Module
title: Kernel
description: Small shared types carry proof, evidence generations, finite outcomes, validation, budgets, and observability capabilities.
resource: file://kernel
tags: [kotlin, kernel, invariants]
timestamp: 2026-09-09T00:00:00Z
code_sources:
  - path: kernel/src/main/kotlin/io/github/amichne/kast/kernel/OperationOutcome.kt
    symbols: [OperationOutcome]
  - path: kernel/src/main/kotlin/io/github/amichne/kast/kernel/EvidenceEnvelope.kt
    symbols: [EvidenceGeneration, EvidenceEnvelope]
  - path: kernel/src/main/kotlin/io/github/amichne/kast/kernel/Refinement.kt
    symbols: [Refinement]
  - path: kernel/src/main/kotlin/io/github/amichne/kast/kernel/ResourceBudget.kt
    symbols: [ResourceBudget]
---

# Kernel

The kernel contains cross-domain types whose meaning must survive module boundaries. [OperationOutcome](../../kernel/src/main/kotlin/io/github/amichne/kast/kernel/OperationOutcome.kt) separates complete evidence, qualified evidence, and rejection. [EvidenceEnvelope](../../kernel/src/main/kotlin/io/github/amichne/kast/kernel/EvidenceEnvelope.kt) binds a successful payload to its operation and non-negative evidence generation.

The module does not own symbol, workspace, protocol, or change semantics. Those meanings remain in their domain contracts and use kernel types only for common proof shapes.

## Read next

- [Operation outcomes](../contracts/operation-outcomes.md)
- [Refinement](../glossary/refinement.md)
- [Evidence generation](../glossary/evidence-generation.md)
