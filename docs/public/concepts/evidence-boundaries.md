---
type: Explanation
title: When Can You Trust a Kast Answer?
description: Read exact identity, scope, coverage, and rejection without weakening the result.
tags: [trust, evidence, coverage, limitations]
code_sources:
  - path: cli-rs/protocol/source/commands.json
  - path: analysis-api/src/main/kotlin/io/github/amichne/kast/api/contract/result/relationships/evidence/RelationshipSearchCoverage.kt
  - path: analysis-api/src/main/kotlin/io/github/amichne/kast/api/contract/skill/symbol/KastResolveResponse.kt
---

# When Can You Trust a Kast Answer?

This page gives one model for deciding how strongly a Kast result supports a
repository claim.

## Trust begins with identity

An exact symbol result retains the declaration's compiler identity and source
location. Relationship results retain both endpoints and the occurrence that
connects them. A name, label, or nearby text match can help discover a
candidate, but it does not replace that identity.

## Read the outcome without strengthening it

| Outcome | What it supports | What it does not support |
| --- | --- | --- |
| complete evidence | The operation covered its eligible scope at the stated generation and returned a terminal result. | Code, consumers, runtime behavior, or data outside that scope. |
| qualified evidence | Returned identities and positive relationships remain usable with the stated limitation. | An exhaustive count or a complete negative answer. |
| rejected request | Kast could not establish an identity, scope, generation, or other required precondition. | Any semantic claim inferred from a fallback guess. |

“Ready,” “non-empty,” and “no diagnostics” are separate observations. None of
them silently upgrades limited relationship coverage to complete evidence.

## Four boundaries travel with the answer

1. **Workspace** identifies the exact repository root and checkout.
2. **Scope** identifies the eligible language, module, source set, and relation
   family.
3. **Generation** identifies the source and graph state used by the operation.
4. **Bounds** identify depth, result limits, truncation, and continuation.

If one of these boundaries changes, reuse the evidence only when the operation
contract explicitly permits it. An authenticated continuation belongs to the
same query and generation; it is not a general cursor over future repository
state.

## A smaller claim can be stronger

Kast is most trustworthy when the answer says exactly what was established.
“These three compiler-identified callers exist” can remain valid under limited
coverage. “These are all callers” requires complete coverage. “No callers
exist” requires the same complete authority plus a terminal empty result.

The [generated semantic operation contract](../reference/semantic-operations.md)
lists the typed methods, request fields, response types, and result variants
that carry these facts.
