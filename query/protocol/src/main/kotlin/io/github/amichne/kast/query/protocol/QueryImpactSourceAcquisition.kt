package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.WorkUnitLimit
import io.github.amichne.kast.protocol.contract.AdmittedImpactModelSyntax
import io.github.amichne.kast.protocol.contract.ImpactBoundaryPositionDocument
import io.github.amichne.kast.protocol.contract.ImpactValueSiteReferenceDocument
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.contract.QueryImpactDeclarationDocument
import io.github.amichne.kast.protocol.contract.QueryImpactFlowDocument
import io.github.amichne.kast.protocol.contract.QueryImpactProducerDocument
import io.github.amichne.kast.protocol.contract.QueryImpactSourceFailureCode
import io.github.amichne.kast.protocol.contract.QueryImpactSourceFailureDocument
import io.github.amichne.kast.protocol.contract.QueryRunRejection
import io.github.amichne.kast.query.contract.QueryBudget
import io.github.amichne.kast.query.contract.QueryImpactProducer
import io.github.amichne.kast.query.contract.QueryImpactRequestedSite
import io.github.amichne.kast.query.contract.QueryImpactRetainedGraph
import io.github.amichne.kast.query.contract.QueryImpactSource
import io.github.amichne.kast.query.contract.QueryImpactSourceFailure
import io.github.amichne.kast.relation.contract.BoundaryPosition
import io.github.amichne.kast.relation.contract.RelationBudget
import io.github.amichne.kast.relation.contract.RelationByteLimit
import io.github.amichne.kast.relation.contract.RelationSearchBoundary
import io.github.amichne.kast.relation.contract.RevalidatedRelationEndpoint
import io.github.amichne.kast.relation.contract.ValueModelDeclarationRead
import io.github.amichne.kast.relation.contract.ValueProducerSeed
import io.github.amichne.kast.relation.contract.ValueProducerSeedCompilerPort
import io.github.amichne.kast.relation.contract.ValueProducerSeedRead
import io.github.amichne.kast.relation.contract.ValueProducerSeedRequest
import io.github.amichne.kast.relation.contract.ValueProducerSeedRequestFailure
import io.github.amichne.kast.symbol.contract.ExactDeclarationTextRange
import io.github.amichne.kast.symbol.contract.SymbolSelector
import io.github.amichne.kast.workspace.contract.SemanticReadAuthority

