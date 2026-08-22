---
hide:
  - toc
---

<span class="kast-eyebrow">Kotlin semantic evidence</span>

# Kast

<div class="kast-hero" markdown>

Kast gives engineers and coding agents a repo-local command boundary for
questions whose answers depend on compiler identity, not a convincing reading
of source text.

It keeps the repository root, evidence generation, scope, limits, and outcome
attached to the answer.

</div>

## The compiler sees more than text

Consider a checkout service that imports one of several declarations named
`PricePolicy`:

```kotlin
import billing.PricePolicy as BillingPricePolicy

class Checkout(private val policy: BillingPricePolicy) {
    fun total(order: Order): Money = policy.price(order)
}
```

<div class="kast-grid evidence-contrast" markdown>

<div class="kast-card kast-tone-muted" markdown>

**Text can suggest**

A search can find `PricePolicy`, `policy`, and `price`. It cannot establish
which declaration the alias names or which overload receives `order`.

</div>

<div class="kast-card kast-tone-evidence" markdown>

**Compiler evidence can establish**

The property and call each have an exact compiler identity. Their source
locations and semantic relationship remain part of the result.

</div>

</div>

The distinction becomes important when an answer crosses files, modules, or a
change boundary. Kast makes the strength and limits of that answer visible.

## Start from the repository root

```console
cd /path/to/kotlin-repository
kast start
```

`kast start` starts or reuses one isolated indexer for that exact root. It
returns when semantic evidence is ready, or returns a typed blocker instead of
manufacturing readiness.

[Set up and start Kast](start.md)

## Ask a repository question

Each route begins with the decision you need to make. The command details stay
secondary to the evidence gained.

<div class="kast-grid kast-question-grid" markdown>

<div class="kast-card kast-tone-discovery" markdown>

<span class="kast-card-label">Workspace</span>

### What is Kast ready to inspect?

See the exact root, semantic generation, and any readiness limit before relying
on later answers.

[Inspect readiness](questions/workspace-readiness.md)

</div>

<div class="kast-card kast-tone-identity" markdown>

<span class="kast-card-label">Identity</span>

### What declaration is this?

Move from a bounded candidate to an exact symbol, then ask for its compiler
description.

[Establish identity](questions/declaration-identity.md)

</div>

<div class="kast-card kast-tone-discovery" markdown>

<span class="kast-card-label">Relationships</span>

### How is this code connected?

Read one semantic edge or follow a relation with explicit depth and result
limits.

[Read connections](questions/code-connections.md)

</div>

<div class="kast-card kast-tone-evidence" markdown>

<span class="kast-card-label">Diagnostics</span>

### Is this code semantically valid?

Check compiler diagnostics for one named Kotlin file and keep incomplete
coverage visible.

[Check a scope](questions/semantic-validity.md)

</div>

<div class="kast-card kast-tone-effect" markdown>

<span class="kast-card-label">Change</span>

### How can I change it safely?

Separate intent, admission, physical application, semantic verification, and
recovery.

[Follow the change boundary](questions/safe-change.md)

</div>

</div>

## Evidence keeps its boundary

<div class="kast-boundary" markdown>

**Complete** means the operation met its declared scope and completeness
contract. **Qualified** keeps usable evidence and names its limits.
**Rejected** returns no evidence as success.

</div>

Every successful semantic payload names the operation and evidence generation
that produced it. A moved generation, unsupported scope, exhausted bound, or
missing capability stays visible at the boundary.

[Read how to judge an answer](concepts/evidence-boundaries.md) ·
[See how Kast works](explanation/how-kast-works.md)
