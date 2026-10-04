package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.AdmittedImpactModelSyntax
import io.github.amichne.kast.protocol.contract.ImpactModelDocument
import io.github.amichne.kast.protocol.contract.ImpactValueRoleDocument
import io.github.amichne.kast.protocol.contract.ImpactValueSiteReferenceDocument
import io.github.amichne.kast.protocol.contract.QueryImpactSourceDocument
import io.github.amichne.kast.protocol.contract.QueryImpactSourceFailureCode
import io.github.amichne.kast.protocol.contract.QueryImpactSourceFailureDocument
import io.github.amichne.kast.protocol.contract.QueryRunRejection
import io.github.amichne.kast.query.contract.QueryBudget
import io.github.amichne.kast.query.contract.QueryImpactSource
import io.github.amichne.kast.relation.contract.RelationBudget
import io.github.amichne.kast.relation.contract.ValueModelDeclarationRead
import io.github.amichne.kast.relation.contract.ValueProducerSeedCompilerPort
import io.github.amichne.kast.relation.contract.ValueProducerSeedRead
import io.github.amichne.kast.relation.contract.ValueProducerSeedRejection
import io.github.amichne.kast.relation.contract.ValueProducerSeedRequest
import io.github.amichne.kast.symbol.contract.SymbolSelector
import io.github.amichne.kast.workspace.contract.SemanticReadAuthority

internal object UnavailableValueProducerSeeds : ValueProducerSeedCompilerPort {
    override suspend fun seed(request: ValueProducerSeedRequest): ValueProducerSeedRead =
        ValueProducerSeedRead.Rejected(ValueProducerSeedRejection.NATIVE_UNAVAILABLE)

    override suspend fun revalidate(selector: SymbolSelector, budget: RelationBudget): ValueModelDeclarationRead =
        ValueModelDeclarationRead.Rejected(ValueProducerSeedRejection.NATIVE_UNAVAILABLE)
}

internal data class QueryImpactSourceAdmission(val source: QueryImpactSource, val examinedWork: Long)

/** Native proof is acquired once, before ordinary plan compilation; resumed plans retain that proof. */
internal suspend fun QueryImpactSourceDocument.admitImpact(
    lease: SemanticReadAuthority,
    authority: QueryReferenceAuthority,
    compiler: ValueProducerSeedCompilerPort,
    budget: QueryBudget,
    peerAdmissions: List<io.github.amichne.kast.query.contract.QueryImpactPeerSiteAdmission> = emptyList(),
): Refinement<QueryImpactSourceAdmission, QueryRunRejection> {
    when (val admitted = admitCountBounds()) {
        is Refinement.Refined -> Unit
        is Refinement.Rejected -> return admitted
    }
    val domain =
        when (val parsed = domain.boundary()) {
            is Refinement.Refined -> parsed.value
            is Refinement.Rejected -> return impactFailure(parsed.failure.impactFailure())
        }
    val modelSyntax =
        when (val admitted = admitModelSyntax()) {
            is Refinement.Rejected -> return admitted
            is Refinement.Refined -> admitted.value
        }
    val peers =
        when (val admitted = admitPeers(lease, peerAdmissions, budget)) {
            is Refinement.Rejected -> return admitted
            is Refinement.Refined -> admitted.value
        }
    val acquisition = QueryImpactSourceAcquisition(lease, authority, compiler, budget, domain, peers)
    when (val admitted = acquisition.acquireSourceInputs(this, peers)) {
        is Refinement.Rejected -> return admitted
        is Refinement.Refined -> Unit
    }
    return acquisition.bindSource(modelSyntax, flow)
}

private fun QueryImpactSourceDocument.admitModelSyntax():
    Refinement<List<AdmittedImpactModelSyntax>, QueryRunRejection> {
    val admittedModels = mutableListOf<AdmittedImpactModelSyntax>()
    for ((position, model) in models.values.withIndex()) {
        when (val admitted = AdmittedImpactModelSyntax.admit(model)) {
            is Refinement.Refined -> admittedModels += admitted.value
            is Refinement.Rejected -> return impactFailure(admitted.failure.impactFailure(), position)
        }
    }
    return Refinement.Refined(admittedModels.toList())
}

private fun QueryImpactSourceDocument.admitPeers(
    lease: SemanticReadAuthority,
    peerAdmissions: List<io.github.amichne.kast.query.contract.QueryImpactPeerSiteAdmission>,
    budget: QueryBudget,
): Refinement<ImpactPeerSourceEvidence, QueryRunRejection> {
    val peers =
        when (val selected = selectPeerSites(lease)) {
            is Refinement.Rejected -> return selected
            is Refinement.Refined ->
                when (val admitted = ImpactPeerSourceEvidence.admit(selected.value, peerAdmissions)) {
                    is Refinement.Rejected -> return admitted
                    is Refinement.Refined -> admitted.value
                }
        }
    if (peers.retainedBytes > budget.checkpointBytes.value)
        return impactFailure(QueryImpactSourceFailureCode.BYTE_LIMIT_REACHED)
    if (peers.examinedWork >= budget.resources.workUnitLimit.value)
        return impactFailure(QueryImpactSourceFailureCode.WORK_LIMIT_REACHED)
    return Refinement.Refined(peers)
}

