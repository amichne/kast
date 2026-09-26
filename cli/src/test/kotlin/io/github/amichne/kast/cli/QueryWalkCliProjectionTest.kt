package io.github.amichne.kast.cli

import io.github.amichne.kast.kernel.EvidenceBasis
import io.github.amichne.kast.kernel.EvidenceEnvelope
import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.CanonicalOperation
import io.github.amichne.kast.protocol.contract.ProtocolCount
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.contract.QueryExpandedFrontierDocument
import io.github.amichne.kast.protocol.contract.QueryItemFailureDocument
import io.github.amichne.kast.protocol.contract.QueryKnownMinimum
import io.github.amichne.kast.protocol.contract.QueryLimitationDocument
import io.github.amichne.kast.protocol.contract.QueryQualifiedProgressDocument
import io.github.amichne.kast.protocol.contract.QueryReferenceDocument
import io.github.amichne.kast.protocol.contract.QueryResultItemDocument
import io.github.amichne.kast.protocol.contract.QueryRunQualification
import io.github.amichne.kast.protocol.contract.QueryRunResult
import io.github.amichne.kast.protocol.contract.QueryTerminalReasonDocument
import io.github.amichne.kast.protocol.contract.QueryWalkCoverageDocument
import io.github.amichne.kast.protocol.contract.QueryWalkObservationDocument
import io.github.amichne.kast.protocol.contract.RelationKindDocument
import io.github.amichne.kast.protocol.contract.RelationLimitationDocument
import io.github.amichne.kast.protocol.contract.TraversalDepthDocument
import io.github.amichne.kast.protocol.contract.TraversalExpansionRemainderDocument
import io.github.amichne.kast.protocol.contract.TraversalLimitationDocument
import io.github.amichne.kast.protocol.contract.TraversalPartialExpansionDocument
import io.github.amichne.kast.protocol.contract.TraversalProgressDocument
import io.github.amichne.kast.protocol.contract.TraversalStrategyDocument
import io.github.amichne.kast.protocol.wire.presentation.ProjectedOperationOutcome
import io.github.amichne.kast.protocol.wire.presentation.queryRunCliProjector
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class QueryWalkCliProjectionTest {
    @Test
    fun `qualified query walk preserves partial expansion and incomplete coverage in installed schema`() {
        val outcome = qualifiedWalk()
        val projected = queryRunCliProjector.project(outcome) as ProjectedOperationOutcome.Qualified
        val document = Json.parseToJsonElement(projected.document.value).jsonObject
        with(LiveReadOutputSchemaTest()) { assertAdmits(CanonicalOperation.QUERY_RUN, document) }
        val walk = document.getValue("walk_observations").jsonArray.single().jsonObject
        assertEquals("exact:v2:fixture-node", walk.getValue("subject").jsonPrimitive.content)
        assertEquals("2", walk.getValue("maximum_depth").jsonPrimitive.content)
        assertEquals("1", walk.getValue("expanded_frontier").jsonPrimitive.content)
        assertEquals("terminal_incomplete", walk.getValue("coverage").jsonObject.getValue("kind").jsonPrimitive.content)
        val expansion = walk.getValue("partial_expansions").jsonArray.single().jsonObject
        assertEquals("exact:v2:fixture-node", expansion.getValue("subject").jsonPrimitive.content)
        assertEquals("not_explored", expansion.getValue("remainder").jsonPrimitive.content)
        assertEquals("page", expansion.getValue("scope").jsonPrimitive.content)
    }

    private fun qualifiedWalk(): OperationOutcome.Qualified<QueryRunResult, QueryRunQualification> {
        val exact = ProtocolText.parse("exact:v2:fixture-node").value()
        val partial =
            TraversalPartialExpansionDocument.create(
                    exact,
                    TraversalDepthDocument.parse(0).value(),
                    listOf(RelationLimitationDocument.RESULT_LIMIT_REACHED),
                    TraversalExpansionRemainderDocument.NOT_EXPLORED,
                )
                .value()
        val observation =
            QueryWalkObservationDocument(
                QueryReferenceDocument.ExactSymbol(exact),
                RelationKindDocument.CALLERS,
                ProtocolCount.parse(2).value(),
                QueryExpandedFrontierDocument.parse(1).value(),
                TraversalProgressDocument(1, 1, 0, 0),
                TraversalStrategyDocument.BreadthFirst,
                BoundedProtocolList.create(listOf(partial)).value(),
                QueryWalkCoverageDocument.terminalIncomplete(
                        listOf(TraversalLimitationDocument.ONE_HOP_INCOMPLETE),
                        listOf(RelationLimitationDocument.RESULT_LIMIT_REACHED),
                    )
                    .value(),
            )
        val result =
            QueryRunResult(
                BoundedProtocolList.create(emptyList<QueryResultItemDocument>()).value(),
                BoundedProtocolList.create(emptyList<QueryItemFailureDocument>()).value(),
                walkObservations = BoundedProtocolList.create(listOf(observation)).value(),
            )
        val outcome =
            OperationOutcome.Qualified(
                EvidenceEnvelope(
                    CanonicalOperation.QUERY_RUN.id,
                    EvidenceBasis.Published(EvidenceGeneration.parse(1).value()),
                    result,
                ),
                QueryRunQualification.create(
                        QueryKnownMinimum.parse(0).value(),
                        listOf(QueryLimitationDocument.TRAVERSAL_INCOMPLETE),
                        QueryQualifiedProgressDocument.TerminalIncomplete(
                            QueryTerminalReasonDocument.UPSTREAM_INCOMPLETE
                        ),
                    )
                    .value(),
            )
        return outcome
    }

    private fun <T, F> Refinement<T, F>.value(): T = (this as Refinement.Refined).value
}
