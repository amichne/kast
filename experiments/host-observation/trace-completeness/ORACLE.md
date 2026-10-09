# Independent r1 graph oracle

`verify.py` derives the expected result from frozen authored declarations and the
TRACE fanout rule in [QueryTraceTasks.kt](../../../query/service/src/main/kotlin/io/github/amichne/kast/query/service/QueryTraceTasks.kt). Baseline output does
not define the candidate's expected rows. The oracle compares every presented
page, including retained windows, against exact named sets and source ranges.

## A: Attributes

The seed expands to the two implementations, their declared members, the
interface members, and authored references and callers in main and test sources.
The exact set contains 31 named declarations and the local `previous` property:
32 rows. The local property is referenced inside its owning implementation and
must remain in the broad TRACE set.

The library callback cases must carry K2's declared `EXACTLY_ONCE` contract for
the actual resolved `kotlin.also` parameter. Its target class digest, source call
occurrence, lexical owner, parameter position and read basis remain evidence.
The dependency does not become an authored TRACE node, source handle or invented
body invocation. The source helper remains compiler-proven body evidence.

## B: FlowCollector

The broad TRACE set contains 9 named declarations plus two anonymous objects
and their two `emit` overrides: 13 rows. IMPLEMENTATIONS has `NamedCollector` and
both objects; OVERRIDES has its named `emit` and both anonymous overrides.

The four outer consumers (`ordinaryFlow`, `inferredFlow`, `testFlow` and
`secondFlow`) are callers of `collectIndexed`/`collectSecond`, which this TRACE
reaches as callers of `Flow.collect`. The USE phase ends after that caller hop.
The compiler-owned superclass reference belongs to the anonymous object; lexical
containment cannot replace it with a named function reference to add another hop.
An initial 17-row draft incorrectly counted those four callers of callers. This
source-and-phase review corrects that draft; the request and fanout rule stay
unchanged. The baseline and candidate each contain 13 rows with different sets.

The objects have source ranges 259–411 and 598–750 in `Collect.kt`. Their actual
compiler owners have ranges 126–412 and 414–751. The second owner's leading
authored comment belongs to its PSI range. Override ranges are 325–405 and
664–744. All offsets are UTF-16 and all ends are exclusive. The fixture includes
a supplementary Unicode marker to distinguish UTF-16 offsets from code points.

Each anonymous object retains the actual compiler supertype
`fixture/flow/FlowCollector<T>`. Its member's owner identity must equal that
object's detached compiler identity. Equal-shaped objects in different owners
remain distinct. A display label cannot supply qualified identity or membership.

## Controls and interpretation

Stored callbacks still reject with `PARAMETER_ESCAPES` and
`NO_INVOCATION_PROVEN`; the stored sink cannot appear as an invoked callee.
The authored conditional helper retains only its direct `also` callee under this
static query. The same-spelled helper cannot inherit a library contract.
The Spring repository interface has no authored implementation in this fixture.

Named caller traversal through an anonymous callback still rejects with
`NESTED_CALLBACK_EXECUTION`. Lexical containment does not prove runtime
activation. This control is intentionally separate from B's complete static
declaration graph. Rejection evidence is readable without becoming a complete
answer. Full upstream coverage, runtime activation and enterprise latency are
outside this suite's claim.
