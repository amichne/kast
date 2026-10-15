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
  - path: kernel/src/main/kotlin/io/github/amichne/kast/kernel/Validation.kt
  - path: kernel/src/main/kotlin/io/github/amichne/kast/kernel/ExecutionBudget.kt
  - path: kernel/src/main/kotlin/io/github/amichne/kast/kernel/ReadLimits.kt
  - path: kernel/src/main/kotlin/io/github/amichne/kast/kernel/KastObservability.kt
  - path: kernel/src/test/kotlin/io/github/amichne/kast/kernel/ExecutionBudgetTest.kt
  - path: kernel/src/test/kotlin/io/github/amichne/kast/kernel/ReadLimitsTest.kt
  - path: kernel/src/test/kotlin/io/github/amichne/kast/kernel/KernelContractTest.kt
sources:
  - id: openwiki-source-d69d2f9483e3ef07d3d7af62
    resource: repo://kernel/src/main/kotlin/io/github/amichne/kast/kernel/EvidenceEnvelope.kt
  - id: openwiki-source-02ae034b72e79a3f84b122d8
    resource: repo://kernel/src/main/kotlin/io/github/amichne/kast/kernel/ExecutionBudget.kt
  - id: openwiki-source-a1630771afb5f7112358b2fa
    resource: repo://kernel/src/main/kotlin/io/github/amichne/kast/kernel/KastObservability.kt
  - id: openwiki-source-1368a6d1e2827cd0501cec50
    resource: repo://kernel/src/main/kotlin/io/github/amichne/kast/kernel/OperationOutcome.kt
generated: { by: "codex", at: "2026-10-15T00:57:31.735Z" }
verified:
  - by: openwiki/0.6.1
    at: 2026-10-15T00:57:31.735Z
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

## Refinement and accumulation

`Refinement` carries either a stronger value or a finite rejection through a single decision.
`Validation` supports independent admissions that accumulate failures before a combined value exists.
Domain contracts choose the proper operation without replacing finite failures with primitive strings.
The [refinement glossary](../glossary/refinement.md) explains this distinction.

## Execution grants preserve their inputs

Positive result, work, elapsed-time, and returned-byte types reject zero and negative values before admission.
`RequestedExecutionBudget` distinguishes an absent caller allowance from an explicit constrained value.
`AdmittedExecutionBudget` keeps the requested value, configured default, operator ceiling, effective limit, and clamp causes.
Transport capacity and remaining deadline can further decrease the effective limit.
The same admitted grant accompanies domain work and response projections.

`ReadLimits` owns parameter identities and configured policy.
A resource budget describes one operation's granted resources.
Neither value proves current workspace authority or successful completion.
The [read authority explanation](../semantics/workspace/read-authority.md) connects these grants to live execution.

## Bounded observation vocabulary

`KastObservability` supplies closed span names, finite failure families, constrained counts, and typed events.
Request values do not become metric dimensions through that contract.
Domain and effect adapters supply observations through explicit capabilities.
Observation data describes the instrumented path without a new compiler or publication proof.

`ExecutionBudgetTest`, `ReadLimitsTest`, and `KernelContractTest` exercise the common contract decisions.
Those tests do not establish native effects or semantic completeness.
