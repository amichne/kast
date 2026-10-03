package io.github.amichne.kast.query.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.BoundaryModel
import io.github.amichne.kast.relation.contract.ConsumerRepresentationEvidence
import io.github.amichne.kast.relation.contract.RelationSearchBoundary
import io.github.amichne.kast.relation.contract.RepresentationCurrent
import io.github.amichne.kast.relation.contract.RepresentationHistory
import io.github.amichne.kast.relation.contract.RepresentationModelApplication
import io.github.amichne.kast.relation.contract.RepresentationRule
import io.github.amichne.kast.relation.contract.ValueFlowStep
import io.github.amichne.kast.relation.contract.ValueSite
import java.util.Collections

/** The versioned supported compiler transfer domain, separate from reviewed model vocabulary. */
enum class QueryImpactFlowSemantics {
    KOTLIN_FORWARD_V1
}

enum class QueryImpactLedgerFailure {
    EMPTY_SEEDS,
    DUPLICATE_SEED,
    DUPLICATE_PATH,
    FOREIGN_PRODUCER,
    MISSING_SEED_PATH,
    DOMAIN_MISMATCH,
    CONFLICTING_OBSERVATIONS,
    MISSING_NATIVE_OBSERVATION,
    UNPROVEN_COMPILER_STEP,
    MISSING_BRANCH,
    MISSING_OBLIGATION,
    UNDECLARED_MODEL,
    DUPLICATE_MODEL_REFERENCE,
    UNPROVEN_TERMINAL,
    UNACCOUNTED_NATIVE_READ,
    PRODUCER_IDENTITY_MISMATCH,
    DUPLICATE_REQUESTED_SITE,
    FOREIGN_REQUESTED_SITE,
    FOREIGN_PEER_BASIS,
}

enum class QueryImpactRequiredObligation {
    NATIVE_FLOW,
    BOUNDARY,
    REPRESENTATION_STATE,
    EXECUTION_BOUNDARY,
    PRODUCER_IDENTITY,
    REQUESTED_SITE_RELATIONSHIP,
}

sealed interface QueryImpactClosure {
    data object Discharged : QueryImpactClosure

    class Unresolved internal constructor(val required: Set<QueryImpactRequiredObligation>) : QueryImpactClosure
}

/**
 * Conservation over the admitted producer set and every observed outgoing branch. This owner only composes detached
 * evidence; it cannot establish compiler facts, infer missing edges, or turn a bounded native read into absence.
 */
class QueryImpactLedger
private constructor(
    val seeds: List<ValueSite>,
    val producerEvidence: List<QueryImpactProducerEvidence>,
    val domain: RelationSearchBoundary,
    val semantics: QueryImpactFlowSemantics,
    val representationModels: List<RepresentationRule>,
    val boundaryModels: List<BoundaryModel>,
    val observations: List<ValueFlowStep>,
    val readRejections: List<QueryImpactReadRejection>,
    val paths: List<QueryImpactPath>,
    val closure: QueryImpactClosure,
    val requestedSites: List<QueryImpactRequestedSite>,
    val peerBoundaries: List<QueryImpactPeerBoundary>,
    val siteAccounting: List<QueryImpactSiteAccounting>,
) {
    companion object {
        fun fromEvidence(
            seeds: List<ValueSite>,
            domain: RelationSearchBoundary,
            semantics: QueryImpactFlowSemantics,
            representationModels: List<RepresentationRule>,
            boundaryModels: List<BoundaryModel>,
            observations: List<ValueFlowStep>,
            paths: List<QueryImpactPath>,
            readRejections: List<QueryImpactReadRejection> = emptyList(),
            originalProducers: List<QueryImpactProducer> = emptyList(),
            requestedSites: List<QueryImpactRequestedSite> = emptyList(),
            peerBoundaries: List<QueryImpactPeerBoundary> = emptyList(),
        ): Refinement<QueryImpactLedger, QueryImpactLedgerFailure> {
            val validation =
                QueryImpactLedgerValidation(
                    seeds,
                    originalProducers,
                    domain,
                    representationModels,
                    boundaryModels,
                    observations,
                    paths,
                    readRejections,
                    peerBoundaries,
                )
            when (val admitted = validation.validate()) {
                is Refinement.Refined -> Unit
                is Refinement.Rejected -> return admitted
            }
            val accounting =
                when (val admitted = requestedSiteAccounting(requestedSites, seeds, paths, domain)) {
                    is Refinement.Refined -> admitted.value
                    is Refinement.Rejected -> return admitted
                }
            val relationship = accounting.relationshipRequirements()
            val producers = producerEvidence(originalProducers, seeds)
            val closure = investigationClosure(paths, producers, relationship)
            return Refinement.Refined(
                QueryImpactLedger(
                    snapshot(seeds),
                    snapshot(producers),
                    domain,
                    semantics,
                    snapshot(representationModels),
                    snapshot(boundaryModels),
                    snapshot(observations),
                    snapshot(readRejections),
                    snapshot(paths),
                    closure,
                    snapshot(requestedSites),
                    snapshot(peerBoundaries),
                    accounting,
                )
            )
        }
    }
}

