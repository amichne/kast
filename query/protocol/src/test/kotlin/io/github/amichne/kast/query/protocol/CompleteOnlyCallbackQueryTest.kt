package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.protocol.contract.QueryCompletionCoverageDocument
import io.github.amichne.kast.protocol.contract.QueryCompletionEvidenceDocument
import io.github.amichne.kast.protocol.contract.QueryCompletionUnprovenReason
import io.github.amichne.kast.protocol.contract.QueryLimitationDocument
import io.github.amichne.kast.protocol.contract.QueryResultInterpretationDocument
import io.github.amichne.kast.protocol.contract.QueryRunRejection
import io.github.amichne.kast.protocol.contract.QueryRunRequest
import io.github.amichne.kast.query.contract.QueryResult
import io.github.amichne.kast.query.contract.QueryRows
import io.github.amichne.kast.relation.contract.CallbackInvocationFlowCause
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Compiler-fact admission oracle; no native resolution or runtime activation is fabricated. */
internal class CompleteOnlyCallbackQueryTest : CompleteOnlyCallbackQueryCase() {
    @Test
    fun `exhaustive bound callback graph admits complete static answer`() = runTest {
        val script =
            Script(
                listOf(emptyList()),
                resultForPage = { rows, _ ->
                    QueryResult(
                        QueryRows.Symbols.of(rows),
                        emptyList(),
                        relationObservations = listOf(observation(true)),
                    )
                },
            )
        val protocol = CanonicalQueryProtocol(script.operations, fixture.references)
        val result = protocol.execute(strict, fixture.authority, budget, policy())
        assertTrue(result is OperationOutcome.Complete)
        script.assertDrained()
    }

    @Test
    fun `rejected raw complete callback evidence cannot be read as an accepted answer`() = runTest {
        val script =
            Script(
                listOf(emptyList()),
                resultForPage = { rows, _ ->
                    QueryResult(
                        QueryRows.Symbols.of(rows),
                        emptyList(),
                        relationObservations = listOf(observation(false)),
                    )
                },
            )
        val protocol = CanonicalQueryProtocol(script.operations, fixture.references)
        val result = protocol.execute(strict, fixture.authority, budget, policy()) as OperationOutcome.Rejected
        val rejected = result.reason as QueryRunRejection.CompletionUnproven
        assertEquals(QueryCompletionCoverageDocument.Complete, rejected.originalCoverage)
        val handle = (rejected.evidence as QueryCompletionEvidenceDocument.Retained).result
        val read =
            protocol.executePage(QueryRunRequest.ReadResult.symbols(handle, output = output), fixture.authority, budget)
                as OperationOutcome.Qualified
        assertTrue(QueryLimitationDocument.STATIC_MODEL_UNPROVEN in read.qualification.limitations)
        val interpretation = read.evidence.payload.interpretation as QueryResultInterpretationDocument.EvidenceOnly
        assertEquals(QueryCompletionCoverageDocument.Complete, interpretation.originalCoverage)
        assertEquals(QueryCompletionUnprovenReason.CALLBACK_GRAPH_UNPROVEN, interpretation.reason)
        assertEquals(rejected.callbackGraphFailure, interpretation.callbackGraphFailure)
        assertEquals(
            io.github.amichne.kast.protocol.contract.QueryCallbackGraphCauseDocument.Unavailable(
                io.github.amichne.kast.protocol.contract.QueryCallbackFlowCauseDocument.STORED_CALLBACK
            ),
            interpretation.callbackGraphFailure?.cause,
        )
        assertEquals(
            1,
            read.evidence.payload.relationObservations.values.flatMap { it.callbackObservations.values }.size,
        )
        script.assertDrained()
    }

