---
type: Explanation
title: Where Can This Value Flow?
description: Separate compiler-visible relationships from runtime value provenance.
tags: [value-flow, calls, references, evidence-boundary]
code_sources:
  - path: cli-rs/protocol/source/commands.json
  - path: cli-rs/src/semantics/repository_intelligence/contract/request.rs
  - path: analysis-api/src/main/kotlin/io/github/amichne/kast/api/contract/result/relationships/evidence/RelationshipResultEvidence.kt
---

# Where Can This Value Flow?

This page defines the strongest value-flow claim Kast can support from its
current semantic relationships.

## Static relationships reveal part of the path

Consider a value created at a boundary and passed into a service:

```kotlin
@JvmInline
value class OrderId(val value: String)

fun accept(request: Request): Receipt {
    val orderId = OrderId(request.orderId)
    return orderService.load(orderId)
}
```

Text can find the variable name. Compiler evidence can identify the
`OrderId` constructor, the selected `load` overload, the caller and callee,
and other references to the same declarations. Repository traversal can also
follow bounded incoming or outgoing relationships such as calls, references,
and type dependencies.

Those facts constrain where the value can move through compiler-visible code.
They are stronger than matching tokens because aliases, overloads, and owner
types remain attached to the edges.

## Static evidence is not runtime provenance

Kast does not prove runtime value flow. Its current contract does not track one
runtime value through branches, heap aliases, database rows, serialized
messages, reflection, dependency injection, or external processes.

The defensible question is therefore narrower:

> Which compiler-visible declarations and relationships can carry or consume
> this typed value inside the admitted scope?

The answer can enumerate exact calls and references with visible bounds. It
cannot claim that every runtime instance follows those paths or that no hidden
runtime path exists.

This boundary keeps static semantic evidence useful without presenting it as a
dynamic trace. [When can you trust a Kast answer?](../concepts/evidence-boundaries.md)
explains how scope and coverage qualify the result.
