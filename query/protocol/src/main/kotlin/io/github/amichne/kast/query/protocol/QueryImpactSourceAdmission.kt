package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.AdmittedImpactModelSyntax
import io.github.amichne.kast.protocol.contract.ImpactModelDocument
import io.github.amichne.kast.protocol.contract.ImpactValueRoleDocument
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
    val modelSyntax = mutableListOf<AdmittedImpactModelSyntax>()
    for ((position, model) in models.values.withIndex()) {
        when (val admitted = AdmittedImpactModelSyntax.admit(model)) {
            is Refinement.Refined -> modelSyntax += admitted.value
            is Refinement.Rejected -> return impactFailure(admitted.failure.impactFailure(), position)
        }
    }
    val declaredPositions = models.values.filterIsInstance<ImpactModelDocument.Boundary>().flatMap { it.positions() }
    when (val admitted = admitBoundaryClaims(lease, declaredPositions)) {
        is Refinement.Refined -> Unit
        is Refinement.Rejected -> return admitted
    }
    val acquisition = QueryImpactSourceAcquisition(lease, authority, compiler, budget, domain)
    when (val admitted = acquisition.acquireSeeds(seeds.values)) {
        is Refinement.Refined -> Unit
        is Refinement.Rejected -> return admitted
    }
    when (val admitted = acquisition.acquireDeclarations(declarations.values)) {
        is Refinement.Refined -> Unit
        is Refinement.Rejected -> return admitted
    }
    when (val admitted = acquisition.acquirePositions(declaredPositions)) {
        is Refinement.Refined -> Unit
        is Refinement.Rejected -> return admitted
    }
    return acquisition.bindSource(modelSyntax, flow)
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

private fun QueryImpactSourceDocument.admitCountBounds(): Refinement<Unit, QueryRunRejection> {
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

private fun QueryImpactSourceDocument.admitBoundaryClaims(
    lease: SemanticReadAuthority,
    declaredPositions: List<io.github.amichne.kast.protocol.contract.ImpactBoundaryPositionDocument>,
): Refinement<Unit, QueryRunRejection> {
    for ((position, declared) in declaredPositions.withIndex()) {
        val claims =
            listOf(declared.site.enclosing) +
                when (val role = declared.site.role) {
                    is ImpactValueRoleDocument.Argument -> listOf(role.invocation.callable)
                    ImpactValueRoleDocument.ExpressionResult,
                    ImpactValueRoleDocument.LocalBinding,
                    ImpactValueRoleDocument.LocalRead,
                    ImpactValueRoleDocument.Return,
                    ImpactValueRoleDocument.PropertyAssignment -> emptyList()
                }
        if (claims.any { !it.basis.matchesBasis(lease.identity) })
            return impactFailure(QueryImpactSourceFailureCode.BOUNDARY_BASIS_MISMATCH, position)
        if (claims.any { claim -> declarations.values.none { it.declaration == claim } })
            return impactFailure(QueryImpactSourceFailureCode.MISSING_DECLARATION, position)
    }
    return Refinement.Refined(Unit)
}
