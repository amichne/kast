# How is this code connected?

Once a declaration has an exact selector, Kast can read compiler-visible
relationships without widening the subject back into a name search.

## Read one semantic edge

```console
kast relation read \
  --selector '<exact-selector>' \
  --relation callers \
  --limit 100
```

One-hop reads support references, callers, callees, implementations,
inheritors, overrides, and type uses. The subject stays exact while the result
names the selected relation and its bound.

Use this form when one edge answers the decision. For example, callers answer
which declarations make a compiler-resolved call to this symbol. A matching
token elsewhere does not become a caller.

## Publish the repository graph

Build topology explicitly before following more than one edge:

```console
kast topology build
```

This is the only command that constructs repository topology. It covers every
admitted Kotlin file with K2 facts, then atomically publishes one snapshot for
the exact workspace generation. Repeating the command without a source change
reuses that snapshot. Reads never create or repair it implicitly.

## Follow a bounded graph

```console
kast traversal run \
  --selector '<exact-selector>' \
  --relation callers \
  --maximum-depth 3 \
  --maximum-results 250
```

Traversal repeats one relation with explicit limits for depth and results.
Those limits are part of the question, not an implementation detail. Reaching
one produces a qualified answer rather than an unmarked partial graph.

Traversal reads the eligible SQLite snapshot, including after the indexer
restarts. Missing or stale snapshot evidence rejects with
`TOPOLOGY_BUILD_REQUIRED`; a stale selector remains the distinct
`SELECTOR_STALE` failure. Kast never starts a hidden topology build on either
read path. Required change-planning traversal that reaches a bound is the
distinct `REQUIRED_TRAVERSAL_INCOMPLETE` prerequisite failure.

<div class="kast-boundary" markdown>

Compiler-visible relations describe bindings, hierarchy, and type use in the
admitted source scope. They do not claim runtime call order, reflection,
configuration-driven wiring, or value provenance.

</div>

Treat an empty result as a strong negative only inside the reported scope,
generation, relation, and completeness state. [Trust the evidence](../concepts/evidence-boundaries.md)
shows how to make that judgment.