private suspend fun QueryImpactSourceAcquisition.acquireSourceInputs(
    source: QueryImpactSourceDocument,
    peers: ImpactPeerSourceEvidence,
): Refinement<Unit, QueryRunRejection> {
    val declaredPositions =
        source.models.values.filterIsInstance<ImpactModelDocument.Boundary>().flatMap { it.positions() }
    when (val admitted = acquireSeeds(source.seeds.values)) {
        is Refinement.Refined -> Unit
        is Refinement.Rejected -> return admitted
    }
    when (
        val admitted =
            acquireDeclarations(
                source.declarations.values.filter { declaration ->
                    peers.selections.none { declaration in it.declarations }
                }
            )
    ) {
        is Refinement.Refined -> Unit
        is Refinement.Rejected -> return admitted
    }
    when (val admitted = acquirePositions(declaredPositions)) {
        is Refinement.Refined -> Unit
        is Refinement.Rejected -> return admitted
    }
    when (val admitted = acquireRequestedSites(source.requestedSites.values)) {
        is Refinement.Refined -> Unit
        is Refinement.Rejected -> return admitted
    }
    return Refinement.Refined(Unit)
}

internal fun impactFailure(
    cause: QueryImpactSourceFailureCode,
    position: Int = 0,
): Refinement.Rejected<QueryRunRejection> =
    Refinement.Rejected(
        QueryRunRejection.ImpactSourceRejected(
            QueryImpactSourceFailureDocument.Admission(cause, queryPosition(position))
        )
    )

private const val MAXIMUM_PRODUCERS = 32
private const val MAXIMUM_DECLARATIONS = 128
private const val MAXIMUM_MODELS = 32

internal fun QueryImpactSourceDocument.admitCountBounds(): Refinement<Unit, QueryRunRejection> {
    if (requestedSites.values.size > MAXIMUM_DECLARATIONS)
        return impactFailure(QueryImpactSourceFailureCode.TOO_MANY_REQUESTED_SITES)
    if (requestedSites.values.distinct().size != requestedSites.values.size)
        return impactFailure(QueryImpactSourceFailureCode.DUPLICATE_REQUESTED_SITE)
    if (seeds.values.isEmpty()) return impactFailure(QueryImpactSourceFailureCode.EMPTY_PRODUCERS)
    if (seeds.values.size > MAXIMUM_PRODUCERS) return impactFailure(QueryImpactSourceFailureCode.TOO_MANY_PRODUCERS)
    if (declarations.values.size > MAXIMUM_DECLARATIONS)
        return impactFailure(QueryImpactSourceFailureCode.TOO_MANY_DECLARATIONS)
    if (models.values.size > MAXIMUM_MODELS) return impactFailure(QueryImpactSourceFailureCode.TOO_MANY_MODELS)
    if (seeds.values.distinct().size != seeds.values.size)
        return impactFailure(QueryImpactSourceFailureCode.DUPLICATE_PRODUCER)
    if (declarations.values.map { it.declaration }.distinct().size != declarations.values.size)
        return impactFailure(QueryImpactSourceFailureCode.DUPLICATE_DECLARATION)
    return Refinement.Refined(Unit)
}

internal fun QueryImpactSourceDocument.admitBoundaryClaims(
    lease: SemanticReadAuthority,
    declaredPositions: List<io.github.amichne.kast.protocol.contract.ImpactBoundaryPositionDocument>,
): Refinement<Unit, QueryRunRejection> {
    return admitSiteClaims(
        lease,
        declaredPositions.map { it.site },
        QueryImpactSourceFailureCode.BOUNDARY_BASIS_MISMATCH,
    )
}

internal fun QueryImpactSourceDocument.admitSiteClaims(
    lease: SemanticReadAuthority,
    sites: List<ImpactValueSiteReferenceDocument>,
    basisFailure: QueryImpactSourceFailureCode,
): Refinement<Unit, QueryRunRejection> {
    for ((position, declared) in sites.withIndex()) {
        val claims =
            listOf(declared.enclosing) +
                when (val role = declared.role) {
                    is ImpactValueRoleDocument.Argument -> listOf(role.invocation.callable)
                    ImpactValueRoleDocument.ExpressionResult,
                    ImpactValueRoleDocument.LocalBinding,
                    ImpactValueRoleDocument.LocalRead,
                    ImpactValueRoleDocument.Return,
                    ImpactValueRoleDocument.PropertyAssignment -> emptyList()
                }
        if (claims.any { !it.basis.matchesBasis(lease.identity) }) return impactFailure(basisFailure, position)
        if (claims.any { claim -> declarations.values.none { it.declaration == claim } })
            return impactFailure(QueryImpactSourceFailureCode.MISSING_DECLARATION, position)
    }
    return Refinement.Refined(Unit)
}
