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
import io.github.amichne.kast.relation.contract.CallbackResultProjection
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Compiler-fact admission oracle; no native resolution or runtime activation is fabricated. */
internal class CompleteOnlyCallbackQueryTest : CompleteOnlyCallbackQueryCase() {
    @Test
    fun `complete callback graph without result membership cannot become a complete empty answer`() = runTest {
        val script =
            Script(
                listOf(emptyList()),
                resultForPage = { rows, _ ->
                    QueryResult(
                        QueryRows.Symbols.of(rows),
                        emptyList(),
                        relationObservations =
                            listOf(
                                observation(
                                    true,
                                    resultProjection = {
                                        CallbackResultProjection.Unproven(CallbackInvocationFlowCause.OUTSIDE_DOMAIN)
                                    },
                                )
                            ),
                    )
                },
            )
        val protocol = CanonicalQueryProtocol(script.operations, fixture.references)
        val rejected = completionRejection(protocol.execute(strict, fixture.authority, budget, policy()))
        assertEquals(
            io.github.amichne.kast.protocol.contract.QueryCallbackGraphCauseDocument.Unavailable(
                io.github.amichne.kast.protocol.contract.QueryCallbackFlowCauseDocument.OUTSIDE_DOMAIN
            ),
            rejected.callbackGraphFailure?.cause,
        )
        assertEquals(1, script.calls)
        script.assertDrained()
    }

    @Test
    fun `failed callback identifies exact source subject while proven rows remain evidence only`() = runTest {
        val script =
            Script(
                listOf(listOf(row)),
                resultForPage = { rows, _ ->
                    QueryResult(
                        QueryRows.Symbols.of(rows),
                        emptyList(),
                        relationObservations = listOf(observation(false)),
                    )
                },
            )
        val protocol = CanonicalQueryProtocol(script.operations, fixture.references)
        val rejection = completionRejection(protocol.execute(strict, fixture.authority, budget, policy()))
        val graph =
            Json.encodeToJsonElement(
                    io.github.amichne.kast.protocol.contract.QueryCallbackGraphFailureDocument.serializer(),
                    assertInstanceOf(
                        io.github.amichne.kast.protocol.contract.QueryCallbackGraphFailureDocument::class.java,
                        rejection.callbackGraphFailure,
                    ),
                )
                .jsonObject
        val subject = graph.getValue("subject").jsonObject
        assertEquals(setOf("type", "occurrence", "body"), subject.keys)
        assertEquals("CALLBACK", subject.getValue("type").jsonPrimitive.content)
        val occurrence = subject.getValue("occurrence").jsonObject
        assertEquals(fixture.selector.file.stableValue, occurrence.getValue("file").jsonPrimitive.content)
        assertEquals("2", occurrence.getValue("start").jsonPrimitive.content)
        assertEquals("3", occurrence.getValue("end").jsonPrimitive.content)
        val body = subject.getValue("body").jsonObject
        assertEquals("1", body.getValue("start").jsonPrimitive.content)
        assertEquals("4", body.getValue("end").jsonPrimitive.content)
        val retained = retainedEvidence(rejection)
        assertEquals(1, retained.preview.values.size)
        val read = protocol.executePage(retained.readRequest(), fixture.authority, budget) as OperationOutcome.Qualified
        assertEquals(1, read.evidence.payload.items.values.size)
        assertTrue(read.evidence.payload.interpretation is QueryResultInterpretationDocument.EvidenceOnly)
        assertEquals(1, script.calls, "Retained evidence must never repeat semantic work")
        script.assertDrained()
    }

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
        val presented =
            io.github.amichne.kast.protocol.wire.presentation.CanonicalQueryCliDocuments.project(result)
                as io.github.amichne.kast.protocol.wire.presentation.ProjectedOperationOutcome.Complete
        assertTrue(
            presented.document.value.contains("\"navigation\""),
            "A complete callback query must expose its admitted navigation, not only nested proof witnesses",
        )
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
        val callback = read.evidence.payload.relationObservations.values.single().callbackObservations.values.single()
        val navigation =
            callback.navigation as io.github.amichne.kast.protocol.contract.QueryCallbackNavigationDocument.Unproven
        assertEquals(interpretation.callbackGraphFailure?.cause, navigation.cause)
        assertTrue(navigation.prefix.values.isEmpty())
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
        val subject =
            assertInstanceOf(
                io.github.amichne.kast.protocol.contract.QueryCallbackGraphSubjectDocument.Callback::class.java,
                rejection.callbackGraphFailure?.subject,
            )
        assertEquals(fixture.selector.file.stableValue, subject.occurrence.file.value)
        assertEquals(2, subject.occurrence.range.startInclusive.value)
        assertEquals(3, subject.occurrence.range.endExclusive.value)
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
        val subject = graph.getValue("subject").jsonObject
        assertEquals("CALLBACK", subject.getValue("type").jsonPrimitive.content)
        assertEquals(
            fixture.selector.file.stableValue,
            subject.getValue("occurrence").jsonObject.getValue("file").jsonPrimitive.content,
        )
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
