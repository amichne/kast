---
type: Explanation
title: Did This Change Reach Every Semantic Dependency?
description: Distinguish an empty search result from a complete semantic proof.
tags: [coverage, completeness, diagnostics, verification]
code_sources:
  - path: cli-rs/protocol/source/commands.json
  - path: analysis-api/src/main/kotlin/io/github/amichne/kast/api/contract/result/relationships/evidence/RelationshipSearchCoverage.kt
  - path: indexer/src/main/kotlin/io/github/amichne/kast/idea/semantic/relationships/RelationshipCoverageAuthority.kt
---

# Did This Change Reach Every Semantic Dependency?

This page shows why “nothing else was found” and “nothing else exists in the
eligible scope” are different claims.

## Absence is only meaningful inside a proven scope

After renaming an API, a repository search can return no old spelling. That
result does not prove that every semantic dependency moved. An alias, an
override, an inherited call, or a use without the old token can still bind to
the changed declaration.

Compiler relationships can test the exact old and new identities. Coverage
then determines how much authority an empty result carries.

## A complete negative answer has several parts

For a claim that no eligible dependency remains, the evidence needs:

- the exact declaration identity;
- the canonical workspace and resolved scope;
- complete eligible coverage at one source generation;
- a terminal, untruncated result with no remaining continuation; and
- no stale or failed files that intersect the claim.

Diagnostics answer a related but different question. They can show whether
selected or changed Kotlin files compile cleanly. They do not replace graph
coverage, and a ready runtime does not by itself prove either result.

## Preserve limitations instead of rounding up

If coverage is limited, positive relationships remain useful. The negative
claim stays qualified. If the generation changes during traversal, the old
continuation cannot prove the new workspace state. If exact identity or scope
cannot be established, the request must be rejected.

This is the difference between apparent completeness and proven coverage.
[When can you trust a Kast answer?](../concepts/evidence-boundaries.md) gives a
compact model for reading those outcomes.