private fun <T> snapshot(values: List<T>): List<T> = Collections.unmodifiableList(values.toList())

private fun investigationClosure(
    paths: List<QueryImpactPath>,
    producers: List<QueryImpactProducerEvidence>,
    relationship: List<QueryImpactRequiredObligation>,
): QueryImpactClosure {
    val required =
        (paths.flatMap { it.requiredObligations() } +
                relationship +
                if (producers.any { it is QueryImpactProducerEvidence.SiteOnly })
                    listOf(QueryImpactRequiredObligation.PRODUCER_IDENTITY)
                else emptyList())
            .toSet()
    return if (required.isEmpty()) QueryImpactClosure.Discharged
    else QueryImpactClosure.Unresolved(Collections.unmodifiableSet(required))
}

internal fun QueryImpactPath.hasModeledArrival(index: Int): Boolean =
    when (steps.getOrNull(index)) {
        is QueryImpactStep.ModeledRepresentation,
        is QueryImpactStep.ModeledBoundary -> true
        is QueryImpactStep.Compiler -> false
        null ->
            when (terminal) {
                is QueryImpactTerminal.Consumer,
                is QueryImpactTerminal.ModeledTerminal -> true
                is QueryImpactTerminal.Unresolved,
                is QueryImpactTerminal.SupportedDomainEnd,
                is QueryImpactTerminal.ExplicitScopeExclusion -> false
            }
    }

internal fun QueryImpactTerminal.isEstablishedBy(observed: ValueFlowStep): Boolean =
    when (this) {
        is QueryImpactTerminal.SupportedDomainEnd -> observation === observed
        is QueryImpactTerminal.Unresolved.Flow -> obligation in observed.obligations
        is QueryImpactTerminal.Unresolved.ReadRejected -> false
        is QueryImpactTerminal.Unresolved.ExecutionStop -> false
        is QueryImpactTerminal.Unresolved.Boundary -> boundary.source.site == observed.source
        is QueryImpactTerminal.Unresolved.PeerContinuation -> false
        is QueryImpactTerminal.Consumer,
        is QueryImpactTerminal.ModeledTerminal,
        is QueryImpactTerminal.ExplicitScopeExclusion -> site == observed.source
    }

internal fun QueryImpactPath.usesDeclaredModels(
    representations: List<RepresentationRule>,
    boundaries: List<BoundaryModel>,
): Boolean =
    steps.all { step ->
        when (step) {
            is QueryImpactStep.Compiler -> true
            is QueryImpactStep.ModeledRepresentation ->
                when (val model = step.application) {
                    is RepresentationModelApplication.Transfer -> model.rule in representations
                    is RepresentationModelApplication.Transformation -> model.rule in representations
                }
            is QueryImpactStep.ModeledBoundary -> step.connection.model in boundaries
        }
    } &&
        when (val current = representation) {
            QueryImpactRepresentation.NotModeled -> true
            is QueryImpactRepresentation.Present ->
                current.evidence.branches.all { branch ->
                    branch.history.all { history ->
                        when (history) {
                            is RepresentationHistory.Origin -> history.rule in representations
                            is RepresentationHistory.CompilerTransfer,
                            is RepresentationHistory.Unmodeled -> true
                            is RepresentationModelApplication.Transfer -> history.rule in representations
                            is RepresentationModelApplication.Transformation -> history.rule in representations
                            is RepresentationHistory.BoundaryModel ->
                                boundaries.any { it.reference == history.reference }
                        }
                    }
                }
        } &&
        when (val end = terminal) {
            is QueryImpactTerminal.Consumer -> end.evidence.rule in representations
            is QueryImpactTerminal.ModeledTerminal -> end.boundary.model in boundaries
            is QueryImpactTerminal.Unresolved,
            is QueryImpactTerminal.SupportedDomainEnd,
            is QueryImpactTerminal.ExplicitScopeExclusion -> true
        }