/** Request-local acquisition shares the existing authority and original grant; it owns no continuation or store. */
internal class QueryImpactSourceAcquisition(
    private val lease: SemanticReadAuthority,
    private val authority: QueryReferenceAuthority,
    private val compiler: ValueProducerSeedCompilerPort,
    private val budget: QueryBudget,
    private val domain: RelationSearchBoundary,
) {
    private var examinedWork = 0L
    private var retainedBytes = 0L
    private val producers = mutableListOf<QueryImpactProducer>()
    private val current = mutableListOf<RevalidatedRelationEndpoint>()
    private val currentSelectors = mutableListOf<SymbolSelector>()
    private val currentPositions = mutableListOf<BoundaryPosition>()
    private val sites = mutableMapOf<ImpactValueSiteReferenceDocument, QueryImpactRequestedSite>()
    private val requestedSites = mutableListOf<QueryImpactRequestedSite>()

    private suspend fun remaining(position: Int): Refinement<RelationBudget, QueryRunRejection> {
        val current =
            when (val observed = authority.remainingReadBudget(budget.resources)) {
                is Refinement.Refined -> observed.value
                is Refinement.Rejected ->
                    return impactFailure(
                        when (observed.failure) {
                            ReadReacquisitionBudgetFailure.WORK_LIMIT_REACHED ->
                                QueryImpactSourceFailureCode.WORK_LIMIT_REACHED
                            ReadReacquisitionBudgetFailure.TIME_LIMIT_REACHED ->
                                QueryImpactSourceFailureCode.TIME_LIMIT_REACHED
                        },
                        position,
                    )
            }
        val work =
            when (
                val admitted =
                    WorkUnitLimit.parse(
                        minOf(current.workUnitLimit.value, budget.resources.workUnitLimit.value - examinedWork)
                    )
            ) {
                is Refinement.Refined -> admitted.value
                is Refinement.Rejected ->
                    return impactFailure(QueryImpactSourceFailureCode.WORK_LIMIT_REACHED, position)
            }
        val bytes =
            when (val admitted = RelationByteLimit.parse(budget.checkpointBytes.value - retainedBytes)) {
                is Refinement.Refined -> admitted.value
                is Refinement.Rejected ->
                    return impactFailure(QueryImpactSourceFailureCode.BYTE_LIMIT_REACHED, position)
            }
        return Refinement.Refined(RelationBudget(current.copy(workUnitLimit = work), bytes))
    }

    private fun restore(token: ProtocolText, position: Int): Refinement<SymbolSelector, QueryRunRejection> =
        when (val restored = authority.restoreExact(token, lease)) {
            is CanonicalSelectorDecoding.Decoded -> Refinement.Refined(restored.value)
            is CanonicalSelectorDecoding.Rejected ->
                Refinement.Rejected(
                    QueryRunRejection.ImpactSourceRejected(
                        QueryImpactSourceFailureDocument.Reference(
                            restored.failure.queryRejection(token),
                            queryPosition(position),
                        )
                    )
                )
        }

    suspend fun acquireSeeds(seeds: List<QueryImpactProducerDocument>): Refinement<Unit, QueryRunRejection> {
        for ((position, seed) in seeds.withIndex()) when (val admitted = acquireSeed(seed, position)) {
            is Refinement.Refined -> Unit
            is Refinement.Rejected -> return admitted
        }
        return Refinement.Refined(Unit)
    }

    private suspend fun seedRequest(
        seed: QueryImpactProducerDocument,
        position: Int,
    ): Refinement<ValueProducerSeedRequest, QueryRunRejection> {
        val enclosing =
            when (val restored = restore(seed.enclosing, position)) {
                is Refinement.Refined -> restored.value
                is Refinement.Rejected -> return restored
            }
        val callable =
            when (val restored = restore(seed.callable, position)) {
                is Refinement.Refined -> restored.value
                is Refinement.Rejected -> return restored
            }
        if (enclosing.lease.identity != lease.identity || callable.lease.identity != lease.identity)
            return impactFailure(QueryImpactSourceFailureCode.BASIS_MISMATCH, position)
        val anchor =
            when (val parsed = ExactDeclarationTextRange.parse(seed.anchor.start.value, seed.anchor.end.value)) {
                is Refinement.Refined -> parsed.value
                is Refinement.Rejected -> return impactFailure(QueryImpactSourceFailureCode.INVALID_ANCHOR, position)
            }
        val grant =
            when (val admitted = remaining(position)) {
                is Refinement.Refined -> admitted.value
                is Refinement.Rejected -> return admitted
            }
        return when (val admitted = ValueProducerSeedRequest.create(enclosing, anchor, callable, grant, domain)) {
            is Refinement.Refined -> admitted
            is Refinement.Rejected ->
                return impactFailure(
                    when (admitted.failure) {
                        ValueProducerSeedRequestFailure.BASIS_MISMATCH -> QueryImpactSourceFailureCode.BASIS_MISMATCH
                        ValueProducerSeedRequestFailure.ANCHOR_OUTSIDE_ENCLOSING ->
                            QueryImpactSourceFailureCode.ANCHOR_OUTSIDE_ENCLOSING
                    },
                    position,
                )
        }
    }

    private suspend fun acquireSeed(
        seed: QueryImpactProducerDocument,
        position: Int,
    ): Refinement<Unit, QueryRunRejection> {
        val request =
            when (val admitted = seedRequest(seed, position)) {
                is Refinement.Refined -> admitted.value
                is Refinement.Rejected -> return admitted
            }
        val observed =
            when (val read = compiler.seed(request)) {
                is ValueProducerSeedRead.Seeded -> read
                is ValueProducerSeedRead.Rejected -> return impactFailure(read.cause.impactFailure(), position)
                is ValueProducerSeedRead.ContractRejected -> return impactFailure(read.cause.impactFailure(), position)
            }
        if (observed.examinedWorkUnits.value > request.budget.resources.workUnitLimit.value)
            return impactFailure(QueryImpactSourceFailureCode.WORK_RECEIPT_EXCEEDS_GRANT, position)
        examinedWork += observed.examinedWorkUnits.value
        // An adapter cannot substitute a proof admitted against a different seed request.
        when (val bound = ValueProducerSeed.fromCompiler(request, observed.seed.site, observed.seed.invocation)) {
            is Refinement.Refined -> Unit
            is Refinement.Rejected -> return impactFailure(bound.failure.impactFailure(), position)
        }
        when (val producer = QueryImpactProducer.admit(observed.seed.site, observed.seed.invocation)) {
            is Refinement.Refined -> producers += producer.value
            is Refinement.Rejected -> return impactFailure(QueryImpactSourceFailureCode.ROLE_MISMATCH, position)
        }
        when (val retained = QueryImpactSource.admit(producers, emptyList(), emptyList(), domain)) {
            is Refinement.Refined -> retainedBytes = retained.value.retainedBytes
            is Refinement.Rejected ->
                return impactFailure(
                    when (retained.failure) {
                        QueryImpactSourceFailure.DUPLICATE_REQUESTED_SITE ->
                            QueryImpactSourceFailureCode.DUPLICATE_REQUESTED_SITE
                        QueryImpactSourceFailure.EMPTY_PRODUCERS -> QueryImpactSourceFailureCode.EMPTY_PRODUCERS
                        QueryImpactSourceFailure.DUPLICATE_PRODUCER -> QueryImpactSourceFailureCode.DUPLICATE_PRODUCER
                        QueryImpactSourceFailure.DUPLICATE_MODEL ->
                            QueryImpactSourceFailureCode.DUPLICATE_MODEL_REFERENCE
                        QueryImpactSourceFailure.FOREIGN_BASIS -> QueryImpactSourceFailureCode.BASIS_MISMATCH
                    },
                    position,
                )
        }
        if (retainedBytes > budget.checkpointBytes.value)
            return impactFailure(QueryImpactSourceFailureCode.BYTE_LIMIT_REACHED, position)
        return Refinement.Refined(Unit)
    }

    suspend fun acquireDeclarations(
        declarations: List<QueryImpactDeclarationDocument>
    ): Refinement<Unit, QueryRunRejection> {
        for ((position, declaration) in declarations.withIndex()) when (
            val admitted = acquireDeclaration(declaration, position)
        ) {
            is Refinement.Refined -> Unit
            is Refinement.Rejected -> return admitted
        }
        return Refinement.Refined(Unit)
    }

    private suspend fun acquireDeclaration(
        declaration: QueryImpactDeclarationDocument,
        position: Int,
    ): Refinement<Unit, QueryRunRejection> {
        val selected =
            when (val restored = restore(declaration.reference, position)) {
                is Refinement.Refined -> restored.value
                is Refinement.Rejected -> return restored
            }
        if (selected.lease.identity != lease.identity)
            return impactFailure(QueryImpactSourceFailureCode.STALE_DECLARATION, position)
        val grant =
            when (val admitted = remaining(position)) {
                is Refinement.Refined -> admitted.value
                is Refinement.Rejected -> return admitted
            }
        val read =
            when (val observed = compiler.revalidate(selected, grant)) {
                is ValueModelDeclarationRead.Revalidated -> observed
                is ValueModelDeclarationRead.Rejected -> return impactFailure(observed.cause.impactFailure(), position)
            }
        if (read.examinedWorkUnits.value > grant.resources.workUnitLimit.value)
            return impactFailure(QueryImpactSourceFailureCode.WORK_RECEIPT_EXCEEDS_GRANT, position)
        examinedWork += read.examinedWorkUnits.value
        val endpoint = read.declaration.endpoint
        if (
            endpoint.compilerIdentity != selected.compilerIdentity ||
                endpoint.file != selected.file ||
                endpoint.range != selected.range
        )
            return impactFailure(QueryImpactSourceFailureCode.DECLARATION_MISMATCH, position)
        if (!declaration.declaration.matchesDeclaration(endpoint))
            return impactFailure(QueryImpactSourceFailureCode.DECLARATION_MISMATCH, position)
        if (endpoint.lease.identity != lease.identity || !declaration.declaration.basis.matchesBasis(lease.identity))
            return impactFailure(QueryImpactSourceFailureCode.STALE_DECLARATION, position)
        current += read.declaration
        currentSelectors += selected
        return Refinement.Refined(Unit)
    }

    suspend fun acquirePositions(
        declaredPositions: List<ImpactBoundaryPositionDocument>
    ): Refinement<Unit, QueryRunRejection> {
        for ((position, declared) in declaredPositions.withIndex()) {
            val site =
                when (val cached = sites[declared.site]) {
                    null ->
                        when (
                            val admitted =
                                acquireSite(declared.site, position, ImpactSiteAdmissionOrigin.BOUNDARY_MODEL)
                        ) {
                            is Refinement.Refined -> admitted.value.also { sites[declared.site] = it }
                            is Refinement.Rejected -> return admitted
                        }
                    else -> cached
                }
            when (val admitted = declared.attachToNative(site.site)) {
                is Refinement.Refined -> currentPositions += admitted.value
                is Refinement.Rejected -> return impactFailure(admitted.failure.impactFailure(), position)
            }
        }
        return Refinement.Refined(Unit)
    }

    suspend fun acquireRequestedSites(
        declaredSites: List<ImpactValueSiteReferenceDocument>
    ): Refinement<Unit, QueryRunRejection> {
        for ((position, declared) in declaredSites.withIndex()) {
            val proof =
                when (val cached = sites[declared]) {
                    null ->
                        when (
                            val admitted = acquireSite(declared, position, ImpactSiteAdmissionOrigin.REQUESTED_SITE)
                        ) {
                            is Refinement.Refined -> admitted.value.also { sites[declared] = it }
                            is Refinement.Rejected -> return admitted
                        }
                    else -> cached
                }
            requestedSites += proof
        }
        return Refinement.Refined(Unit)
    }

    private suspend fun acquireSite(
        declared: ImpactValueSiteReferenceDocument,
        position: Int,
        origin: ImpactSiteAdmissionOrigin,
    ): Refinement<QueryImpactRequestedSite, QueryRunRejection> {
        val grant =
            when (val admitted = remaining(position)) {
                is Refinement.Refined -> admitted.value
                is Refinement.Rejected -> return admitted
            }
        val request =
            when (val admitted = declared.siteRequest(currentSelectors, grant)) {
                is Refinement.Refined -> admitted.value
                is Refinement.Rejected -> return impactFailure(admitted.failure, position)
            }
        val proof =
            when (val admitted = revalidateImpactSite(request, compiler, grant, origin, position)) {
                is Refinement.Refined -> admitted.value
                is Refinement.Rejected -> return admitted
            }
        examinedWork += proof.examinedWorkUnits.value
        retainedBytes +=
            when (origin) {
                ImpactSiteAdmissionOrigin.BOUNDARY_MODEL -> proof.site.retainedBytes
                ImpactSiteAdmissionOrigin.REQUESTED_SITE -> QueryImpactRetainedGraph().site(proof.site)
            }
        if (retainedBytes > budget.checkpointBytes.value)
            return impactFailure(QueryImpactSourceFailureCode.BYTE_LIMIT_REACHED, position)
        return Refinement.Refined(proof)
    }

    fun bindSource(
        modelSyntax: List<AdmittedImpactModelSyntax>,
        flow: QueryImpactFlowDocument,
    ): Refinement<QueryImpactSourceAdmission, QueryRunRejection> =
        bindImpactSourceModels(
            ImpactSourceModelEvidence(
                producers.toList(),
                current.toList(),
                currentPositions.toList(),
                examinedWork,
                requestedSites.toList(),
            ),
            modelSyntax,
            domain,
            flow,
            budget,
        )
}
