---
type: Glossary Term
title: Compiler identity
description: The exact declaration selector and signature established by compiler-backed refinement rather than inferred from display text.
resource: file://symbol/contract
tags: [glossary, compiler, symbol]
timestamp: 2026-09-09T00:00:00Z
code_sources:
  - path: symbol/contract/src/main/kotlin/io/github/amichne/kast/symbol/contract/ExactDeclarationSelector.kt
    symbols: [ExactDeclarationSelector]
  - path: symbol/contract/src/main/kotlin/io/github/amichne/kast/symbol/contract/CanonicalCompilerSignature.kt
    symbols: [CanonicalCompilerSignature]
  - path: symbol/service/src/main/kotlin/io/github/amichne/kast/symbol/service/SymbolExactService.kt
    symbols: [SymbolExactService]
---

# Compiler identity

Names and text matches are discovery inputs, not declaration authority. Compiler identity is the exact selector/signature established after candidate refinement, including the information required to distinguish overloads and preserve source/workspace binding.

Relations, source reads, traversal, diagnostics, and changes should consume the refined identity. They must not fall back to a display name when exact resolution fails.