private fun QueryImpactPath.requiredObligations(): List<QueryImpactRequiredObligation> {
    val currentUnknown =
        when (val current = representation) {
            QueryImpactRepresentation.NotModeled -> emptyList()
            is QueryImpactRepresentation.Present ->
                if (current.evidence.branches.any { it.current is RepresentationCurrent.Unknown })
                    listOf(QueryImpactRequiredObligation.REPRESENTATION_STATE)
                else emptyList()
        }
    val crossing =
        if (steps.any { it is QueryImpactStep.ModeledBoundary && it.connection.obligations.isNotEmpty() })
            listOf(QueryImpactRequiredObligation.BOUNDARY)
        else emptyList()
    return currentUnknown + crossing + terminal.requiredObligations()
}

private fun QueryImpactTerminal.requiredObligations(): List<QueryImpactRequiredObligation> =
    when (val end = this) {
        is QueryImpactTerminal.Unresolved.Flow -> listOf(QueryImpactRequiredObligation.NATIVE_FLOW)
        is QueryImpactTerminal.Unresolved.ReadRejected -> listOf(QueryImpactRequiredObligation.NATIVE_FLOW)
        is QueryImpactTerminal.Unresolved.ExecutionStop -> listOf(QueryImpactRequiredObligation.EXECUTION_BOUNDARY)
        is QueryImpactTerminal.Unresolved.Boundary,
        is QueryImpactTerminal.Unresolved.PeerContinuation -> listOf(QueryImpactRequiredObligation.BOUNDARY)
        is QueryImpactTerminal.ModeledTerminal ->
            if (end.boundary.obligations.isEmpty()) emptyList() else listOf(QueryImpactRequiredObligation.BOUNDARY)
        is QueryImpactTerminal.Consumer ->
            when (end.evidence) {
                is ConsumerRepresentationEvidence.Unknown -> listOf(QueryImpactRequiredObligation.REPRESENTATION_STATE)
                is ConsumerRepresentationEvidence.Satisfied,
                is ConsumerRepresentationEvidence.Different -> emptyList()
            }
        is QueryImpactTerminal.SupportedDomainEnd,
        is QueryImpactTerminal.ExplicitScopeExclusion -> emptyList()
    }

private fun requestedSiteAccounting(
    requestedSites: List<QueryImpactRequestedSite>,
    seeds: List<ValueSite>,
    paths: List<QueryImpactPath>,
    domain: RelationSearchBoundary,
): Refinement<List<QueryImpactSiteAccounting>, QueryImpactLedgerFailure> {
    if (requestedSites.map { it.site }.distinct().size != requestedSites.size)
        return Refinement.Rejected(QueryImpactLedgerFailure.DUPLICATE_REQUESTED_SITE)
    if (requestedSites.any { it.site.hasForeignBasis(seeds.first().enclosing.lease) })
        return Refinement.Rejected(QueryImpactLedgerFailure.FOREIGN_REQUESTED_SITE)
    return QueryImpactSiteAccounting.fromRequestedSites(requestedSites, paths, domain)
}

private fun List<QueryImpactSiteAccounting>.relationshipRequirements(): List<QueryImpactRequiredObligation> =
    if (any { it.outcome == QueryImpactSiteOutcome.RelationshipUnproven })
        listOf(QueryImpactRequiredObligation.REQUESTED_SITE_RELATIONSHIP)
    else emptyList()

private fun producerEvidence(
    original: List<QueryImpactProducer>,
    seeds: List<ValueSite>,
): List<QueryImpactProducerEvidence> =
    if (original.isEmpty()) seeds.map(QueryImpactProducerEvidence::SiteOnly)
    else original.map(QueryImpactProducerEvidence::Invocation)
