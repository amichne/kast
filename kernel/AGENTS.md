---
type: Module Guide
title: "Kast kernel"
description: "Defines shared outcomes, evidence, refinement, identity, budgets, and observability for the semantic domains."
resource: "repo://kernel"
tags: [kast, navigation]
timestamp: "2026-10-14T23:25:10Z"
source_revision: e014a489bfd7e69cbf2c4b61140ee771d857a03e
code_sources:
  - path: kernel/build.gradle.kts
  - path: kernel/src/main/kotlin/io/github/amichne/kast/kernel/EvidenceEnvelope.kt
  - path: kernel/src/main/kotlin/io/github/amichne/kast/kernel/ExecutionBudget.kt
  - path: kernel/src/main/kotlin/io/github/amichne/kast/kernel/KastObservability.kt
  - path: kernel/src/main/kotlin/io/github/amichne/kast/kernel/OperationOutcome.kt
  - path: kernel/src/main/kotlin/io/github/amichne/kast/kernel/ReadLimits.kt
  - path: kernel/src/main/kotlin/io/github/amichne/kast/kernel/Refinement.kt
  - path: kernel/src/test/kotlin/io/github/amichne/kast/kernel/ExecutionBudgetTest.kt
  - path: kernel/src/test/kotlin/io/github/amichne/kast/kernel/ReadLimitsTest.kt
---

<!-- AGENTS.md: generated navigation; keep this file checked in -->
<!-- generated: 2026-10-14 | hash: 9f0a354e79f5 -->

# kernel

## Purpose

Defines shared outcomes, evidence, refinement, identity, budgets, and observability for the semantic domains.

## Key Files

- [OperationOutcome.kt](src/main/kotlin/io/github/amichne/kast/kernel/OperationOutcome.kt) - complete, qualified, and rejected outcomes.
- [EvidenceEnvelope.kt](src/main/kotlin/io/github/amichne/kast/kernel/EvidenceEnvelope.kt) - payload evidence and its authority.
- [Refinement.kt](src/main/kotlin/io/github/amichne/kast/kernel/Refinement.kt) - typed admission and finite failure.
- [ExecutionBudget.kt](src/main/kotlin/io/github/amichne/kast/kernel/ExecutionBudget.kt) - caller grants, default grants, ceilings, and clamp evidence.
- [ReadLimits.kt](src/main/kotlin/io/github/amichne/kast/kernel/ReadLimits.kt) - read, retention, and concurrency limits.
- [KastObservability.kt](src/main/kotlin/io/github/amichne/kast/kernel/KastObservability.kt) - the domain observability port.

## Subdirectories

- [src/main/](src/main/) - shared contracts.
- [src/test/](src/test/) - invariant and serialization tests.

## Entry Points

- Gradle project: `:kernel`.
- [ExecutionBudgetTest.kt](src/test/kotlin/io/github/amichne/kast/kernel/ExecutionBudgetTest.kt) - grant and ceiling cases.
- [ReadLimitsTest.kt](src/test/kotlin/io/github/amichne/kast/kernel/ReadLimitsTest.kt) - limit admission cases.

## Navigation Hints

- For a shared proof or failure, start with `OperationOutcome` and `Refinement`.
- Keep domain-specific meaning in the domain contract.
- Read the [root guide](../AGENTS.md) for engineering rules and optional OpenWiki retrieval.
