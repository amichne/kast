package io.github.amichne.kast.query.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.BoundaryModel
import io.github.amichne.kast.relation.contract.RelationSearchBoundary
import io.github.amichne.kast.relation.contract.RepresentationRule
import io.github.amichne.kast.relation.contract.ValueFlowObligation
import io.github.amichne.kast.relation.contract.ValueFlowStep
import io.github.amichne.kast.relation.contract.ValueFlowTerminal
import io.github.amichne.kast.relation.contract.ValueFlowUnsupportedCause
import io.github.amichne.kast.relation.contract.ValueSite

/** Pure conservation checks for the sole ledger constructor, preserving the first rejected invariant. */
internal class QueryImpactLedgerValidation(
    private val seeds: List<ValueSite>,
    private val producers: List<QueryImpactProducer>,
    private val domain: RelationSearchBoundary,
    private val representations: List<RepresentationRule>,
    private val boundaries: List<BoundaryModel>,
    observations: List<ValueFlowStep>,
    private val paths: List<QueryImpactPath>,
    rejections: List<QueryImpactReadRejection>,
    private val peers: List<QueryImpactPeerBoundary>,
) {
    private val native = observations.groupBy { it.source }
    private val rejected = rejections.groupBy { it.source }

    fun validate(): Refinement<Unit, QueryImpactLedgerFailure> {
        for (check in listOf(::origins, ::models, ::nativeDomain, ::observedPaths, ::modeledBranches, ::unaccounted)) {
            when (val result = check()) {
                is Refinement.Refined -> Unit
                is Refinement.Rejected -> return result
            }
        }
        return Refinement.Refined(Unit)
    }

    private fun origins(): Refinement<Unit, QueryImpactLedgerFailure> {
        if (seeds.isEmpty()) return reject(QueryImpactLedgerFailure.EMPTY_SEEDS)
        if (seeds.distinct().size != seeds.size) return reject(QueryImpactLedgerFailure.DUPLICATE_SEED)
        if (producers.isNotEmpty() && producers.map { it.site } != seeds)
            return reject(QueryImpactLedgerFailure.PRODUCER_IDENTITY_MISMATCH)
        if (paths.distinct().size != paths.size) return reject(QueryImpactLedgerFailure.DUPLICATE_PATH)
        if (paths.any { it.producer !in seeds }) return reject(QueryImpactLedgerFailure.FOREIGN_PRODUCER)
        if (seeds.any { seed -> paths.none { it.producer == seed } })
            return reject(QueryImpactLedgerFailure.MISSING_SEED_PATH)
        return Refinement.Refined(Unit)
    }

    private fun models(): Refinement<Unit, QueryImpactLedgerFailure> {
        val references = representations.map { it.reference } + boundaries.map { it.reference }
        if (references.distinct().size != references.size)
            return reject(QueryImpactLedgerFailure.DUPLICATE_MODEL_REFERENCE)
        if (peers.isNotEmpty()) {
            when (val admitted = admitPeerBoundaries(seeds.first().enclosing.lease, boundaries, peers)) {
                is Refinement.Refined -> Unit
                is Refinement.Rejected ->
                    return reject(
                        when (admitted.failure) {
                            QueryImpactPeerDeclarationFailure.DUPLICATE_BOUNDARY ->
                                QueryImpactLedgerFailure.DUPLICATE_MODEL_REFERENCE
                            QueryImpactPeerDeclarationFailure.UNDECLARED_BOUNDARY ->
                                QueryImpactLedgerFailure.UNDECLARED_MODEL
                            QueryImpactPeerDeclarationFailure.FOREIGN_BASIS ->
                                QueryImpactLedgerFailure.FOREIGN_PEER_BASIS
                        }
                    )
            }
        }
        return Refinement.Refined(Unit)
    }

    private fun nativeDomain(): Refinement<Unit, QueryImpactLedgerFailure> {
        if (peers.isNotEmpty() && native.values.flatten().any { it.hasForeignBasis(seeds.first().enclosing.lease) })
            return reject(QueryImpactLedgerFailure.FOREIGN_PEER_BASIS)
        if (
            native.values.flatten().any { it.domain.boundary != domain } ||
                rejected.values.flatten().any { it.domain != domain }
        )
            return reject(QueryImpactLedgerFailure.DOMAIN_MISMATCH)
        if (
            native.values.any { it.size != 1 } ||
                rejected.values.any { it.size != 1 } ||
                native.keys.any(::hasConflictingRejection)
        )
            return reject(QueryImpactLedgerFailure.CONFLICTING_OBSERVATIONS)
        return Refinement.Refined(Unit)
    }

    private fun hasConflictingRejection(site: ValueSite): Boolean =
        site in rejected && native.getValue(site).singleOrNull()?.terminal != ValueFlowTerminal.ResourceSuspended

    private fun observedPaths(): Refinement<Unit, QueryImpactLedgerFailure> {
        for (path in paths) {
            if (!path.usesDeclaredModels(representations, boundaries))
                return reject(QueryImpactLedgerFailure.UNDECLARED_MODEL)
            for (index in 0..path.steps.size) {
                when (val result = position(path, index)) {
                    is Refinement.Refined -> Unit
                    is Refinement.Rejected -> return result
                }
            }
        }
        return Refinement.Refined(Unit)
    }

    private fun position(path: QueryImpactPath, index: Int): Refinement<Unit, QueryImpactLedgerFailure> =
        when (val admitted = witness(path, index)) {
            is Refinement.Rejected -> admitted
            is Refinement.Refined ->
                when (val proof = admitted.value) {
                    PositionWitness.Accounted -> Refinement.Refined(Unit)
                    is PositionWitness.Native -> branches(path, index, proof.observation)
                }
        }

    private sealed interface PositionWitness {
        data object Accounted : PositionWitness

        data class Native(val observation: ValueFlowStep) : PositionWitness
    }

    private fun witness(path: QueryImpactPath, index: Int): Refinement<PositionWitness, QueryImpactLedgerFailure> {
        val source = if (index == 0) path.producer else path.steps[index - 1].target
        if (index == path.steps.size) {
            when (val end = path.terminal) {
                is QueryImpactTerminal.Unresolved.ExecutionStop -> return accounted()
                is QueryImpactTerminal.Unresolved.ReadRejected ->
                    return if (end.rejection in rejected[source].orEmpty()) accounted()
                    else reject(QueryImpactLedgerFailure.UNPROVEN_TERMINAL)
                is QueryImpactTerminal.ExplicitScopeExclusion -> return exclusion(source, end)
                is QueryImpactTerminal.Unresolved.PeerContinuation -> return peerWitness(path, source, end)
                is QueryImpactTerminal.Consumer,
                is QueryImpactTerminal.ModeledTerminal,
                is QueryImpactTerminal.Unresolved.Flow,
                is QueryImpactTerminal.Unresolved.Boundary,
                is QueryImpactTerminal.SupportedDomainEnd -> Unit
            }
        }
        val observed =
            native[source]?.singleOrNull() ?: return reject(QueryImpactLedgerFailure.MISSING_NATIVE_OBSERVATION)
        return Refinement.Refined(PositionWitness.Native(observed))
    }

    private fun peerWitness(
        path: QueryImpactPath,
        source: ValueSite,
        end: QueryImpactTerminal.Unresolved.PeerContinuation,
    ): Refinement<PositionWitness, QueryImpactLedgerFailure> {
        if (end.boundary !in peers || path.hasForeignBasis(seeds.first().enclosing.lease))
            return reject(QueryImpactLedgerFailure.UNPROVEN_TERMINAL)
        if (source in native || source in rejected) return reject(QueryImpactLedgerFailure.CONFLICTING_OBSERVATIONS)
        return accounted()
    }

    private fun exclusion(
        source: ValueSite,
        end: QueryImpactTerminal.ExplicitScopeExclusion,
    ): Refinement<PositionWitness, QueryImpactLedgerFailure> =
        when {
            domain != end.exclusion.domain -> reject(QueryImpactLedgerFailure.DOMAIN_MISMATCH)
            source in native || source in rejected -> reject(QueryImpactLedgerFailure.CONFLICTING_OBSERVATIONS)
            else -> accounted()
        }

    private fun branches(
        path: QueryImpactPath,
        index: Int,
        observed: ValueFlowStep,
    ): Refinement<Unit, QueryImpactLedgerFailure> {
        val siblings = paths.filter { it.sameModeledPrefix(path, index) }
        val step = path.steps.getOrNull(index)
        if (step is QueryImpactStep.Compiler && step.transfer !in observed.transfers)
            return reject(QueryImpactLedgerFailure.UNPROVEN_COMPILER_STEP)
        if (
            observed.transfers.any { edge ->
                siblings.none { it.steps.getOrNull(index) == QueryImpactStep.Compiler(edge) }
            }
        )
            return reject(QueryImpactLedgerFailure.MISSING_BRANCH)
        if (observed.obligations.any { !obligationRetained(it, observed.source, siblings, index) })
            return reject(QueryImpactLedgerFailure.MISSING_OBLIGATION)
        if (index == path.steps.size && !path.terminal.isEstablishedBy(observed))
            return reject(QueryImpactLedgerFailure.UNPROVEN_TERMINAL)
        return Refinement.Refined(Unit)
    }

    private fun obligationRetained(
        obligation: ValueFlowObligation,
        source: ValueSite,
        siblings: List<QueryImpactPath>,
        index: Int,
    ): Boolean {
        val retained = siblings.any {
            it.steps.size == index && (it.terminal as? QueryImpactTerminal.Unresolved.Flow)?.obligation == obligation
        }
        val modeled =
            obligation.site == source &&
                obligation.cause == ValueFlowUnsupportedCause.UNMODELED_CALL &&
                siblings.any { it.hasModeledArrival(index) }
        return retained || modeled
    }

    private fun modeledBranches(): Refinement<Unit, QueryImpactLedgerFailure> =
        QueryImpactLedgerModelConservation(producers, representations, boundaries, paths).validate()

    private fun unaccounted(): Refinement<Unit, QueryImpactLedgerFailure> {
        val visited = paths.flatMap { path -> listOf(path.producer) + path.steps.map { it.target } }.toSet()
        return if (native.keys.any { it !in visited } || rejected.keys.any { it !in visited })
            reject(QueryImpactLedgerFailure.UNACCOUNTED_NATIVE_READ)
        else Refinement.Refined(Unit)
    }

    private fun accounted(): Refinement<PositionWitness, QueryImpactLedgerFailure> =
        Refinement.Refined(PositionWitness.Accounted)
}

private fun reject(cause: QueryImpactLedgerFailure): Refinement.Rejected<QueryImpactLedgerFailure> =
    Refinement.Rejected(cause)
