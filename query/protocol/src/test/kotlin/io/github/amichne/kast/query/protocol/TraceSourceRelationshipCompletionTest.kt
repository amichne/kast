package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.protocol.contract.QueryExpansionScopeDocument
import io.github.amichne.kast.protocol.contract.QueryResultInterpretationDocument
import io.github.amichne.kast.protocol.contract.QueryResultRetention
import io.github.amichne.kast.protocol.contract.QueryRetentionModeDocument
import io.github.amichne.kast.protocol.contract.QueryRunRequest
import io.github.amichne.kast.protocol.contract.QueryStepDocument
import io.github.amichne.kast.protocol.contract.RelationKindDocument
import io.github.amichne.kast.query.contract.QueryResult
import io.github.amichne.kast.query.contract.QueryRows
import io.github.amichne.kast.relation.contract.CallbackInvocationFlowCause
import io.github.amichne.kast.relation.contract.CallbackResultProjection
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

internal class TraceSourceRelationshipCompletionTest : CompleteOnlyCallbackQueryCase() {
    private val trace
        get() = strict.copy(steps = bounded(listOf(QueryStepDocument.Trace(QueryExpansionScopeDocument.Workspace))))

    @Test
    fun `trace preserves known source relationships when callback execution is unknown`() = runTest {
        val callback = observation(false)
        val script = script(callback)
        val protocol = CanonicalQueryProtocol(script.operations, fixture.references)
        val outcome =
            protocol.execute(
                trace.copy(retention = QueryRetentionModeDocument.RETAIN),
                fixture.authority,
                budget,
                policy(),
            )
        assertTrue(outcome is OperationOutcome.Complete, "Expected source relationships, received $outcome")
        val result = (outcome as OperationOutcome.Complete).evidence.payload
        assertEquals(QueryResultInterpretationDocument.SourceRelationships(), result.interpretation)
        assertEquals(1, result.relationObservations.values.single().callbackObservations.values.size)
        val reference = (result.retention as QueryResultRetention.Retained).reference
        val read =
            protocol.executePage(
                QueryRunRequest.ReadResult.symbols(reference, output = output),
                fixture.authority,
                budget,
            )
        assertTrue(read is OperationOutcome.Complete)
        val retained = (read as OperationOutcome.Complete).evidence.payload
        assertEquals(result.relationObservations, retained.relationObservations)
        assertEquals(result.interpretation, retained.interpretation)
        val emptyEvidence =
            protocol.executePage(
                QueryRunRequest.ReadResult.symbols(
                    reference,
                    output = output,
                    evidenceCursor = retained.evidenceWindow!!.end,
                ),
                fixture.authority,
                budget,
            )
        assertTrue(emptyEvidence is OperationOutcome.Complete)
        val emptyPage = (emptyEvidence as OperationOutcome.Complete).evidence.payload
        assertTrue(emptyPage.relationObservations.values.isEmpty())
        assertEquals(result.interpretation, emptyPage.interpretation)
        assertEquals(1, script.calls)
        script.assertDrained()
    }

    @Test
    fun `trace does not convert missing source membership to an empty complete result`() = runTest {
        val script =
            script(
                observation(
                    false,
                    resultProjection = {
                        CallbackResultProjection.Unproven(CallbackInvocationFlowCause.OUTSIDE_DOMAIN)
                    },
                )
            )
        val protocol = CanonicalQueryProtocol(script.operations, fixture.references)
        completionRejection(protocol.execute(trace, fixture.authority, budget, policy()))
        script.assertDrained()
    }

    @Test
    fun `trace rejects unresolved compiler mapping`() = runTest {
        val script = script(observation(true, setOf(CallbackInvocationFlowCause.UNRESOLVED_ARGUMENT_MAPPING)))
        val protocol = CanonicalQueryProtocol(script.operations, fixture.references)
        completionRejection(protocol.execute(trace, fixture.authority, budget, policy()))
        script.assertDrained()
    }

    @Test
    fun `trace does not weaken an explicit invocation operation`() = runTest {
        val script = script(observation(false))
        val protocol = CanonicalQueryProtocol(script.operations, fixture.references)
        val composed =
            trace.copy(
                steps =
                    bounded(listOf(QueryStepDocument.Related(RelationKindDocument.CALLEES), QueryStepDocument.Trace()))
            )
        completionRejection(protocol.execute(composed, fixture.authority, budget, policy()))
        script.assertDrained()
    }

