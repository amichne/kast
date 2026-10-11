package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.ElapsedTimeLimitMillis
import io.github.amichne.kast.kernel.ResourceBudget
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.kernel.WorkUnitLimit
import io.github.amichne.kast.protocol.contract.QueryOutputDocument
import io.github.amichne.kast.protocol.contract.QueryStepDocument
import io.github.amichne.kast.protocol.contract.RelationKindDocument
import io.github.amichne.kast.query.contract.QueryBudget
import io.github.amichne.kast.query.contract.QueryByteLimit
import io.github.amichne.kast.query.contract.QueryCheckpointStorageAdmission
import io.github.amichne.kast.query.contract.QueryCheckpointStorageObservation
import io.github.amichne.kast.query.service.QueryNanoClock
import io.github.amichne.kast.query.service.QueryService
import io.github.amichne.kast.relation.contract.RelationBatch
import io.github.amichne.kast.relation.contract.RelationByteCount
import io.github.amichne.kast.relation.contract.RelationCompilation
import io.github.amichne.kast.relation.contract.RelationConfirmedReferenceTarget
import io.github.amichne.kast.relation.contract.RelationEndpoint
import io.github.amichne.kast.relation.contract.RelationIncompleteCoverage
import io.github.amichne.kast.relation.contract.RelationLimitation
import io.github.amichne.kast.relation.contract.RelationOccurrence
import io.github.amichne.kast.relation.contract.RelationOperations
import io.github.amichne.kast.relation.contract.RelationProvenance
import io.github.amichne.kast.relation.contract.RelationProviderConsumption
import io.github.amichne.kast.relation.contract.RelationProviderItemDescriptor
import io.github.amichne.kast.relation.contract.RelationProviderLocator
import io.github.amichne.kast.relation.contract.RelationProviderState
import io.github.amichne.kast.relation.contract.RelationReadPosition
import io.github.amichne.kast.relation.contract.RelationReadResult
import io.github.amichne.kast.relation.contract.RelationReferenceContext
import io.github.amichne.kast.relation.contract.RelationReferenceOccurrence
import io.github.amichne.kast.relation.contract.RelationReferenceOwnership
import io.github.amichne.kast.relation.contract.RelationRequest
import io.github.amichne.kast.relation.contract.RelationResultCount
import io.github.amichne.kast.relation.contract.RelationWorkCount
import io.github.amichne.kast.source.contract.SourceReadOperations
import io.github.amichne.kast.symbol.contract.CompilerGroundedSymbolEvidence
import io.github.amichne.kast.symbol.contract.ExactDeclarationTextRange
import io.github.amichne.kast.symbol.contract.ExactSymbolRequest
import io.github.amichne.kast.symbol.contract.SymbolDescription
import io.github.amichne.kast.symbol.contract.SymbolDescriptionResult
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryOperations
import io.github.amichne.kast.symbol.contract.SymbolExactOperations
import io.github.amichne.kast.symbol.contract.SymbolResolutionRequest
import io.github.amichne.kast.symbol.contract.SymbolResolutionResult
import io.github.amichne.kast.traversal.contract.TraversalBudget
import io.github.amichne.kast.traversal.contract.TraversalByteLimit
import io.github.amichne.kast.traversal.contract.TraversalDepthLimit
import io.github.amichne.kast.traversal.contract.TraversalFrontierLimit
import io.github.amichne.kast.traversal.contract.TraversalOperations
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals

/** Detached external observations shared by two production accounting test consumers. */
internal open class AutomaticDenseRetentionCase : AutomaticSymbolQueryCase() {
    private val unexpectedCalls = mutableListOf<String>()

    private fun unexpected(boundary: String): Nothing {
        unexpectedCalls += boundary
        error("Unexpected $boundary")
    }

    @AfterEach
    fun `external observation violations cannot be swallowed by production`() {
        assertEquals(emptyList<String>(), unexpectedCalls)
    }

