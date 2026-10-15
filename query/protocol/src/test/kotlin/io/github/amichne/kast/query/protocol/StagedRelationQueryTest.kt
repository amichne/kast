package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.WorkUnitLimit
import io.github.amichne.kast.protocol.contract.QueryCompletionCoverageDocument
import io.github.amichne.kast.protocol.contract.QueryCompletionUnprovenReason
import io.github.amichne.kast.protocol.contract.QueryExpansionScopeDocument
import io.github.amichne.kast.protocol.contract.QueryFromDocument
import io.github.amichne.kast.protocol.contract.QueryLimitationDocument
import io.github.amichne.kast.protocol.contract.QueryResultItemDocument
import io.github.amichne.kast.protocol.contract.QueryResultRetention
import io.github.amichne.kast.protocol.contract.QueryRetentionModeDocument
import io.github.amichne.kast.protocol.contract.QueryStepDocument
import io.github.amichne.kast.protocol.contract.RelationKindDocument
import io.github.amichne.kast.query.service.QueryNanoClock
import io.github.amichne.kast.query.service.QueryService
import io.github.amichne.kast.relation.contract.RelationBatch
import io.github.amichne.kast.relation.contract.RelationByteCount
import io.github.amichne.kast.relation.contract.RelationCompilation
import io.github.amichne.kast.relation.contract.RelationEndpoint
import io.github.amichne.kast.relation.contract.RelationFact
import io.github.amichne.kast.relation.contract.RelationLimitation
import io.github.amichne.kast.relation.contract.RelationMeaning
import io.github.amichne.kast.relation.contract.RelationOccurrence
import io.github.amichne.kast.relation.contract.RelationOperations
import io.github.amichne.kast.relation.contract.RelationProvenance
import io.github.amichne.kast.relation.contract.RelationReadResult
import io.github.amichne.kast.relation.contract.RelationResultCount
import io.github.amichne.kast.relation.contract.RelationSearchBoundary
import io.github.amichne.kast.relation.contract.RelationWorkCount
import io.github.amichne.kast.source.contract.SourceReadOperations
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
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Public retained composition and the production interpreter run against bounded external relation facts. */
internal class StagedRelationQueryTest : AutomaticSymbolQueryCase() {
    private val hop = QueryStepDocument.Related(RelationKindDocument.CALLERS, QueryExpansionScopeDocument.RetainedSeed)
    private val smallBudget =
        budget.copy(resources = budget.resources.copy(workUnitLimit = WorkUnitLimit.parse(3).refined()))

    @Test
    fun `complete retained stage supplies the next hop without repeating the prefix`() = runTest {
        val case = Stages()
        val first =
            case.protocol.execute(
                request.copy(steps = bounded(listOf(hop)), retention = QueryRetentionModeDocument.RETAIN),
                fixture.authority,
                smallBudget,
                policy(),
            ) as OperationOutcome.Complete
        val firstPayload = first.evidence.payload
        assertEquals("middle", (firstPayload.items.values.single() as QueryResultItemDocument.ExactSymbol).name?.value)
        val retained = (firstPayload.retention as QueryResultRetention.Retained).reference
        val next = request.copy(from = QueryFromDocument.Result(retained), steps = bounded(listOf(hop)))
        val second = case.protocol.execute(next, fixture.authority, smallBudget, policy()) as OperationOutcome.Complete
        assertEquals(
            "end",
            (second.evidence.payload.items.values.single() as QueryResultItemDocument.ExactSymbol).name?.value,
        )
        assertEquals(1, case.descriptions, "The complete retained source must not describe its prefix again")
        assertEquals(listOf("subject", "middle"), case.reads)
        assertEquals(2, second.evidence.payload.relationObservations.values.size, "Both stage witnesses must survive")
    }

    @Test
    fun `one shared grant rejects the unsplit two hop question`() = runTest {
        val case = Stages()
        val rejection =
            completionRejection(
                case.protocol.execute(
                    request.copy(steps = bounded(listOf(hop, hop))),
                    fixture.authority,
                    smallBudget,
                    policy(),
                )
            )
        assertEquals(QueryCompletionUnprovenReason.INCOMPLETE_EXECUTION, rejection.reason)
        assertTrue(QueryLimitationDocument.WORK_LIMIT_REACHED in originalCoverage(rejection).limitations.values)
        assertEquals(listOf("subject"), case.reads)
        assertEquals(1, case.descriptions)
    }

