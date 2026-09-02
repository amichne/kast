package io.github.amichne.kast.runtime.ide.read.revalidation.dispatch

import io.github.amichne.kast.kernel.KastObservability
import io.github.amichne.kast.kernel.KastSpanCompletion
import io.github.amichne.kast.kernel.KastSpanCount
import io.github.amichne.kast.kernel.KastSpanMeasurement
import io.github.amichne.kast.kernel.KastSpanName
import io.github.amichne.kast.kernel.KastSpanObservation
import io.github.amichne.kast.kernel.KastTraceSpan
import io.github.amichne.kast.protocol.wire.CanonicalOperationWireBindings
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class IdeReadRuntimeDispatchTest {
    @Test
    fun `exact four codecs route to one nominal port and preserve legal outcomes`() = runSuspend {
        val workspaceOutcome = workspaceComplete()
        val discoverOutcome = discoverQualified()
        val resolveOutcome = resolveComplete()
        val describeOutcome = describeRejected()
        val fixture = RecordingIdeReadPorts(
            workspaceOutcome = workspaceOutcome,
            discoverOutcome = discoverOutcome,
            resolveOutcome = resolveOutcome,
            describeOutcome = describeOutcome,
        )

        val workspaceRequest = workspaceRequest()
        val workspaceResponse = fixture.dispatch.dispatch(
            CanonicalOperationWireBindings.workspaceInspect.requestDocument(workspaceRequest),
        ).responseDocument()
        assertEquals(
            workspaceOutcome,
            CanonicalOperationWireBindings.workspaceInspect
                .decodeOutcome(workspaceResponse)
                .decodedValue(),
        )
        assertEquals(IdeReadPortCalls(workspaceInspect = 1), fixture.calls)
        assertEquals(listOf(workspaceRequest), fixture.workspaceRequests)

        val discoverRequest = discoverRequest()
        val trace = RecordingReadObservability()
        val discoverResponse = fixture.dispatch.dispatch(
            CanonicalOperationWireBindings.symbolDiscover.requestDocument(discoverRequest),
            trace,
        ).responseDocument()
        assertEquals(
            discoverOutcome,
            CanonicalOperationWireBindings.symbolDiscover
                .decodeOutcome(discoverResponse)
                .decodedValue(),
        )
        assertEquals(
            IdeReadPortCalls(workspaceInspect = 1, symbolDiscover = 1),
            fixture.calls,
        )
        assertEquals(listOf(discoverRequest), fixture.discoverRequests)
        assertEquals(listOf(KastSpanName.SYMBOL_DISCOVERY), trace.names)
        assertEquals(
            listOf(
                KastSpanObservation(
                    KastSpanCompletion.Qualified,
                    setOf(
                        KastSpanMeasurement.RecordCount(KastSpanCount.parse(1L).refinedValue()),
                    ),
                ),
            ),
            trace.observations,
        )

        val resolveRequest = resolveRequest()
        val resolveResponse = fixture.dispatch.dispatch(
            CanonicalOperationWireBindings.symbolResolve.requestDocument(resolveRequest),
        ).responseDocument()
        assertEquals(
            resolveOutcome,
            CanonicalOperationWireBindings.symbolResolve
                .decodeOutcome(resolveResponse)
                .decodedValue(),
        )
        assertEquals(
            IdeReadPortCalls(workspaceInspect = 1, symbolDiscover = 1, symbolResolve = 1),
            fixture.calls,
        )
        assertEquals(listOf(resolveRequest), fixture.resolveRequests)

        val describeRequest = describeRequest()
        val describeResponse = fixture.dispatch.dispatch(
            CanonicalOperationWireBindings.symbolDescribe.requestDocument(describeRequest),
        ).responseDocument()
        assertEquals(
            describeOutcome,
            CanonicalOperationWireBindings.symbolDescribe
                .decodeOutcome(describeResponse)
                .decodedValue(),
        )
        assertEquals(
            IdeReadPortCalls(
                workspaceInspect = 1,
                symbolDiscover = 1,
                symbolResolve = 1,
                symbolDescribe = 1,
            ),
            fixture.calls,
        )
        assertEquals(listOf(describeRequest), fixture.describeRequests)
    }
}

private class RecordingReadObservability : KastObservability {
    val names = mutableListOf<KastSpanName>()
    val observations = mutableListOf<KastSpanObservation>()

    override suspend fun <Value> inSpan(
        name: KastSpanName,
        operation: suspend (KastTraceSpan) -> Value,
    ): Value {
        names += name
        return operation(
            object : KastTraceSpan {
                override suspend fun <Nested> child(
                    name: KastSpanName,
                    operation: suspend (KastTraceSpan) -> Nested,
                ): Nested = error("Hosted symbol discovery does not create child spans")

                override fun observe(observation: KastSpanObservation) {
                    observations += observation
                }
            },
        )
    }
}
