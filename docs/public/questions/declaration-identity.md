# What declaration is this?

Names are useful for finding possibilities. They are not stable identities for
aliases, overloads, generated declarations, or two modules that use the same
simple name.

Kast makes identity a visible refinement:

<div class="kast-proof-chain" aria-label="Declaration identity refinement">
  <span class="kast-step kast-tone-discovery">query</span>
  <span class="kast-arrow" aria-hidden="true">→</span>
  <span class="kast-step kast-tone-discovery">candidate selector</span>
  <span class="kast-arrow" aria-hidden="true">→</span>
  <span class="kast-step kast-tone-identity">exact selector</span>
  <span class="kast-arrow" aria-hidden="true">→</span>
  <span class="kast-step kast-tone-evidence">description</span>
</div>

## Discover without overclaiming

```console
kast symbol discover --mode name --query PricePolicy --limit 25
```

Discovery can search by name, source location, file structure, or bounded text.
Its results are candidates because the query may intentionally match more than
one declaration. Each candidate carries enough context for the next boundary.

This is the right place to widen or narrow a search. It is not the right place
to infer exact ownership from display text.

## Resolve one candidate

Select the `candidateSelector` from the intended discovery item:

```console
kast symbol resolve --candidate '<candidate-selector>'
```

Resolution succeeds only when that candidate still identifies one symbol in
the same root, scope, and semantic generation. Its `exactSelector` is a
capability for exact symbol operations, not another search string.

## Describe the exact symbol

```console
kast symbol describe --selector '<exact-selector>'
```

The description is compiler-grounded and tied to the selector that requested
it. If the declaration moved with a newer generation, Kast closes the request
instead of resolving a convenient current match.

Keep the exact selector for [semantic connections](code-connections.md) or a
[planned change](safe-change.md). Rediscover after the workspace generation
moves.