    @Test
    fun `walk callback obligations cannot bypass complete only policy`() = runTest {
        val walk = walkObservation()
        val script =
            Script(
                listOf(emptyList()),
                resultForPage = { rows, _ ->
                    QueryResult(QueryRows.Symbols.of(rows), emptyList(), walkObservations = listOf(walk))
                },
            )
        val protocol = CanonicalQueryProtocol(script.operations, fixture.references)
        val result = protocol.execute(strict, fixture.authority, budget, policy()) as OperationOutcome.Rejected
        val rejection = result.reason as QueryRunRejection.CompletionUnproven
        assertEquals(QueryCompletionUnprovenReason.CALLBACK_GRAPH_UNPROVEN, rejection.reason)
        assertEquals(
            io.github.amichne.kast.protocol.contract.QueryCallbackObservationOrigin.WALK,
            rejection.callbackGraphFailure?.origin,
        )
        assertEquals(
            io.github.amichne.kast.protocol.contract.QueryCallbackGraphCauseDocument.Unavailable(
                io.github.amichne.kast.protocol.contract.QueryCallbackFlowCauseDocument.STORED_CALLBACK
            ),
            rejection.callbackGraphFailure?.cause,
        )
        script.assertDrained()
    }

    @Test
    fun `unavailable retention preserves exact known callback cause in encoded rejection`() = runTest {
        val script =
            Script(
                listOf(emptyList()),
                resultForPage = { rows, _ ->
                    QueryResult(
                        QueryRows.Symbols.of(rows),
                        emptyList(),
                        relationObservations = listOf(observation(false)),
                    )
                },
            )
        val store = QueryStateStore()
        val protocol =
            CanonicalQueryProtocol(
                script.operations,
                fixture.references,
                store,
                retentionObservation =
                    QueryResultRetentionObservation { event ->
                        if (event is QueryResultRetentionEvidence.CaptureStarted) store.retire()
                    },
            )
        val outcome = protocol.execute(strict, fixture.authority, budget, policy()) as OperationOutcome.Rejected
        val rejection = outcome.reason as QueryRunRejection.CompletionUnproven
        assertTrue(rejection.evidence is QueryCompletionEvidenceDocument.Unavailable)
        val projected =
            io.github.amichne.kast.protocol.wire.presentation.CanonicalQueryCliDocuments.project(
                OperationOutcome.Rejected(rejection)
            ) as io.github.amichne.kast.protocol.wire.presentation.ProjectedOperationOutcome.Rejected
        val encoded =
            Json.parseToJsonElement(projected.document.value)
                .jsonObject
                .getValue("rejection")
                .jsonObject
                .getValue("detail")
                .jsonObject
        val graph = encoded.getValue("cause").jsonObject.getValue("graphFailure").jsonObject
        assertTrue("cause" in graph)
        assertEquals("STORED_CALLBACK", graph.getValue("cause").jsonObject.getValue("cause").jsonPrimitive.content)
        script.assertDrained()
    }

    @Test
    fun `unresolved callback obligations survive graph rejection metadata`() = runTest {
        val script =
            Script(
                listOf(emptyList()),
                resultForPage = { rows, _ ->
                    QueryResult(
                        QueryRows.Symbols.of(rows),
                        emptyList(),
                        relationObservations =
                            listOf(observation(true, setOf(CallbackInvocationFlowCause.RETURNED_CALLBACK))),
                    )
                },
            )
        val protocol = CanonicalQueryProtocol(script.operations, fixture.references)
        val outcome = protocol.execute(strict, fixture.authority, budget, policy()) as OperationOutcome.Rejected
        val rejection = outcome.reason as QueryRunRejection.CompletionUnproven
        val cause =
            rejection.callbackGraphFailure?.cause
                as io.github.amichne.kast.protocol.contract.QueryCallbackGraphCauseDocument.Unresolved
        assertEquals(
            listOf(io.github.amichne.kast.protocol.contract.QueryCallbackFlowCauseDocument.RETURNED_CALLBACK),
            cause.obligations.values,
        )
        assertEquals(
            io.github.amichne.kast.protocol.contract.QueryCallbackInvocationScanDocument.INCOMPLETE,
            cause.scan,
        )
        script.assertDrained()
    }
}
