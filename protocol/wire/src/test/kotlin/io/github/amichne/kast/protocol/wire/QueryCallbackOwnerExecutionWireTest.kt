package io.github.amichne.kast.protocol.wire

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.ImpactEvidenceRevisionDocument
import io.github.amichne.kast.protocol.contract.ImpactSemanticBasisDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackDocumentFailure
import io.github.amichne.kast.protocol.contract.QueryCallbackFlowCauseDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackInvocationScanDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackObservationDocument
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class QueryCallbackOwnerExecutionWireTest {
    @Test
    fun `recursive owner execution survives encoded admission without a nested effect gap`() {
        val wire = composed()
        val encoded = wireJson.encodeToJsonElement(QueryCallbackObservationWireDocument.serializer(), wire)
        val owner =
            encoded.jsonObject.getValue("flow").jsonObject.getValue("owner_bindings").jsonArray.single().jsonObject
        val execution = owner.getValue("execution").jsonObject
        assertEquals("OBSERVED", execution.getValue("type").jsonPrimitive.content)
        val outer = execution.getValue("flow").jsonObject.getValue("owner_bindings").jsonArray.single().jsonObject
        assertEquals("OBSERVED", outer.getValue("execution").jsonObject.getValue("type").jsonPrimitive.content)
        assertEquals(emptyList<String>(), owner.getValue("obligations").jsonArray.map { it.jsonPrimitive.content })
        val admitted = decode(wire)
        assertTrue(admitted is WireDocumentConversion.Converted)
        admitted as WireDocumentConversion.Converted
        assertEquals(wire, admitted.value.toWireDocument())
    }

    @Test
    fun `owner execution cannot change the exact body binding authority or discharge an absent proof`() {
        val wire = composed()
        val flow = wire.flow as QueryCallbackFlowWireDocument.Observed
        val owner = flow.ownerBindings.single()
        val execution = (owner.execution as QueryCallbackOwnerExecutionWireDocument.Observed).flow
        val published = execution.basis as ImpactSemanticBasisDocument.Published
        val changedBasis = published.copy(generation = ImpactEvidenceRevisionDocument.parse(2L).refined())
        val bound = execution.binding as QueryCallbackBindingWireDocument.Direct
        val invalid =
            listOf(
                owner.copy(execution = QueryCallbackOwnerExecutionWireDocument.Unproven),
                owner.copy(
                    execution =
                        QueryCallbackOwnerExecutionWireDocument.Observed(execution.copy(body = anonymous(11, 24)))
                ),
                owner.copy(
                    execution =
                        QueryCallbackOwnerExecutionWireDocument.Observed(
                            execution.copy(
                                binding = bound.copy(owner = QueryCallbackBodyWireDocument.Named(wire.lexicalOwner))
                            )
                        )
                ),
                owner.copy(
                    execution = QueryCallbackOwnerExecutionWireDocument.Observed(execution.copy(basis = changedBasis))
                ),
                owner.copy(
                    execution =
                        QueryCallbackOwnerExecutionWireDocument.Observed(execution.copy(ownerBindings = emptyList()))
                ),
                owner.copy(
                    execution =
                        QueryCallbackOwnerExecutionWireDocument.Observed(
                            execution.copy(scan = QueryCallbackInvocationScanDocument.EXHAUSTIVE)
                        )
                ),
            )
        for (rejected in invalid) {
            assertEquals(
                WireDocumentConversion.Rejected,
                decode(wire.copy(flow = flow.copy(ownerBindings = listOf(rejected)))),
            )
        }
    }

    @Test
    fun `an exact anonymous owner cannot substitute a different named root for the receiving formal`() {
        val wire = composed()
        val flow = wire.flow as QueryCallbackFlowWireDocument.Observed
        val inner = flow.ownerBindings.single()
        val innerFlow = (inner.execution as QueryCallbackOwnerExecutionWireDocument.Observed).flow
        val outer = innerFlow.ownerBindings.single()
        val outerFlow = (outer.execution as QueryCallbackOwnerExecutionWireDocument.Observed).flow
        val source = QueryCallbackWireFixture()
        val wrongRoot = source.callable("Boundary.kt", 0, 30, "sample.other", listOf("kotlin.Function0<kotlin.Unit>"))
        val changedBinding =
            (outer.binding as QueryCallbackBindingWireDocument.Direct).copy(
                owner = QueryCallbackBodyWireDocument.Named(wrongRoot.callbackWire())
            )
        val changedOuter =
            outer.copy(
                binding = changedBinding,
                execution = QueryCallbackOwnerExecutionWireDocument.Observed(outerFlow.copy(binding = changedBinding)),
            )
        val changedInner =
            inner.copy(
                execution =
                    QueryCallbackOwnerExecutionWireDocument.Observed(
                        innerFlow.copy(ownerBindings = listOf(changedOuter))
                    )
            )
        assertEquals(
            WireDocumentConversion.Rejected,
            decode(wire.copy(flow = flow.copy(ownerBindings = listOf(changedInner)))),
        )
    }

    @Test
    fun `an unproved owner remains explicit and cannot masquerade as complete execution`() {
        val wire = composed()
        val flow = wire.flow as QueryCallbackFlowWireDocument.Observed
        val cause = QueryCallbackFlowCauseDocument.NESTED_CALLBACK_EXECUTION
        val owner =
            flow.ownerBindings
                .single()
                .copy(
                    execution = QueryCallbackOwnerExecutionWireDocument.Unproven,
                    obligations = listOf(cause),
                )
        val unresolved = wire.copy(flow = flow.copy(ownerBindings = listOf(owner), obligations = listOf(cause)))
        val admitted = decode(unresolved)
        assertTrue(admitted is WireDocumentConversion.Converted)
        admitted as WireDocumentConversion.Converted
        assertEquals(unresolved, admitted.value.toWireDocument())
        assertEquals(
            WireDocumentConversion.Rejected,
            decode(wire.copy(flow = flow.copy(ownerBindings = listOf(owner)))),
        )
    }

    @Test
    fun `immutable owner proof preserves its source uses and discharges only the supply cause`() {
        val wire = QueryCallbackOwnerExecutionWireFixture().immutable()
        val admitted = decode(wire)
        assertTrue(admitted is WireDocumentConversion.Converted)
        admitted as WireDocumentConversion.Converted
        assertEquals(wire, admitted.value.toWireDocument())
        val flow = wire.flow as QueryCallbackFlowWireDocument.Observed
        val owner = flow.ownerBindings.single()
        val execution = owner.execution as QueryCallbackOwnerExecutionWireDocument.Immutable
        val other =
            QueryCallbackBodyWireDocument.Named(
                QueryCallbackWireFixture()
                    .callable(
                        "Boundary.kt",
                        0,
                        30,
                        "sample.other",
                        listOf("kotlin.Function0<kotlin.Unit>"),
                    )
                    .callbackWire()
            )
        val invalid =
            listOf(
                owner.copy(execution = execution.copy(owner = other)),
                owner.copy(body = anonymous(11, 24)),
                owner.copy(
                    binding =
                        QueryCallbackBindingWireDocument.Unavailable(QueryCallbackFlowCauseDocument.EXTERNAL_CALLABLE)
                ),
                owner.copy(
                    execution =
                        execution.copy(
                            flow = execution.flow.copy(scan = QueryCallbackInvocationScanDocument.INCOMPLETE)
                        )
                ),
            )
        for (rejected in invalid) assertEquals(
            WireDocumentConversion.Rejected,
            decode(wire.copy(flow = flow.copy(ownerBindings = listOf(rejected)))),
        )
    }

    @Test
    fun `recursive owner admission rejects evidence deeper than its finite limit`() {
        val wire = composed()
        val original = (wire.toContract() as WireDocumentConversion.Converted).value
        val flow = wire.flow as QueryCallbackFlowWireDocument.Observed
        val template = flow.ownerBindings.single()
        val execution = (template.execution as QueryCallbackOwnerExecutionWireDocument.Observed).flow
        val nested = listOf(QueryCallbackFlowCauseDocument.NESTED_CALLBACK_EXECUTION)
        var owner = template.copy(obligations = nested, execution = QueryCallbackOwnerExecutionWireDocument.Unproven)
        repeat(63) {
            owner =
                template.copy(
                    obligations = nested,
                    execution =
                        QueryCallbackOwnerExecutionWireDocument.Observed(
                            execution.copy(
                                ownerBindings = listOf(owner),
                                obligations = nested,
                            )
                        ),
                )
        }
        val deep =
            (flow.copy(ownerBindings = listOf(owner), obligations = nested).toContract()
                    as WireDocumentConversion.Converted)
                .value
        val admitted =
            QueryCallbackObservationDocument.create(
                original.occurrence,
                original.target,
                original.lexicalOwner,
                original.callbackBody,
                original.namedPolicy,
                deep,
                original.relation,
                original.requestedDomain,
                original.effectiveDomain,
                original.domainFingerprint,
                original.resultProjection,
                original.basis,
            )
        assertEquals(Refinement.Rejected(QueryCallbackDocumentFailure.INVALID_SCAN_PROOF), admitted)
    }

    @Test
    fun `unknown owner execution tags reject at decoding`() {
        val unknown = wireJson.encodeToString(UnknownOwnerExecution.serializer(), UnknownOwnerExecution("UNKNOWN"))
        assertThrows(SerializationException::class.java) {
            wireJson.decodeFromString(QueryCallbackOwnerExecutionWireDocument.serializer(), unknown)
        }
    }

    private fun composed() = QueryCallbackOwnerExecutionWireFixture().composed()

    private fun anonymous(start: Int, end: Int) = QueryCallbackOwnerExecutionWireFixture().anonymous(start, end)

    private fun decode(wire: QueryCallbackObservationWireDocument) =
        wireJson
            .decodeFromString(
                QueryCallbackObservationWireDocument.serializer(),
                wireJson.encodeToString(QueryCallbackObservationWireDocument.serializer(), wire),
            )
            .toContract()

    private fun <T> io.github.amichne.kast.kernel.Refinement<T, *>.refined(): T =
        (this as io.github.amichne.kast.kernel.Refinement.Refined).value
}

@Serializable private data class UnknownOwnerExecution(val type: String)
