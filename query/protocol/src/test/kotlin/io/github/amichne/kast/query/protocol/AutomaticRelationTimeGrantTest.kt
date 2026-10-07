package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.protocol.contract.QueryInvocationStop
import io.github.amichne.kast.protocol.contract.QueryResultItemDocument
import io.github.amichne.kast.protocol.contract.QueryStepDocument
import io.github.amichne.kast.protocol.contract.RelationKindDocument
import io.github.amichne.kast.query.service.QueryNanoClock
import io.github.amichne.kast.query.service.QueryService
import io.github.amichne.kast.relation.contract.RelationBatch
import io.github.amichne.kast.relation.contract.RelationBudget
import io.github.amichne.kast.relation.contract.RelationByteCount
import io.github.amichne.kast.relation.contract.RelationByteLimit
import io.github.amichne.kast.relation.contract.RelationCompilation
import io.github.amichne.kast.relation.contract.RelationEndpoint
import io.github.amichne.kast.relation.contract.RelationFact
import io.github.amichne.kast.relation.contract.RelationMeaning
import io.github.amichne.kast.relation.contract.RelationOccurrence
import io.github.amichne.kast.relation.contract.RelationOperations
import io.github.amichne.kast.relation.contract.RelationProvenance
import io.github.amichne.kast.relation.contract.RelationReadResult
import io.github.amichne.kast.relation.contract.RelationRequest
import io.github.amichne.kast.relation.contract.RelationResultCount
import io.github.amichne.kast.relation.contract.RelationWorkCount
import io.github.amichne.kast.source.contract.SourceReadOperations
import io.github.amichne.kast.symbol.contract.ExactSymbolRequest
import io.github.amichne.kast.symbol.contract.SymbolDescription
import io.github.amichne.kast.symbol.contract.SymbolDescriptionResult
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryOperations
import io.github.amichne.kast.symbol.contract.SymbolExactOperations
import io.github.amichne.kast.symbol.contract.SymbolResolutionRequest
import io.github.amichne.kast.symbol.contract.SymbolResolutionResult
import io.github.amichne.kast.symbol.contract.SymbolSelector
import io.github.amichne.kast.traversal.contract.TraversalBudget
import io.github.amichne.kast.traversal.contract.TraversalByteLimit
import io.github.amichne.kast.traversal.contract.TraversalDepthLimit
import io.github.amichne.kast.traversal.contract.TraversalFrontierLimit
import io.github.amichne.kast.traversal.contract.TraversalOperations
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

/** Native observations are scripted; the invocation runner, query scheduler and child grant are production. */
internal class AutomaticRelationTimeGrantTest : AutomaticSymbolQueryCase() {
    @Test
    fun `slow exact revalidation leaves the full remaining grant for a direct caller read`() = runTest {
        var now = 0L
        var descriptions = 0
        var relations = 0
        val caller = RelationPagingFixture(fixture.authority, "caller").selector
        val service =
            QueryService(
                discovery = SymbolDiscoveryOperations { error("Unexpected discovery") },
                exact =
                    object : SymbolExactOperations {
                        override suspend fun resolve(request: SymbolResolutionRequest): SymbolResolutionResult =
                            error("Unexpected resolution")

                        override suspend fun describe(request: ExactSymbolRequest): SymbolDescriptionResult {
                            assertEquals(1, ++descriptions, "Unexpected exact revalidation")
                            assertEquals(fixture.selector.fingerprint, request.selector.fingerprint)
                            assertEquals(fixture.authority, request.selector.lease)
                            now = 600_000_000L
                            return SymbolDescriptionResult.Described(SymbolDescription.from(request.selector))
                        }
                    },
                source = SourceReadOperations { error("Unexpected source read") },
                relations =
                    RelationOperations { child ->
                        assertEquals(1, ++relations, "Unexpected relation read")
                        assertEquals(RelationMeaning.Callers, child.meaning)
                        assertEquals(
                            fixture.selector.fingerprint,
                            (child.subject as RelationEndpoint.Subject).selector.fingerprint,
                        )
                        assertEquals(400L, child.budget.resources.elapsedTimeLimit.value)
                        now = 900_000_000L
                        callerPage(child, caller)
                    },
                traversal = TraversalOperations { error("Unexpected traversal") },
                traversalCeiling = traversalCeiling(),
                clock = QueryNanoClock { now },
            )
        val result =
            CanonicalQueryProtocol(service, fixture.references)
                .executeAutomatically(
                    request.copy(steps = bounded(listOf(QueryStepDocument.Related(RelationKindDocument.CALLERS)))),
                    fixture.authority,
                    budget,
                    policy(nanoTime = { now }),
                )
        assertInstanceOf(OperationOutcome.Complete::class.java, result)
        val complete = result as OperationOutcome.Complete
        assertEquals(1, complete.evidence.payload.items.values.size)
        val returned = complete.evidence.payload.items.values.single() as QueryResultItemDocument.ExactSymbol
        assertEquals("caller", returned.name?.value)
        assertEquals(QueryInvocationStop.COMPLETED, complete.evidence.payload.invocation!!.stop)
        assertEquals(1, descriptions, "Unconsumed exact observation")
        assertEquals(1, relations, "Unconsumed caller observation")
    }

    private fun callerPage(child: RelationRequest, caller: SymbolSelector): RelationReadResult {
        val fact =
            RelationFact.create(
                    child,
                    RelationEndpoint.subject(caller),
                    child.subject,
                    RelationOccurrence.fromBoundary(caller.file, 10, 11).refined(),
                    RelationProvenance.K2_AUTHORED_SOURCE,
                )
                .refined()
        val batch =
            RelationBatch.create(
                    child,
                    listOf(fact),
                    RelationByteCount.parse(fact.canonicalProjection().toByteArray().size.toLong()).refined(),
                    RelationWorkCount.parse(1L).refined(),
                    RelationResultCount.parse(1).refined(),
                )
                .refined()
        return RelationReadResult.Complete(batch, RelationCompilation.complete(batch).coverage)
    }

    private fun traversalCeiling() =
        TraversalBudget(
            budget.resources.resultLimit,
            TraversalByteLimit.parse(100_000L).refined(),
            budget.resources.workUnitLimit,
            budget.resources.elapsedTimeLimit,
            TraversalDepthLimit.parse(1).refined(),
            TraversalFrontierLimit.parse(1).refined(),
            RelationBudget(budget.resources, RelationByteLimit.parse(100_000L).refined()),
        )
}
