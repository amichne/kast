# Kast

Kast helps an engineer tell the difference between a plausible reading of
Kotlin code and a repository answer grounded in compiler identity, semantic
relationships, and explicit coverage.

## The difference is visible in one line

Consider a checkout service that imports one of several declarations named
`PricePolicy`:

```kotlin
import billing.PricePolicy as BillingPricePolicy

class Checkout(private val policy: BillingPricePolicy) {
    fun total(order: Order): Money = policy.price(order)
}
```

<div class="grid cards evidence-contrast" markdown>

-   **Text can suggest**

    A search can find every `PricePolicy` token and rank nearby files. It
    cannot prove which declaration owns `policy` or which overload receives
    `order`.

-   **Compiler evidence can establish**

    The property has one exact compiler identity. The call binds to one exact
    `price` declaration, with a source location and typed relationship between
    both ends.

</div>

That distinction matters most when the answer must cover more than one file.
Kast keeps the workspace, scope, source generation, coverage, and limitations
attached to the evidence.

## Start with the repository question

Each page below has one job. It shows where compiler-grounded evidence changes
the answer and where that evidence stops.

<div class="grid cards" markdown>

-   **What declaration does this actually refer to?**

    Separate exact identity from a matching name.

    [Resolve a declaration](questions/resolve-declaration.md)

-   **What depends on this API?**

    Follow references, callers, implementations, and hierarchy from one
    resolved declaration.

    [Find semantic dependents](questions/dependents.md)

-   **Where can this value flow?**

    Use compiler-visible relationships without claiming runtime provenance.

    [Bound value-flow claims](questions/value-flow.md)

-   **What must change if this contract changes?**

    Turn a declaration change into a bounded semantic impact set.

    [Assess a contract change](questions/contract-change.md)

-   **Did this change reach every semantic dependency?**

    Distinguish no matches from a complete negative answer.

    [Verify semantic coverage](questions/verify-coverage.md)

</div>

## Trust the boundary

A useful Kast answer says whether its evidence is complete, qualified, or
rejected. [When can you trust a Kast answer?](concepts/evidence-boundaries.md)
explains those states. The
[generated semantic operation contract](reference/semantic-operations.md)
states mechanically knowable protocol facts without duplicating them in
authored prose.
