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

<div class="kast-boundary" markdown>

Compiler-visible relations describe bindings, hierarchy, and type use in the
admitted source scope. They do not claim runtime call order, reflection,
configuration-driven wiring, or value provenance.

</div>

Treat an empty result as a strong negative only inside the reported scope,
generation, relation, and completeness state. [Trust the evidence](../concepts/evidence-boundaries.md)
shows how to make that judgment.
