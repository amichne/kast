# Native flow closure: independent oracle review

The initial synthetic oracles need stronger assertions before CP1 can pass.
This separate review examines the revision-3 specification and the initial `NativeFlowClosureTest` draft.
It does not establish a compiler result or an enterprise reproduction.

The source base is `b43a9ebc720d4aeaa5267a7d9c6cddb4f1d66ad1`.
The initial fixture digest is `d9a84806951a1838142d54482c419f50136d084ef0a5a857db2c2b41a66d7635`.
The review uses no enterprise source or private run identity.
Every reported case keeps enterprise status `REQUIRED / UNMAPPED / PENDING` until an authorized replay supplies its mapping.

## Sound starting oracles

- Named and anonymous callers differ by the synchronous source wrapper.
- Concrete and interface receivers use distinct source declarations.
- Outer `origin` and inner `convert` calls supply distinct producer anchors.
- The constant-call pair uses the upstream input and call result as separate seeds.
- Regular escapes, raw backslashes, and interpolated dollar literals use distinct lexical forms.
- Two reads use one fixture basis. The comparison includes frames, field paths, conditions, and work counts.

These source-derived expectations remain planned until their named assertions run.
The identity conversion isolates execution ownership. It does not reproduce an unknown enterprise transformation.

## Necessary oracle repairs

| Gap in the initial draft | Necessary assertion or disposition |
| --- | --- |
| A rejected seed aborts the first run | Capture both seed results before an assertion stops the case. Compare the finite rejection and its evidence. |
| Positive arrival alone accepts unresolved reads | Assert the fixed supported prefix and its qualified terminal. Reject unexpected resource caps and unexplained obligations. |
| Negative arrival alone accepts unsupported traversal | Prove callback entry or discarded input at the responsible owner. Assert the expected terminal or exact unresolved frontier. |
| Generic helper coverage starts before the helper | Add the helper-result seed. Prove RETURN and examine the first downstream failure separately. |
| I3 uses separate known implementations | Add one call with both admissible implementation alternatives. Forbid provenance from the discarded alternative. Keep each qualification. |
| Repeated entries only assert arrival | Assert two exact contribution occurrences and separate contribution counts from requested-site counts. |
| `String?` content only asserts arrival | Assert non-null contribution and the explained constant null construction, with their distinct conditions. |
| Getter coverage lacks the result seed | Add the getter-result positive. Keep the discarded-input negative. |
| Conversion coverage is absent | Add source conversion, discarded-input/result pairs, and an exact unresolved conversion frontier. Preserve independently supported synthesis. |
| Traversal ignores reviewed transfers | Traverse or explicitly account for reviewed alternatives. Do not silently drop acquired evidence. |
| Projection compares traversal order | Compare routes without enumeration order. Keep order within each witness, duplicate counts, basis, and proof differences. |
| Header and scope cases are absent | Freeze HRT-1, HBP-1, SCP-1, and SCP-2 separately. Keep FPD-1 pending until its failed stage is known. |

The current draft covers useful candidates, but it does not satisfy the entire control matrix.
Existing tests can supply unchanged obligations after their current assertions run.
Historical test source alone supplies no executed pass.

## D1 decision limits

`ModelValuePosition` exposes `Argument` and `Result`.
`ReviewedIterableMapCallable` admits the exact pinned `Iterable.map` callback contract.
Neither contract supplies a generic Pair/Map construction rule.
`QueryImpactReviewedConservationTest` also forbids a reviewed transfer from silently discharging `UNMODELED_CALL`.

The native route must still establish the actual selected `to` and `mapOf` declarations, overloads, and dependency identities.
A missing registry entry cannot erase independent source or compiler proof.
If the first unsupported join needs a new construction mechanism, CP0 selects the approved `QUALIFIED_LIBRARY_BOUNDARY` target.
That target needs the original token's supported prefix and the exact library frontier.
The header argument remains unproved from that seed.
HBP-1 cannot replace HRT-1.

No Pair/Map rule means X-33 through X-35 are inapplicable only to new-rule obligations.
Their disposition cannot remove an original reported case or waive enterprise replay.
A new D1 trigger after CP0 blocks that join until the owner supplies a decision.

## Review result

Independent source review: **complete, with unresolved oracle gaps**.
Compiler/native assertions: **NOT RUN by this reviewer**.
Enterprise replay: **NOT RUN by this reviewer**.
CP0 route packet: **PENDING exact native callable evidence**.
CP1: **OPEN** until the repaired oracles and B0/B1 evidence support its predicates.
