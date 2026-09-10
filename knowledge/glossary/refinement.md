---
type: Glossary Term
title: Refinement
description: A refinement consumes weak boundary data and returns either a stronger invariant-bearing value or a closed expected failure.
resource: file://kernel/src/main/kotlin/io/github/amichne/kast/kernel/Refinement.kt
tags: [glossary, types, invariants]
timestamp: 2026-09-09T00:00:00Z
code_sources:
  - path: kernel/src/main/kotlin/io/github/amichne/kast/kernel/Refinement.kt
    symbols: [Refinement]
  - path: kernel/src/main/kotlin/io/github/amichne/kast/kernel/EvidenceEnvelope.kt
    symbols: [EvidenceGeneration]
  - path: AGENTS.md
---

# Refinement

In Kast, refinement is a proof transition. Parsing or admission consumes a primitive or weak candidate and returns either `Refinement.Refined<StrongValue>` or `Refinement.Rejected<ClosedFailure>`.

Callers should retain the strong value rather than extract its primitive and repeat the check. This is the code-level form of the repository rule “refine; never erase.”
