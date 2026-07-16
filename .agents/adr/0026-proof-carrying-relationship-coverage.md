# ADR 0026: Proof-carrying relationship coverage

Status: Accepted

Date: 2026-07-16

This ADR supersedes ADR 0022 only for relationship completeness, cardinality,
and degraded-result evidence. ADR 0022's anchored identity, bounded provider
work, family-specific records, and continuation ownership remain in force. ADR
0025's backend-bound selector handles remain transport for that anchored
identity and do not themselves prove relationship coverage. Issue #393
implements this decision on top of the selector-handle foundation from #392.

## Context

An exact relationship count is a semantic claim about work that did not find
another relationship. In particular, `EXACT 0` says more than an empty list:
it says the selected identity, project scope, source-set scope, index
freshness, semantic backend, and requested relationship family were all
complete. The current contract can construct `EXACT 0` from an empty provider
result without carrying any of those facts. Rust projections accept that value
and `--count` drops the limitations that would let an agent judge it.

Candidate visits, an empty page, and an opaque selector handle are not coverage
proof. A timeout, cancellation, excluded source set, stale index, incomplete
backend, or exhausted budget must not be converted into an exact zero.

## Decision

All compiler relationship results carry one closed coverage value with six
explicit dimensions: `identity`, `projectScope`, `sourceSetScope`,
`indexFreshness`, `backend`, and `requestedFamily`. Coverage also carries a
closed limitation list.

Coverage has three nominal states:

- `COMPLETE` proves all six dimensions complete and has no limitations;
- `RESUMABLE` proves the first five dimensions complete while the requested
  family is still in progress behind an issued continuation; and
- `LIMITED` has at least one non-complete dimension and at least one closed
  limitation explaining it.

Cardinality and coverage travel together in a sealed evidence algebra:

- `Complete` contains only `ResultCardinality.Exact` and `COMPLETE` coverage;
- `Resumable` contains only `ResultCardinality.KnownMinimum` and `RESUMABLE`
  coverage; and
- `Limited` contains only `ResultCardinality.KnownMinimum` and `LIMITED`
  coverage.

Available results may carry only complete or resumable evidence. Degraded,
stale, and invalid continuation results carry limited evidence. There is no
constructor that pairs exact cardinality with resumable or limited coverage.
When a provider has not established a larger lower bound, the honest known
minimum is zero.

The IDEA backend is the coverage authority. It may issue complete or resumable
coverage only when the exact subject is verified, the linked Gradle project
model accounts for admitted modules and source sets inside the canonical
workspace, compiler/index admission is ready, the semantic generation is
stable, and the requested family completed or retained resumable state.
Relationship code reads those model facts directly; it does not reinterpret
the public workspace-file inventory as semantic relationship evidence.

Project and source-set completeness comes from IDEA's persisted external
Gradle graph. Complete coverage requires exact equality between imported and
loaded Gradle module identities, exact equality between imported and IDEA
source roots, and complete accounting for every configured linked root.
Missing, extra, incomplete, or out-of-workspace inventory is limited coverage.
The backend revalidates selector identity, semantic generation, and coverage
inside the same read epoch that commits a final result or continuation. A
change between admission and commit fails closed instead of publishing facts
proved against an older epoch.

Call, implementation, and hierarchy providers already materialize one bounded
complete snapshot before paging. Their continuation store therefore preserves
the snapshot's exact total on every page. References may remain incremental:
an intermediate page is resumable with a known minimum, while the exhausted
page becomes complete and exact. A partial provider search cannot issue or
reissue a continuation because retained paging state is not proof that the
unsearched family remains recoverable. Any partial, stale, excluded,
timed-out, cancelled, backend-incomplete, or budget-limited path becomes a
family-typed degraded or unavailable outcome with limited evidence. Provider
timeouts and provider-originated cancellation are caught at the relationship
boundary and remain distinct from cancellation of the enclosing request.

The Rust boundary requires and validates the closed evidence shape before
projecting an available relationship. Missing coverage, `EXACT` paired with
anything other than complete coverage, or a malformed limited result is an
invalid backend contract. Compact, field-selected, and `--count` projections
retain coverage and limitations; `--count` may omit records and subject detail
but never the facts qualifying the count. JSON and default TOON are encodings
of the same projected value.

## Proof

The implementation uses vertical tests:

1. API construction and serialization prove exact, resumable, and limited
   evidence cannot be interchanged.
2. Server tests prove incomplete backend evidence becomes a degraded result
   with a known minimum and closed limitations for every compiler relationship
   family.
3. Rust projection tests reject proofless exact zero, admit a genuine complete
   zero, and preserve coverage in compact and count views.
4. Backend tests prove incomplete coverage cannot reach an exact page and that
   complete bounded snapshots keep exact cardinality across paging.
5. The installed semantic fixture includes production, test, and test-fixture
   references and proves the real IDEA/Gradle path reports their exact count.

## Consequences

Relationship payloads are intentionally larger by a small fixed coverage
object. In return, exact zero becomes an auditable compiler-backed statement
instead of an inference from missing records. Consumers can distinguish a
genuine zero from a lower bound without knowing provider internals, and compact
or count output no longer erases the qualification on the result.

Any future coverage dimension, coverage state, limitation, exactness rule, or
projection that removes coverage or limitations must supersede this ADR.
