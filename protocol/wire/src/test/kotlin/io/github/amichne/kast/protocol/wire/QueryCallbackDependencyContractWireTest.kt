package io.github.amichne.kast.protocol.wire

import io.github.amichne.kast.protocol.contract.QueryCallbackDependencyContractProvenanceDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackDependencyInvocationKindDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackFlowCauseDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackInvocationScanDocument
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class QueryCallbackDependencyContractWireTest {
    private val fixture = QueryCallbackRefinementWireFixture()

    @Test
    fun `declared contract encodes exact provenance class digest and formal position`() {
        val wire = observation()
        fixture.assertAdmitted(wire)
        val raw = wireJson.encodeToJsonElement(QueryCallbackObservationWireDocument.serializer(), wire).jsonObject
        val binding = raw.getValue("flow").jsonObject.getValue("binding").jsonObject
        assertEquals(
            setOf(
                "type",
                "basis",
                "occurrence",
                "owner",
                "target",
                "position",
                "class_digest",
                "provenance",
                "invocation_kind",
            ),
            binding.keys,
        )
        assertEquals("DEPENDENCY_CONTRACT", binding.getValue("type").jsonPrimitive.content)
        assertEquals("KOTLIN_BINARY_CONTRACT", binding.getValue("provenance").jsonPrimitive.content)
        assertEquals("EXACTLY_ONCE", binding.getValue("invocation_kind").jsonPrimitive.content)
        assertEquals("a".repeat(64), binding.getValue("class_digest").jsonPrimitive.content)
        assertEquals(0, binding.getValue("position").jsonPrimitive.content.toInt())
        assertEquals(
            "jar:///stdlib.jar!/kotlin/StandardKt.class",
            binding.getValue("target").jsonObject.getValue("file").jsonPrimitive.content,
        )
    }

    @Test
    fun `contract projection rejects invented body invocations invalid binary identity and incomplete scans`() {
        val wire = observation()
        val flow = fixture.flow(wire)
        val binding = flow.binding as QueryCallbackBindingWireDocument.DependencyContract
        val sourceCallable = (fixture.flow(fixture.base()).binding as QueryCallbackBindingWireDocument.Bound).callable
        for (wrong in
            listOf(
                flow.copy(scan = QueryCallbackInvocationScanDocument.INCOMPLETE),
                flow.copy(invocations = fixture.flow(fixture.base()).invocations),
                flow.copy(binding = binding.copy(classDigest = "A".repeat(64))),
                flow.copy(binding = binding.copy(position = 1)),
                flow.copy(binding = binding.copy(target = sourceCallable.compilerTarget)),
                flow.copy(binding = binding.copy(occurrence = fixture.occurrence("Seed.kt", 8, 20))),
            )) fixture.assertRejected(wire.copy(flow = wrong))
    }

    @Test
    fun `anonymous supplying owner retains its activation obligation`() {
        val wire = observation()
        val flow = fixture.flow(wire)
        val binding = flow.binding as QueryCallbackBindingWireDocument.DependencyContract
        val nested = flow.copy(binding = binding.copy(owner = fixture.anonymous("Seed.kt", 1, 25)))
        fixture.assertRejected(wire.copy(flow = nested))
        fixture.assertAdmitted(
            wire.copy(
                flow = nested.copy(obligations = listOf(QueryCallbackFlowCauseDocument.NESTED_CALLBACK_EXECUTION))
            )
        )
    }

    private fun observation(): QueryCallbackObservationWireDocument {
        val wire = fixture.base()
        val flow = fixture.flow(wire)
        val initial = flow.binding as QueryCallbackBindingWireDocument.Bound
        val callable =
            QueryCallbackWireFixture()
                .callable(
                    "jar:///stdlib.jar!/kotlin/StandardKt.class",
                    0,
                    30,
                    "kotlin.also",
                    listOf("kotlin.Function1<T,kotlin.Unit>"),
                )
                .callbackWire()
        return wire.copy(
            flow =
                flow.copy(
                    binding =
                        QueryCallbackBindingWireDocument.DependencyContract(
                            flow.basis,
                            initial.invocationOccurrence,
                            initial.invocationOwner,
                            callable.compilerTarget,
                            0,
                            "a".repeat(64),
                            QueryCallbackDependencyContractProvenanceDocument.KOTLIN_BINARY_CONTRACT,
                            QueryCallbackDependencyInvocationKindDocument.EXACTLY_ONCE,
                        ),
                    invocations = emptyList(),
                    obligations = emptyList(),
                    scan = QueryCallbackInvocationScanDocument.EXHAUSTIVE,
                )
        )
    }
}
