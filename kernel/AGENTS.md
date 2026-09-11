<!-- AGENTS.md: generated navigation; keep this file checked in -->
<!-- generated: 2026-09-11 | hash: 9f0a354e79f5 -->

# kernel

## Purpose

Provides the smallest shared domain vocabulary for typed outcomes, evidence, validation, refinement, identity, resource budgets, and observability.

## Key Files

- [src/main/kotlin/io/github/amichne/kast/kernel/OperationOutcome.kt](src/main/kotlin/io/github/amichne/kast/kernel/OperationOutcome.kt) - complete/qualified/rejected outcome semantics.
- [src/main/kotlin/io/github/amichne/kast/kernel/EvidenceEnvelope.kt](src/main/kotlin/io/github/amichne/kast/kernel/EvidenceEnvelope.kt) - evidence-bearing result envelope.
- [src/main/kotlin/io/github/amichne/kast/kernel/Refinement.kt](src/main/kotlin/io/github/amichne/kast/kernel/Refinement.kt) - proof-preserving refinement primitives.
- [src/main/kotlin/io/github/amichne/kast/kernel/ResourceBudget.kt](src/main/kotlin/io/github/amichne/kast/kernel/ResourceBudget.kt) - bounded execution values.
- [src/main/kotlin/io/github/amichne/kast/kernel/KastObservability.kt](src/main/kotlin/io/github/amichne/kast/kernel/KastObservability.kt) - observability port.

## Subdirectories

- `src/main` - shared pure contracts.
- `src/test` - invariant and serialization evidence.

## Entry Points

- Gradle project: `:kernel`.

## Navigation Hints

- Start with the [repository knowledge](../knowledge/modules/kernel.md) and follow its `code_sources` for source evidence. Root engineering rules remain authoritative; this map adds navigation only.

- Read this module when a cross-domain outcome, proof, identity, or budget is unclear.
- Keep domain-specific meaning in its owning contract rather than expanding the kernel by convenience.