    @Test
    fun `a rejected retained prefix cannot become a complete answer through a smaller stage`() = runTest {
        val case = Stages(partialFirst = true)
        val first =
            completionRejection(
                case.protocol.execute(
                    request.copy(steps = bounded(listOf(hop))),
                    fixture.authority,
                    smallBudget,
                    policy(),
                )
            )
        val next =
            request.copy(from = QueryFromDocument.Result(retainedEvidence(first).result), steps = bounded(listOf(hop)))
        val second = completionRejection(case.protocol.execute(next, fixture.authority, smallBudget, policy()))
        val coverage = assertInstanceOf(QueryCompletionCoverageDocument.Qualified::class.java, second.originalCoverage)
        assertTrue(QueryLimitationDocument.RELATION_INCOMPLETE in coverage.limitations.values)
        assertEquals(
            "end",
            (retainedEvidence(second).preview.values.single() as QueryResultItemDocument.ExactSymbol).name?.value,
        )
        assertEquals(1, case.descriptions)
        assertEquals(listOf("subject", "middle"), case.reads)
    }

    private inner class Stages(private val partialFirst: Boolean = false) {
        val reads = mutableListOf<String>()
        var descriptions = 0
        private val middle = RelationPagingFixture(fixture.authority, "middle").selector
        private val end = RelationPagingFixture(fixture.authority, "end").selector
        private val service =
            QueryService(
                discovery = SymbolDiscoveryOperations { error("Unexpected discovery") },
                exact =
                    object : SymbolExactOperations {
                        override suspend fun resolve(request: SymbolResolutionRequest): SymbolResolutionResult =
                            error("Unexpected resolution")

                        override suspend fun describe(request: ExactSymbolRequest): SymbolDescriptionResult {
                            assertEquals(1, ++descriptions, "Unexpected prefix replay")
                            assertEquals(fixture.selector.fingerprint, request.selector.fingerprint)
                            assertEquals(fixture.authority, request.selector.lease)
                            return SymbolDescriptionResult.Described(SymbolDescription.from(request.selector))
                        }
                    },
                source = SourceReadOperations { error("Unexpected source read") },
                relations =
                    RelationOperations { child ->
                        assertEquals(RelationMeaning.Callers, child.meaning)
                        assertEquals(RelationSearchBoundary.RETAINED_SUBJECT, child.boundary)
                        assertTrue(reads.size < 2, "Unexpected relation read")
                        val expected = if (reads.isEmpty()) fixture.selector else middle
                        val target = if (reads.isEmpty()) middle else end
                        assertEquals(
                            expected.fingerprint,
                            (child.subject as RelationEndpoint.Subject).selector.fingerprint,
                        )
                        reads += expected.name.value
                        val fact =
                            RelationFact.create(
                                    child,
                                    RelationEndpoint.subject(target),
                                    child.subject,
                                    RelationOccurrence.fromBoundary(target.file, 10, 11).refined(),
                                    RelationProvenance.K2_AUTHORED_SOURCE,
                                )
                                .refined()
                        val batch =
                            RelationBatch.create(
                                    child,
                                    listOf(fact),
                                    RelationByteCount.parse(fact.canonicalProjection().toByteArray().size.toLong())
                                        .refined(),
                                    RelationWorkCount.parse(1).refined(),
                                    RelationResultCount.parse(1).refined(),
                                )
                                .refined()
                        if (partialFirst && reads.size == 1) {
                            val partial =
                                RelationCompilation.qualifiedTerminal(
                                        batch,
                                        setOf(RelationLimitation.PROVIDER_INCOMPLETE),
                                    )
                                    .refined()
                            RelationReadResult.Qualified(batch, partial.coverage)
                        } else RelationReadResult.Complete(batch, RelationCompilation.complete(batch).coverage)
                    },
                traversal = TraversalOperations { error("Unexpected traversal") },
                traversalCeiling =
                    TraversalBudget(
                        budget.resources.resultLimit,
                        TraversalByteLimit.parse(100_000).refined(),
                        budget.resources.workUnitLimit,
                        budget.resources.elapsedTimeLimit,
                        TraversalDepthLimit.parse(1).refined(),
                        TraversalFrontierLimit.parse(1).refined(),
                        fixture.budget,
                    ),
                clock = QueryNanoClock { 0L },
            )
        val protocol = CanonicalQueryProtocol(service, fixture.references)
    }
}
