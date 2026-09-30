package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.Refinement
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Pure admission carries the exact store transition to the single mutation owner. */
internal sealed interface QueryPublicationTransition {
    data object Initial : QueryPublicationTransition

    data object Replay : QueryPublicationTransition

    data class Producer(val key: QueryStateKey, val entry: QueryStateEntry.Published) : QueryPublicationTransition
}

internal fun Map<QueryStateKey, QueryStateEntry>.admitPublication(
    claim: QueryExecutionClaim,
    page: QueryPublishedPage,
    charge: QueryPublicationPageCharge,
    transientClaims: Map<UUID, QueryExecutionClaim>,
    now: Long,
    ttlMillis: Long,
): Refinement<QueryPublicationTransition, QueryPublicationFailure> {
    val authority =
        when (val admitted = publicationAuthority(claim)) {
            is Refinement.Refined -> admitted.value
            is Refinement.Rejected -> return admitted
        }
    if (!page.matchesAuthority(authority)) return publicationRejected(QueryPublicationFailure.PUBLISHED_PAGE_MISMATCH)
    val inputs =
        when (val origin = claim.origin) {
            is QueryExecutionOrigin.Initial -> origin.inputs
            is QueryExecutionOrigin.Producer -> this[origin.token.key()]?.dependencies().orEmpty()
            is QueryExecutionOrigin.Replay -> this[origin.token.key()]?.dependencies().orEmpty()
        }
    val closure = dependencyClosure(page.dependencies() + inputs)
    val transition =
        when (val origin = claim.origin) {
            is QueryExecutionOrigin.Initial ->
                admitInitial(claim, origin, transientClaims, PublicationWindow(now, ttlMillis))
            is QueryExecutionOrigin.Replay ->
                admitReplay(claim, origin, page, transientClaims, PublicationWindow(now, ttlMillis))
            is QueryExecutionOrigin.Producer ->
                admitProducer(
                    claim,
                    origin,
                    PublicationProposal(page, charge),
                    closure,
                    PublicationWindow(now, ttlMillis),
                )
        }
    val accepted =
        when (transition) {
            is Refinement.Refined -> transition.value
            is Refinement.Rejected -> return transition
        }
    if (
        closure.any { key ->
            val entry = this[key]
            !entry.admitsDependency(authority, claim) ||
                accepted != QueryPublicationTransition.Replay && entry.hasActiveProducer()
        }
    )
        return publicationRejected(QueryPublicationFailure.DEPENDENCY_UNAVAILABLE)
    if (closure.any { key -> this[key]?.withinLifetime(now, ttlMillis) == false })
        return publicationRejected(QueryPublicationFailure.EXPIRED)
    return Refinement.Refined(accepted)
}

private fun Map<QueryStateKey, QueryStateEntry>.publicationAuthority(
    claim: QueryExecutionClaim
): Refinement<io.github.amichne.kast.workspace.contract.SemanticReadAuthority, QueryPublicationFailure> {
    val authority =
        when (val origin = claim.origin) {
            is QueryExecutionOrigin.Initial -> origin.lease
            is QueryExecutionOrigin.Producer -> this[origin.token.key()]?.lease
            is QueryExecutionOrigin.Replay -> this[origin.token.key()]?.lease
        }
    return if (authority == null) publicationRejected(QueryPublicationFailure.CLAIM_UNAVAILABLE)
    else Refinement.Refined(authority)
}

private fun admitInitial(
    claim: QueryExecutionClaim,
    origin: QueryExecutionOrigin.Initial,
    transientClaims: Map<UUID, QueryExecutionClaim>,
    window: PublicationWindow,
): Refinement<QueryPublicationTransition, QueryPublicationFailure> =
    when {
        transientClaims[claim.identity] !== claim -> publicationRejected(QueryPublicationFailure.CLAIM_UNAVAILABLE)
        window.now - origin.createdAt > TimeUnit.MILLISECONDS.toNanos(window.ttlMillis) ->
            publicationRejected(QueryPublicationFailure.EXPIRED)
        else -> Refinement.Refined(QueryPublicationTransition.Initial)
    }

