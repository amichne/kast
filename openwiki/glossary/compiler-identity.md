---
type: Glossary Term
title: Compiler identity
description: The exact declaration selector and signature established by compiler-backed refinement rather than inferred from display text.
resource: file://symbol/contract
tags: [glossary, compiler, symbol]
code_sources:
  - path: symbol/contract/src/main/kotlin/io/github/amichne/kast/symbol/contract/ExactDeclarationSelector.kt
    symbols: [ExactDeclarationSelector]
  - path: symbol/contract/src/main/kotlin/io/github/amichne/kast/symbol/contract/exact/SymbolSelector.kt
    symbols: [SymbolSelector, CompilerGroundedSymbolEvidence]
  - path: symbol/contract/src/main/kotlin/io/github/amichne/kast/symbol/contract/CanonicalCompilerSignature.kt
    symbols: [CanonicalCompilerSignature]
  - path: symbol/service/src/main/kotlin/io/github/amichne/kast/symbol/service/SymbolExactService.kt
    symbols: [SymbolExactService]
  - path: change/apply/src/main/kotlin/io/github/amichne/kast/change/apply/LiveMutationAuthority.kt
sources:
  - id: openwiki-source-432d05143d371dfe54d7f30d
    resource: repo://change/apply/src/main/kotlin/io/github/amichne/kast/change/apply/LiveMutationAuthority.kt
  - id: openwiki-source-705a59188ebb6e00a05c7204
    resource: repo://symbol/contract/src/main/kotlin/io/github/amichne/kast/symbol/contract/CanonicalCompilerSignatureEncoding.kt
  - id: openwiki-source-d932b255353af72bc963a5f0
    resource: repo://symbol/contract/src/main/kotlin/io/github/amichne/kast/symbol/contract/exact/SymbolSelector.kt
  - id: openwiki-source-61d3e4205f93cd7e76343311
    resource: repo://symbol/contract/src/main/kotlin/io/github/amichne/kast/symbol/contract/ExactDeclarationSelector.kt
  - id: openwiki-source-5dc71c24cf32522bcaec58f5
    resource: repo://symbol/contract/src/main/kotlin/io/github/amichne/kast/symbol/contract/LocalDeclarationAddress.kt
  - id: openwiki-source-48e35a1fbb35fcbc2edbdded
    resource: repo://symbol/contract/src/test/kotlin/io/github/amichne/kast/symbol/contract/LocalDeclarationIdentityTest.kt
generated: { by: "codex", at: "2026-10-08T03:09:54.978Z" }
verified:
  - by: openwiki/0.6.1
    at: 2026-10-08T03:09:54.978Z
---

# Compiler identity

Names and text matches are discovery inputs, not declaration authority. Compiler identity is the exact selector/signature established after candidate refinement, including the information required to distinguish overloads and preserve source/workspace binding.

Relations, source reads, traversal, diagnostics, and changes should consume the refined identity. They must not fall back to a display name when exact resolution fails.

Exact selectors retain published or live read authority and the selected scope
and discovery constraints. Their fingerprint covers those facts and the canonical
compiler identity. Candidate refinement rejects evidence outside the retained
declaration kinds. A relation target can carry a different declaration kind
through its separate compiler-evidence issuance path.

Compiler identity alone does not authorize a write. The published path requires
a published lease. Hosted `AddDeclaration` retains its live planning basis and
requires a fresh matching live preimage, a permanent attempt claim and durable
recovery preparation before `LiveMutationAuthority` admits the write. An epoch
alone supplies neither identity nor mutation authority.

Canonical signatures retain an explicit qualified or local declaration address.
Qualified signatures preserve their existing canonical bytes and identity
digests. Local signatures use the versioned `local-v1` encoding: exact file/range,
compiler containing declaration identity/range and bounded lexical ownership
anchors. Local functions retain callable facts and result type; local properties
retain type and mutability. Names constrain search and presentation without
establishing equality. A local address is positive compiler identity evidence,
separate from unavailable qualified-name evidence.
