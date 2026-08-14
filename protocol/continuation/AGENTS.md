# Protocol continuation module guide

`:protocol:continuation` owns bounded host-neutral continuation and registered long-operation
state. It stores only immutable detached records, typed binding evidence, closed lifecycle state,
and store-owned timer signals; it does not own transport, persistence, IntelliJ objects, native
query execution, public operation routing, operation executors, or runtime composition.

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
- `LongOperationIdentity.kt` owns copyable operation IDs and exact root, requester, runtime-epoch,
  declared-capability, and input identities.
- `LongOperationProtocol.kt` owns capacity/deadline/retention policy, scheduler capability,
  cancellation policy, detached terminal results, and closed operation outcomes.
- `RegisteredLongOperationStore.kt` owns synchronized registration, exact binding admission,
  deadline transition, poll, completion, cancellation, terminal replay, retention, and cleanup.

## Dependency boundary

- Production depends only on `:kernel` and `:workspace:contract` as declared by the canonical
  architecture policy.
- Never import IntelliJ, PSI, VFS, search scopes, Gradle, JDBC, filesystem, process, transport,
  JSON-RPC, legacy `analysis-api`, backend, handler, closure, or service-locator types.
- A continuation contains detached records or a detached resumable descriptor only. A registered
  operation contains no worker, future, approval, PSI, database transaction, or live host object.
  Its scheduler may retain only the store-owned ID/key expiry signal required for passive cleanup.

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

## Registered-operation invariants

- Arm the absolute server deadline before publishing an operation ID. Binding includes the exact
  canonical root, requester, runtime epoch, declared capability, and complete input identity.
- The request handler never owns the operation lifetime. An external executor completes by ID;
  request disconnect, poll frequency, and poll absence cannot cancel, renew, or extend work.
- Running, terminal success, and terminal failure are closed states. Deadline, cancellation, and
  execution failure remain typed and replayable until the fixed retention interval ends.
- Capacity bounds running and retained terminal entries together. Deadline and retention timers
  clean passively; polling is observation only.
- Every timer callback is bound to the entry's unforgeable registration key. Expiry, poll cleanup,
  close, and stale callbacks release an entry at most once and cannot remove a reused ID.

## Verification ladder

1. Run the focused store test: `*DetachedContinuationStoreTest` for paging or
   `*RegisteredLongOperationStoreTest` for registered operations.
2. Run `./gradlew :protocol:continuation:test`.
3. Run `./gradlew verifyKastArchitecture --configuration-cache`.
4. Run direct operation consumers when they adopt this service.
