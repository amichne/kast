---
type: Explanation
title: What Depends on This API?
description: Follow compiler-backed relationships from one exact Kotlin declaration.
tags: [dependencies, references, callers, hierarchy]
code_sources:
  - path: cli-rs/protocol/source/commands.json
  - path: analysis-api/src/main/kotlin/io/github/amichne/kast/api/contract/result/relationships/evidence/RelationshipResultEvidence.kt
  - path: analysis-api/src/main/kotlin/io/github/amichne/kast/api/contract/result/relationships/evidence/RelationshipSearchCoverage.kt
---

# What Depends on This API?

This page shows how dependency evidence differs from a repository-wide token
search.

## Dependencies are relationships, not repeated names

Consider an interface used through constructor injection:

```kotlin
interface PricePolicy {
    fun price(order: Order): Money
}

class RegionalPricePolicy : PricePolicy {
    override fun price(order: Order): Money = TODO()
}

class Checkout(private val policy: PricePolicy)
```

The relevant dependency set is not every file containing `PricePolicy`.
Implementations, overrides, call sites, aliases, and inherited members express
different relationships to one declaration.

## Start from one identity

Kast can follow references, incoming callers, outgoing callees,
implementations, supertypes, and subtypes from the resolved declaration. Each
edge retains both endpoint identities and source evidence for the occurrence
that connects them.

This makes several repository questions distinct:

- A reference answers where the declaration is used.
- A caller answers which callable invokes it.
- An implementation answers which declaration satisfies its contract.
- A subtype or supertype answers where it sits in the type hierarchy.

Combining those answers is useful. Collapsing them into one undifferentiated
list is not.

## Coverage determines what “every” means

A returned edge is positive evidence even when more files remain. A claim that
there are no other dependents needs complete relationship coverage for the
eligible scope and source generation. Limited coverage turns the result into a
qualified answer, not a complete negative one.

External builds, reflection, runtime service lookup, and consumers outside the
admitted workspace remain outside this proof. For transitive change planning,
continue with [what must change if this contract changes](contract-change.md).
