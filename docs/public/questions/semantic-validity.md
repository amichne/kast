# Is this code semantically valid?

Compiler diagnostics are useful only when you know which source was analyzed
and whether every file in that scope completed analysis. Kast binds both facts
to the result.

## Name one explicit scope

```console
kast diagnostic check \
  --scope src/main/kotlin/billing/PricePolicy.kt \
  --limit 250
```

The scope is one normalized Kotlin file inside the exact workspace. Kast
rejects paths outside the root and unsupported file kinds before invoking the
compiler adapter.

## Distinguish clean from incomplete

A complete result with no diagnostic facts establishes a clean compiler answer
for that file and generation. A qualified result can still contain useful
diagnostics, but it also records which part of analysis remained incomplete.

That distinction prevents two very different observations from collapsing
into the same empty list:

- the compiler analyzed the whole requested scope and found no diagnostics
- the requested scope did not complete analysis

Diagnostics are bounded by the requested result limit. If the limit or a
provider constraint affects coverage, the qualification remains attached to
the evidence.

## Use diagnostics at the change boundary

`kast diagnostic check` is useful for an independent read. A Kast change has a
separate terminal operation, `change.verify`, that checks the applied change
against its resulting workspace generation. Continue with
[How can I change it safely?](safe-change.md) when the diagnostic question is
part of a write.
