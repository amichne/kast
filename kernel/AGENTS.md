---
type: Module Guide
title: "Kast kernel"
description: "Defines shared outcomes, evidence, refinement, identity, budgets, and observability for the semantic domains."
resource: "repo://kernel"
tags: [kast, navigation]
timestamp: "2026-10-22T06:15:00Z"
source_revision: ebc805014ada9e3eb1b85ef611e41bd176af8d80
code_sources:
  - path: kernel/build.gradle.kts
  - path: kernel/src/main/kotlin/io/github/amichne/kast/kernel/EvidenceEnvelope.kt
  - path: kernel/src/main/kotlin/io/github/amichne/kast/kernel/ExecutionBudget.kt
  - path: kernel/src/main/kotlin/io/github/amichne/kast/kernel/KastObservability.kt
  - path: kernel/src/main/kotlin/io/github/amichne/kast/kernel/OperationOutcome.kt
  - path: kernel/src/main/kotlin/io/github/amichne/kast/kernel/ReadLimits.kt
  - path: kernel/src/main/kotlin/io/github/amichne/kast/kernel/Refinement.kt
  - path: kernel/src/main/kotlin/io/github/amichne/kast/kernel/ResourceBudget.kt
  - path: kernel/src/main/kotlin/io/github/amichne/kast/kernel/ResultAllowance.kt
  - path: kernel/src/main/kotlin/io/github/amichne/kast/kernel/WorkAllowance.kt
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
- [ResourceBudget.kt](src/main/kotlin/io/github/amichne/kast/kernel/ResourceBudget.kt) - finite grants and the explicit `RunnerControlled` variant.
- [WorkAllowance.kt](src/main/kotlin/io/github/amichne/kast/kernel/WorkAllowance.kt) and [ResultAllowance.kt](src/main/kotlin/io/github/amichne/kast/kernel/ResultAllowance.kt) - finite or unlimited semantic allowances.
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
- Carry the budget variant through consumers. Keep finite output pages separate from unlimited semantic allowances. Do not encode unlimited grants with numeric sentinels.
- Read the [root guide](../AGENTS.md) for engineering rules and optional OpenWiki retrieval.
