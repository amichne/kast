---
type: Glossary Term
title: Compiler identity
description: The exact declaration selector and signature established by compiler-backed refinement rather than inferred from display text.
resource: file://symbol/contract
tags: [glossary, compiler, symbol]
timestamp: 2026-09-10T00:00:00Z
code_sources:
  - path: symbol/contract/src/main/kotlin/io/github/amichne/kast/symbol/contract/ExactDeclarationSelector.kt
    symbols: [ExactDeclarationSelector]
  - path: symbol/contract/src/main/kotlin/io/github/amichne/kast/symbol/contract/exact/SymbolSelector.kt
    symbols: [SymbolSelector, CompilerGroundedSymbolEvidence]
  - path: symbol/contract/src/main/kotlin/io/github/amichne/kast/symbol/contract/CanonicalCompilerSignature.kt
    symbols: [CanonicalCompilerSignature]
  - path: symbol/service/src/main/kotlin/io/github/amichne/kast/symbol/service/SymbolExactService.kt
    symbols: [SymbolExactService]
---

# Compiler identity

Names and text matches are discovery inputs, not declaration authority. Compiler identity is the exact selector/signature established after candidate refinement, including the information required to distinguish overloads and preserve source/workspace binding.

Relations, source reads, traversal, diagnostics, and changes should consume the refined identity. They must not fall back to a display name when exact resolution fails.

Exact selectors retain published or live read authority and the selected scope
and discovery constraints. Their fingerprint covers those facts and the canonical
compiler identity. Candidate refinement rejects evidence outside the retained
declaration kinds. A relation target can carry a different declaration kind
through its separate compiler-evidence issuance path.

Compiler identity alone does not authorize a write. Live selectors still need a
published refinement at mutation admission; an IDE epoch cannot supply it.
