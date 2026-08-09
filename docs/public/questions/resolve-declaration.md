---
type: Explanation
title: What Declaration Does This Actually Refer To?
description: Distinguish a matching Kotlin name from one exact compiler identity.
tags: [identity, symbols, ambiguity, compiler-evidence]
code_sources:
  - path: cli-rs/protocol/source/commands.json
  - path: analysis-api/src/main/kotlin/io/github/amichne/kast/api/contract/skill/symbol/KastResolveResponse.kt
  - path: analysis-api/src/main/kotlin/io/github/amichne/kast/api/contract/skill/selector/KastExactSymbolSelector.kt
---

# What Declaration Does This Actually Refer To?

This page shows why a Kotlin name is not enough to identify the declaration a
use site means.

## One spelling can name different declarations

Aliases, overloads, inherited members, extension functions, and repeated names
make text matches ambiguous:

```kotlin
package checkout

import billing.PricePolicy as BillingPricePolicy

class Checkout(private val policy: BillingPricePolicy) {
    fun total(order: Order): Money = policy.price(order)
}
```

Another package can declare `PricePolicy`. A type can also provide several
`price` overloads. Searching for either spelling produces candidates, not the
binding selected by the Kotlin compiler.

## Exact identity closes the ambiguity

Kast resolves the use site to an exact compiler identity. That identity retains
the fully qualified name, declaration file, source offset, declaration kind,
and containing type when one exists. A later relationship query consumes that
identity instead of resolving the name again.

This distinction prevents two unsafe shortcuts:

- choosing the closest matching file; and
- treating every declaration with the same simple name as the same symbol.

If the available constraints still select several declarations, the result is
ambiguous. Kast returns the candidates instead of manufacturing one answer.

## The evidence has a boundary

Exact identity proves what valid Kotlin code binds to in the admitted
workspace. It does not decide which declaration an incomplete snippet was
intended to name. It also does not turn reflection, generated code outside the
workspace, or a string containing a class name into a compiler relationship.

Once the declaration is exact, the next practical question is
[what depends on this API](dependents.md).
