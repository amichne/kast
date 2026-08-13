# Protocol continuation module guide

`:protocol:continuation` owns bounded host-neutral continuation state. It stores only immutable
detached records and typed binding evidence; it does not own transport, persistence, IntelliJ
objects, native query execution, public operation routing, or runtime composition.

## Module map

- `ContinuationBinding.kt` owns canonical-root/generation, normalized-request, scope, ordering,
  and resource-owner identity.
- `ContinuationToken.kt` owns copyable opaque token parsing and issuance.
- `ContinuationLimits.kt` owns token, cached-record, cached-byte, page, and absolute-TTL limits.
- `DetachedContinuationRecord.kt` owns immutable canonical detached payloads and exact byte counts.
- `ContinuationPage.kt` owns exact resume positions, terminal pages, reissued pages, and closed
  issue/access/invalidation outcomes.
- `DetachedContinuationStore.kt` owns synchronized issue, single-use resume/reissue, mismatch and
  expiry invalidation, cancellation cleanup, and finite resource accounting.
- `DetachedContinuationStoreState.kt` contains only the store's closed internal admission,
  lifecycle, selection, and failure-translation states.

## Dependency boundary

- Production depends only on `:kernel` and `:workspace:contract` as declared by the canonical
  architecture policy.
- Never import IntelliJ, PSI, VFS, search scopes, Gradle, JDBC, filesystem, process, transport,
  JSON-RPC, legacy `analysis-api`, backend, handler, closure, or service-locator types.
- A continuation contains detached records or a detached resumable descriptor only. It never owns
  a live host object or callback.

## Continuation invariants

- Every entry binds one `SemanticReadLease`, normalized request fingerprint, scope fingerprint,
  order fingerprint, resource owner, exact resume position, and non-renewing absolute TTL.
- Tokens are opaque and safe to copy verbatim. A successful page consumes its token; a nonterminal
  page receives a fresh token for the next exact position.
- Wrong root, moved generation, changed request/scope/order/owner, expiry, cancellation, and a page
  that cannot make progress invalidate and release the claimed entry. Never return a stale page.
- Enforce total token, cached-record, and cached-byte limits before publication. Reissue preserves
  ownership without increasing totals; terminal completion, invalidation, expiry, cancellation,
  and close release totals exactly once.
- Preserve record order exactly. A continuation never sorts, filters, re-resolves, refreshes, or
  extends native results; operation adapters own any lazy native query descriptor.

## Verification ladder

1. Run `./gradlew :protocol:continuation:test --tests '*DetachedContinuationStoreTest'`.
2. Run `./gradlew :protocol:continuation:test`.
3. Run `./gradlew verifyKastArchitecture --configuration-cache`.
4. Run direct operation consumers when they adopt this service.
