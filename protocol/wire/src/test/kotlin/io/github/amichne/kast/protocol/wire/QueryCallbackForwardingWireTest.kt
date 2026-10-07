package io.github.amichne.kast.protocol.wire

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.ImpactCompilerTransferDocument
import io.github.amichne.kast.protocol.contract.ImpactSourceRangeDocument
import io.github.amichne.kast.protocol.contract.ImpactTransferKindDocument
import io.github.amichne.kast.protocol.contract.ImpactValueRoleDocument
import io.github.amichne.kast.protocol.contract.ImpactValueSiteReferenceDocument
import io.github.amichne.kast.protocol.contract.ProtocolOffset
import io.github.amichne.kast.protocol.contract.QueryCallbackFlowCauseDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackInvocationScanDocument
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class QueryCallbackForwardingWireTest {
    private val fixture = QueryCallbackRefinementWireFixture()

    @Test
    fun `forwarded invocation retains source formal argument site and receiving formal`() {
        val wire = fixture.forwardedObservation()
        fixture.assertAdmitted(wire)
        val flow = fixture.flow(wire)
        val invocation = flow.invocations.single()
        val forwarding = invocation.forwardings.single()
        val reissuedSource =
            forwarding.copy(
                source =
                    forwarding.source.copy(
                        parameter = forwarding.source.parameter.copy(candidateSelector = "candidate:reissued")
                    )
            )
        fixture.assertAdmitted(
            wire.copy(flow = flow.copy(invocations = listOf(invocation.copy(forwardings = listOf(reissuedSource)))))
        )
        val raw = wireJson.encodeToJsonElement(QueryCallbackObservationWireDocument.serializer(), wire).jsonObject
        val encoded =
            raw.getValue("flow")
                .jsonObject
                .getValue("invocations")
                .jsonArray
                .single()
                .jsonObject
                .getValue("forwardings")
                .jsonArray
                .single()
                .jsonObject
        assertEquals(setOf("source", "argument", "target", "callable_transfers"), encoded.keys)
        assertEquals("BOUND", encoded.getValue("target").jsonObject.getValue("type").jsonPrimitive.content)
    }

    @Test
    fun `forwarded immutable alias retains exact transfer evidence and rejects broken routes`() {
        val wire = fixture.forwardedObservation()
        val flow = fixture.flow(wire)
        val invocation = flow.invocations.single()
        val forwarding = invocation.forwardings.single()
        val enclosing = (flow.binding as QueryCallbackBindingWireDocument.Bound).invocation.callable
        fun offset(value: Int) = (ProtocolOffset.parse(value) as Refinement.Refined).value
        fun site(start: Int, end: Int, role: ImpactValueRoleDocument) =
            ImpactValueSiteReferenceDocument(enclosing, ImpactSourceRangeDocument(offset(start), offset(end)), role)
        val source = site(11, 12, ImpactValueRoleDocument.ExpressionResult)
        val local = site(9, 13, ImpactValueRoleDocument.LocalBinding)
        val read = site(14, 18, ImpactValueRoleDocument.LocalRead)
        val transfers =
            listOf(
                ImpactCompilerTransferDocument(source, local, ImpactTransferKindDocument.LOCAL_BINDING),
                ImpactCompilerTransferDocument(local, read, ImpactTransferKindDocument.LOCAL_READ),
            )
        val aliased = forwarding.copy(callableTransfers = transfers)
        val admitted = wire.copy(flow = flow.copy(invocations = listOf(invocation.copy(forwardings = listOf(aliased)))))
        fixture.assertAdmitted(admitted)
        assertEquals(
            transfers,
            fixture.flow(fixture.decode(admitted)).invocations.single().forwardings.single().callableTransfers,
        )
        for (wrong in
            listOf(
                transfers.reversed(),
                transfers.drop(1),
                listOf(
                    transfers.first(),
                    transfers.last().copy(target = site(19, 20, ImpactValueRoleDocument.LocalRead)),
                ),
                listOf(transfers.first().copy(kind = ImpactTransferKindDocument.PROPERTY_ASSIGNMENT), transfers.last()),
            )) fixture.assertRejected(
            wire.copy(
                flow =
                    flow.copy(
                        invocations =
                            listOf(invocation.copy(forwardings = listOf(forwarding.copy(callableTransfers = wrong))))
                    )
            )
        )
    }

    @Test
    fun `forwarding inside a named nested owner requires its deferred execution obligation`() {
        val wire = fixture.forwardedObservation()
        val flow = fixture.flow(wire)
        val invocation = flow.invocations.single()
        val forwarding = invocation.forwardings.single()
        val target = forwarding.target as QueryCallbackBindingWireDocument.Bound
        val deferred = fixture.named("Boundary.kt", 9, 26, "sample.deferred")
        val nestedHop = forwarding.copy(target = target.copy(invocationOwner = deferred))
        val nestedFlow = flow.copy(invocations = listOf(invocation.copy(forwardings = listOf(nestedHop))))
        fixture.assertRejected(wire.copy(flow = nestedFlow))
        val qualified =
            wire.copy(
                flow = nestedFlow.copy(obligations = listOf(QueryCallbackFlowCauseDocument.NESTED_CALLBACK_EXECUTION))
            )
        fixture.assertAdmitted(qualified)
        assertEquals(QueryCallbackInvocationScanDocument.EXHAUSTIVE, fixture.flow(fixture.decode(qualified)).scan)
        val retained = fixture.flow(fixture.decode(qualified)).invocations.single().forwardings.single()
        assertEquals(deferred, (retained.target as QueryCallbackBindingWireDocument.Bound).invocationOwner)
    }

    @Test
    fun `supplying inside a named nested owner requires its deferred execution obligation`() {
        val wire = fixture.base()
        val flow = fixture.flow(wire)
        val binding = flow.binding as QueryCallbackBindingWireDocument.Bound
        val deferred = fixture.named("Seed.kt", 1, 21, "sample.deferred")
        val nestedFlow =
            flow.copy(
                binding = binding.copy(invocationOwner = deferred),
                scan = QueryCallbackInvocationScanDocument.EXHAUSTIVE,
            )
        fixture.assertRejected(wire.copy(flow = nestedFlow))
        val qualified =
            wire.copy(
                flow = nestedFlow.copy(obligations = listOf(QueryCallbackFlowCauseDocument.NESTED_CALLBACK_EXECUTION))
            )
        fixture.assertAdmitted(qualified)
        val retained = fixture.flow(fixture.decode(qualified))
        assertEquals(QueryCallbackInvocationScanDocument.EXHAUSTIVE, retained.scan)
        assertEquals(deferred, (retained.binding as QueryCallbackBindingWireDocument.Bound).invocationOwner)
    }

    @Test
    fun `forwarding rejects broken formal identities argument sites and omitted hops`() {
        val wire = fixture.forwardedObservation()
        val flow = fixture.flow(wire)
        val invocation = flow.invocations.single()
        val forwarding = invocation.forwardings.single()
        val target = forwarding.target as QueryCallbackBindingWireDocument.Bound
        for (wrong in
            listOf(
                forwarding.copy(source = forwarding.source.copy(position = 1)),
                forwarding.copy(source = forwarding.source.copy(callable = target.callable)),
                forwarding.copy(argument = fixture.occurrence("Boundary.kt", 9, 10)),
                forwarding.copy(target = target.copy(position = 1)),
                forwarding.copy(
                    target = QueryCallbackBindingWireDocument.Default(forwarding.source, forwarding.argument)
                ),
            )) fixture.assertRejected(
            wire.copy(flow = flow.copy(invocations = listOf(invocation.copy(forwardings = listOf(wrong)))))
        )
        fixture.assertRejected(
            wire.copy(flow = flow.copy(invocations = listOf(invocation.copy(forwardings = emptyList()))))
        )
    }
}
