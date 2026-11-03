---
type: Glossary Term
title: Refinement
description: A refinement consumes weak boundary data and returns either a stronger invariant-bearing value or a closed expected failure.
resource: file://kernel/src/main/kotlin/io/github/amichne/kast/kernel/Refinement.kt
tags: [glossary, types, invariants]
code_sources:
  - path: kernel/src/main/kotlin/io/github/amichne/kast/kernel/Refinement.kt
    symbols: [Refinement]
  - path: kernel/src/main/kotlin/io/github/amichne/kast/kernel/EvidenceEnvelope.kt
    symbols: [EvidenceGeneration]
  - path: AGENTS.md
sources:
  - id: openwiki-source-099007a1bc17059a5a48fcc5
    resource: repo://kernel/src/main/kotlin/io/github/amichne/kast/kernel/Refinement.kt
generated: { by: "codex", at: "2026-11-03T06:09:31.584Z" }
verified:
  - by: openwiki/0.6.1
    at: 2026-11-03T06:09:31.584Z
---

# Refinement

In Kast, refinement is a proof transition. Parsing or admission consumes a primitive or weak candidate and returns either `Refinement.Refined<StrongValue>` or `Refinement.Rejected<ClosedFailure>`.

Callers must keep the strong value. They must not extract its primitive and repeat the discharged check. This is the code-level form of the repository rule “refine; never erase.”