    protected val locators =
        (0 until 1_001).map { ordinal ->
            val start = 100 + ordinal * 32
            RelationProviderLocator.Reference(
                fixture.selector.file,
                ExactDeclarationTextRange.parse(start, start + 20).refined(),
                RelationProviderItemDescriptor.parse("dense-reference:$start").refined(),
            )
        }
    protected val inventory = RelationProviderState.references(locators)
    protected val positions = mutableListOf<Long>()
    protected val checkpointAdmissions = mutableListOf<QueryCheckpointStorageAdmission>()
    protected val input =
        request.copy(
            output = QueryOutputDocument.Occurrences,
            steps = bounded(listOf(QueryStepDocument.Related(RelationKindDocument.REFERENCES))),
        )
    protected val grant =
        QueryBudget(
            ResourceBudget(
                ResultLimit.parse(20).refined(),
                WorkUnitLimit.parse(100_000).refined(),
                ElapsedTimeLimitMillis.parse(10_000).refined(),
            ),
            QueryByteLimit.parse(49_152).refined(),
        )
    protected val service =
        QueryService(
            discovery = SymbolDiscoveryOperations { unexpected("discovery") },
            exact =
                object : SymbolExactOperations {
                    override suspend fun resolve(request: SymbolResolutionRequest): SymbolResolutionResult =
                        unexpected("resolution")

                    override suspend fun describe(request: ExactSymbolRequest): SymbolDescriptionResult =
                        SymbolDescriptionResult.Described(SymbolDescription.from(request.selector))
                },
            source = SourceReadOperations { unexpected("source read") },
            relations = RelationOperations(::read),
            traversal = TraversalOperations { unexpected("traversal") },
            traversalCeiling =
                TraversalBudget(
                    grant.resources.resultLimit,
                    TraversalByteLimit.parse(100_000).refined(),
                    grant.resources.workUnitLimit,
                    grant.resources.elapsedTimeLimit,
                    TraversalDepthLimit.parse(1).refined(),
                    TraversalFrontierLimit.parse(100).refined(),
                    fixture.budget,
                ),
            clock = QueryNanoClock { 0L },
            checkpointObservation = QueryCheckpointStorageObservation(checkpointAdmissions::add),
        )

    protected fun read(request: RelationRequest): RelationReadResult {
        var state = (request.position as? RelationReadPosition.Resume)?.continuation?.providerState ?: inventory
        positions += state.consumedLocatorCount.value
        val references =
            state.prepared
                .take(minOf(20, request.budget.resources.resultLimit.value))
                .map { locator ->
                    val occurrence = reference(request, locator)
                    state = state.consume(RelationProviderConsumption.Confirmed(occurrence))
                    occurrence
                }
                .sorted()
        val batch =
            RelationBatch.create(
                    request,
                    references.map { it.declarationFact(request).refined() }.sorted(),
                    RelationByteCount.parse(
                            references.sumOf {
                                it.canonicalProjection().toByteArray(Charsets.UTF_8).size.toLong() +
                                    it.declarationFact(request)
                                        .refined()
                                        .canonicalProjection()
                                        .toByteArray(Charsets.UTF_8)
                                        .size
                            }
                        )
                        .refined(),
                    RelationWorkCount.parse(references.size.toLong()).refined(),
                    RelationResultCount.parse(references.size).refined(),
                    references,
                )
                .refined()
        return if (state.hasUnfinishedWork)
            RelationReadResult.Qualified(
                batch,
                RelationIncompleteCoverage.resumable(
                        batch,
                        setOf(RelationLimitation.RESULT_LIMIT_REACHED),
                        state.providerCursor,
                        state,
                    )
                    .refined(),
            )
        else RelationReadResult.Complete(batch, RelationCompilation.complete(batch).coverage)
    }

    private fun reference(request: RelationRequest, locator: RelationProviderLocator): RelationReferenceOccurrence =
        RelationReferenceOccurrence.confirmed(
                request,
                RelationConfirmedReferenceTarget.fromCompiler(
                        request.subject,
                        CompilerGroundedSymbolEvidence.fromSelector(fixture.selector),
                    )
                    .refined(),
                RelationOccurrence.fromBoundary(
                        locator.file,
                        locator.range.startInclusive,
                        locator.range.endExclusive,
                    )
                    .refined(),
                RelationReferenceContext.TYPE,
                RelationReferenceOwnership.DeclarationOwned(
                    RelationEndpoint.resolve(
                            fixture.authority,
                            request.searchScope,
                            CompilerGroundedSymbolEvidence.fromSelector(fixture.selector),
                        )
                        .refined()
                ),
                RelationProvenance.K2_AUTHORED_SOURCE,
            )
            .refined()
}