    @Test
    fun `trace still rejects unfinished source enumeration`() = runTest {
        val script =
            Script(
                listOf(listOf(row)),
                terminal = true,
                resultForPage = { rows, _ ->
                    QueryResult(
                        QueryRows.Symbols.of(rows),
                        emptyList(),
                        relationObservations = listOf(observation(false)),
                    )
                },
            )
        val protocol = CanonicalQueryProtocol(script.operations, fixture.references)
        val rejection = completionRejection(protocol.execute(trace, fixture.authority, budget, policy()))
        assertEquals(
            io.github.amichne.kast.protocol.contract.QueryCompletionUnprovenReason.INCOMPLETE_EXECUTION,
            rejection.reason,
        )
        script.assertDrained()
    }

    @Test
    fun `execution gaps remain evidence without preventing source completion`() = runTest {
        for (cause in
            listOf(
                CallbackInvocationFlowCause.FINALLY_UNSUPPORTED,
                CallbackInvocationFlowCause.ABRUPT_COMPLETION,
                CallbackInvocationFlowCause.STORED_CALLBACK,
                CallbackInvocationFlowCause.RETURNED_CALLBACK,
                CallbackInvocationFlowCause.UNSUPPORTED_CALLBACK_SUPPLY,
                CallbackInvocationFlowCause.EXTERNAL_CALLABLE,
                CallbackInvocationFlowCause.OUTSIDE_DOMAIN,
                CallbackInvocationFlowCause.DEPENDENCY_CONTRACT_EFFECT_UNPROVEN,
                CallbackInvocationFlowCause.DEPENDENCY_DEFERRED_MODEL_UNPROVEN,
                CallbackInvocationFlowCause.PARAMETER_ESCAPES,
                CallbackInvocationFlowCause.CALLBACK_CYCLE,
                CallbackInvocationFlowCause.NESTED_CALLBACK_EXECUTION,
                CallbackInvocationFlowCause.NO_INVOCATION_PROVEN,
            )) {

            val script = script(observation(true, setOf(cause)))
            val protocol = CanonicalQueryProtocol(script.operations, fixture.references)
            val outcome = protocol.execute(trace, fixture.authority, budget, policy())
            assertTrue(outcome is OperationOutcome.Complete, cause.name)
            val callback =
                (outcome as OperationOutcome.Complete)
                    .evidence
                    .payload
                    .relationObservations
                    .values
                    .single()
                    .callbackObservations
                    .values
                    .single()
            val flow = callback.flow as io.github.amichne.kast.protocol.contract.QueryCallbackFlowDocument.Observed
            assertEquals(
                listOf(io.github.amichne.kast.protocol.contract.QueryCallbackFlowCauseDocument.valueOf(cause.name)),
                flow.obligations.values,
            )
            script.assertDrained()
        }
    }

    @Test
    fun `source and resource gaps still reject trace`() = runTest {
        for (cause in
            listOf(
                CallbackInvocationFlowCause.ANONYMOUS_IDENTITY_UNAVAILABLE,
                CallbackInvocationFlowCause.UNRESOLVED_PARAMETER_REFERENCE,
                CallbackInvocationFlowCause.DEPENDENCY_BINARY_UNAVAILABLE,
                CallbackInvocationFlowCause.DEPENDENCY_BINARY_CHANGED,
                CallbackInvocationFlowCause.DEPENDENCY_TARGET_UNAVAILABLE,
                CallbackInvocationFlowCause.WORK_LIMIT_REACHED,
                CallbackInvocationFlowCause.TIME_LIMIT_REACHED,
                CallbackInvocationFlowCause.RESULT_LIMIT_REACHED,
                CallbackInvocationFlowCause.BYTE_LIMIT_REACHED,
            )) {

            val script = script(observation(true, setOf(cause)))
            val protocol = CanonicalQueryProtocol(script.operations, fixture.references)
            completionRejection(protocol.execute(trace, fixture.authority, budget, policy()))
            script.assertDrained()
        }
    }

    private fun script(observation: io.github.amichne.kast.query.contract.QueryRelationObservation) =
        Script(
            listOf(listOf(row)),
            resultForPage = { rows, _ ->
                QueryResult(QueryRows.Symbols.of(rows), emptyList(), relationObservations = listOf(observation))
            },
        )
}