private fun Map<QueryStateKey, QueryStateEntry>.admitReplay(
    claim: QueryExecutionClaim,
    origin: QueryExecutionOrigin.Replay,
    page: QueryPublishedPage,
    transientClaims: Map<UUID, QueryExecutionClaim>,
    window: PublicationWindow,
): Refinement<QueryPublicationTransition, QueryPublicationFailure> {
    if (transientClaims[claim.identity] !== claim) return publicationRejected(QueryPublicationFailure.CLAIM_UNAVAILABLE)
    val published =
        this[origin.token.key()] as? QueryStateEntry.Published
            ?: return publicationRejected(QueryPublicationFailure.CLAIM_UNAVAILABLE)
    if (origin.page != page || published.page != page)
        return publicationRejected(QueryPublicationFailure.PUBLISHED_PAGE_MISMATCH)
    return if (!published.withinLifetime(window.now, window.ttlMillis))
        publicationRejected(QueryPublicationFailure.EXPIRED)
    else Refinement.Refined(QueryPublicationTransition.Replay)
}

private fun Map<QueryStateKey, QueryStateEntry>.admitProducer(
    claim: QueryExecutionClaim,
    origin: QueryExecutionOrigin.Producer,
    proposal: PublicationProposal,
    closure: Set<QueryStateKey>,
    window: PublicationWindow,
): Refinement<QueryPublicationTransition, QueryPublicationFailure> {
    val page = proposal.page
    val charge = proposal.charge
    val key = origin.token.key()
    val entry =
        this[key] as? QueryStateEntry.Pending ?: return publicationRejected(QueryPublicationFailure.CLAIM_UNAVAILABLE)
    val running =
        entry.execution as? QueryCheckpointExecution.Running
            ?: return publicationRejected(QueryPublicationFailure.CLAIM_UNAVAILABLE)
    if (running.claim !== claim) return publicationRejected(QueryPublicationFailure.CLAIM_UNAVAILABLE)
    if (!entry.withinLifetime(window.now, window.ttlMillis)) return publicationRejected(QueryPublicationFailure.EXPIRED)
    if (key in closure) return publicationRejected(QueryPublicationFailure.NON_ADVANCING_SUCCESSOR)
    val pageBytes =
        when (val admitted = charge.admittedBytes(page, running.reservedBytes)) {
            is Refinement.Refined -> admitted.value
            is Refinement.Rejected -> return admitted
        }
    val bytes = entry.request.accountedRequestBytes().saturatedAdd(pageBytes)
    if (bytes > entry.bytes) return publicationRejected(QueryPublicationFailure.PUBLISHED_PAGE_MISMATCH)
    return Refinement.Refined(
        QueryPublicationTransition.Producer(
            key,
            QueryStateEntry.Published(
                entry.request,
                entry.lease,
                page,
                java.util.Set.copyOf(page.dependencies()),
                entry.createdAt,
                bytes,
            ),
        )
    )
}

private fun QueryPublicationPageCharge.admittedBytes(
    page: QueryPublishedPage,
    reservation: Long,
): Refinement<Long, QueryPublicationFailure> =
    when (this) {
        QueryPublicationPageCharge.Reserved -> Refinement.Refined(reservation)
        is QueryPublicationPageCharge.Encoded ->
            if (this.page != page) publicationRejected(QueryPublicationFailure.PUBLISHED_PAGE_MISMATCH)
            else
                Refinement.Refined(
                    encodedBytes.value
                        .saturatedMultiply(RETAINED_ENCODING_MULTIPLIER)
                        .saturatedAdd(PUBLISHED_PAGE_STRUCTURE_BYTES)
                )
    }

internal fun QueryStateEntry?.admitsDependency(
    authority: io.github.amichne.kast.workspace.contract.SemanticReadAuthority,
    claim: QueryExecutionClaim,
): Boolean {
    if (this == null || lease != authority) return false
    if (owner != null && owner !== claim) return false
    return when (this) {
        is QueryStateEntry.Output -> page.matchesAuthority(authority)
        is QueryStateEntry.Published -> page.matchesAuthority(authority)
        is QueryStateEntry.Checkpoint,
        is QueryStateEntry.Result -> true
    }
}

private const val PUBLISHED_PAGE_STRUCTURE_BYTES = 1_024L

internal fun QueryStateEntry.publishOwned(claim: QueryExecutionClaim): QueryStateEntry =
    if (owner !== claim) this
    else
        when (this) {
            is QueryStateEntry.Checkpoint -> copy(owner = null)
            is QueryStateEntry.Result -> copy(owner = null)
            is QueryStateEntry.Output -> copy(owner = null)
            is QueryStateEntry.Published -> this
        }

private fun publicationRejected(failure: QueryPublicationFailure) = Refinement.Rejected(failure)

private data class PublicationWindow(val now: Long, val ttlMillis: Long)

private data class PublicationProposal(val page: QueryPublishedPage, val charge: QueryPublicationPageCharge)
