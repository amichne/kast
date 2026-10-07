package io.github.amichne.kast.protocol.wire

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.ImpactEvidenceRevisionDocument
import io.github.amichne.kast.protocol.contract.ImpactSemanticBasisDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackFlowCauseDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackInvocationScanDocument
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class QueryCallbackForwardingGraphWireTest {
    private val fixture = QueryCallbackRefinementWireFixture()

    @Test
    fun `exhausted graph encodes its finite proof and preserves it after admission`() {
        val wire = fixture.exhaustedGraphObservation()
        fixture.assertAdmitted(wire)
        val encoded = wireJson.encodeToJsonElement(QueryCallbackObservationWireDocument.serializer(), wire).jsonObject
        val forwarding = encoded.getValue("flow").jsonObject.getValue("forwarding").jsonObject
        assertEquals(setOf("type", "root", "formals", "forwardings"), forwarding.keys)
        assertEquals("EXHAUSTED_GRAPH", forwarding.getValue("type").jsonPrimitive.content)
        val converted = fixture.decode(wire).toContract() as WireDocumentConversion.Converted
        assertEquals(fixture.flow(wire).forwarding, fixture.flow(converted.value.toWireDocument()).forwarding)
    }

    @Test
    fun `invocation route evidence encodes its required discriminator with no graph fields`() {
        val wire = fixture.base()
        val encoded = wireJson.encodeToJsonElement(QueryCallbackObservationWireDocument.serializer(), wire).jsonObject
        val forwarding = encoded.getValue("flow").jsonObject.getValue("forwarding").jsonObject
        assertEquals(setOf("type"), forwarding.keys)
        assertEquals("INVOCATION_ROUTES", forwarding.getValue("type").jsonPrimitive.content)
    }

    @Test
    fun `finite recursive graph retains its back edge without unrolling invocation witnesses`() {
        val wire = fixture.recursiveGraphObservation()
        fixture.assertAdmitted(wire)
        val flow = fixture.flow(fixture.decode(wire))
        val graph = flow.forwarding as QueryCallbackForwardingEvidenceWireDocument.ExhaustedGraph
        assertEquals(2, graph.formals.size)
        assertEquals(2, graph.forwardings.size)
        assertEquals(1, flow.invocations.single().forwardings.size)
        fixture.assertAdmitted(wire.copy(flow = flow.copy(invocations = emptyList())))
        val reissued =
            graph.forwardings.last().let { edge ->
                edge.copy(
                    source =
                        edge.source.copy(
                            parameter = edge.source.parameter.copy(candidateSelector = "candidate:reissued")
                        )
                )
            }
        fixture.assertAdmitted(
            wire.copy(flow = flow.copy(forwarding = graph.copy(forwardings = graph.forwardings.dropLast(1) + reissued)))
        )
    }

    @Test
    fun `graph edges independently reject wrong basis formal positions argument sites and missing activation`() {
        val wire = fixture.exhaustedGraphObservation()
        val flow = fixture.flow(wire)
        val graph = flow.forwarding as QueryCallbackForwardingEvidenceWireDocument.ExhaustedGraph
        val edge = graph.forwardings.single()
        val target = edge.target as QueryCallbackBindingWireDocument.Bound
        val original = flow.basis as ImpactSemanticBasisDocument.Published
        val later = (ImpactEvidenceRevisionDocument.parse(original.generation.value + 1) as Refinement.Refined).value
        val wrongBasis =
            target.invocation.copy(
                callable = target.invocation.callable.copy(basis = original.copy(generation = later))
            )
        for (wrong in
            listOf(
                edge.copy(target = target.copy(invocation = wrongBasis)),
                edge.copy(source = edge.source.copy(position = 1)),
                edge.copy(target = target.copy(position = 1)),
                edge.copy(argument = fixture.occurrence("Boundary.kt", 9, 10)),
                edge.copy(
                    target = target.copy(invocationOwner = fixture.named("Boundary.kt", 9, 26, "sample.deferred"))
                ),
            )) fixture.assertRejected(wire.copy(flow = flow.copy(forwarding = graph.copy(forwardings = listOf(wrong)))))
    }

    @Test
    fun `exhausted graph rejects missing or disconnected formals and duplicate inventory`() {
        val wire = fixture.exhaustedGraphObservation()
        val flow = fixture.flow(wire)
        val graph = flow.forwarding as QueryCallbackForwardingEvidenceWireDocument.ExhaustedGraph
        val terminal = graph.formals.last()
        for (wrong in
            listOf(
                graph.copy(root = terminal),
                graph.copy(formals = listOf(graph.root)),
                graph.copy(formals = graph.formals + graph.root),
                graph.copy(forwardings = emptyList()),
                graph.copy(forwardings = graph.forwardings + graph.forwardings),
            )) fixture.assertRejected(wire.copy(flow = flow.copy(forwarding = wrong)))
    }

    @Test
    fun `exhausted graph rejects unjustified parent scans and unrecorded invocation hops`() {
        val wire = fixture.exhaustedGraphObservation()
        val flow = fixture.flow(wire)
        fixture.assertRejected(wire.copy(flow = flow.copy(scan = QueryCallbackInvocationScanDocument.INCOMPLETE)))
        fixture.assertRejected(
            wire.copy(flow = flow.copy(obligations = listOf(QueryCallbackFlowCauseDocument.PARAMETER_ESCAPES)))
        )
        val graph = flow.forwarding as QueryCallbackForwardingEvidenceWireDocument.ExhaustedGraph
        val edge = graph.forwardings.single()
        fixture.assertRejected(
            wire.copy(
                flow =
                    flow.copy(
                        forwarding =
                            graph.copy(
                                forwardings = listOf(edge.copy(argument = fixture.occurrence("Boundary.kt", 18, 19)))
                            )
                    )
            )
        )
    }

    @Test
    fun `qualified supplier activation retains exhausted formal graph without claiming an exhaustive parent scan`() {
        val wire = fixture.recursiveGraphObservation()
        val flow = fixture.flow(wire)
        val qualified =
            wire.copy(
                flow =
                    flow.copy(
                        scan = QueryCallbackInvocationScanDocument.INCOMPLETE,
                        obligations = listOf(QueryCallbackFlowCauseDocument.STORED_CALLBACK),
                    )
            )
        fixture.assertAdmitted(qualified)
        val restored = fixture.flow(fixture.decode(qualified))
        assertEquals(flow.forwarding, restored.forwarding)
        assertEquals(listOf(QueryCallbackFlowCauseDocument.STORED_CALLBACK), restored.obligations)
        assertEquals(QueryCallbackInvocationScanDocument.INCOMPLETE, restored.scan)
        fixture.assertRejected(
            qualified.copy(flow = restored.copy(scan = QueryCallbackInvocationScanDocument.NOT_APPLICABLE))
        )
    }

    @Test
    fun `raw graph payload requires the discriminator all graph fields and closed alternatives`() {
        for (raw in QueryCallbackWireFixture().malformedForwardingEvidenceExamples()) assertThrows(
            SerializationException::class.java
        ) {
            wireJson.decodeFromString(QueryCallbackForwardingEvidenceWireDocument.serializer(), raw)
        }
    }
}
