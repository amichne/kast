package io.github.amichne.kast.protocol.wire

import io.github.amichne.kast.protocol.contract.QueryCallbackInvocationScanDocument
import io.github.amichne.kast.protocol.contract.QueryDeferredDependencyModelDocument
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class QueryCallbackDeferredDependencyWireTest {
    private val fixture = QueryCallbackRefinementWireFixture()

    @Test
    fun `deferred supply encodes every class identity without an invocation count`() {
        val wire = observation()
        fixture.assertAdmitted(wire)
        val raw = wireJson.encodeToJsonElement(QueryCallbackObservationWireDocument.serializer(), wire).jsonObject
        val binding = raw.getValue("flow").jsonObject.getValue("binding").jsonObject
        assertEquals(setOf("type", "basis", "occurrence", "owner", "target", "position", "binary"), binding.keys)
        assertEquals("DEFERRED_DEPENDENCY", binding.getValue("type").jsonPrimitive.content)
        val binary = binding.getValue("binary").jsonObject
        assertEquals(
            setOf("model", "facade", "factory", "synchronized", "publication", "unsafe", "mode_dispatch"),
            binary.keys,
        )
        assertEquals("KOTLIN_LAZY_JVM_V1", binary.getValue("model").jsonPrimitive.content)
        assertEquals(
            "0f432817ab2bf3d06e5095a83b872c1e1e2632a2aea3bce3b1e0fbd50cbd32c4",
            binary.getValue("facade").jsonPrimitive.content,
        )
        assertEquals(0, binding.getValue("position").jsonPrimitive.content.toInt())
    }

    @Test
    fun `deferred document rejects invented invocations incomplete scans and mismatched identities`() {
        val wire = observation()
        val flow = fixture.flow(wire)
        val binding = flow.binding as QueryCallbackBindingWireDocument.DeferredDependency
        for (wrong in
            listOf(
                flow.copy(scan = QueryCallbackInvocationScanDocument.INCOMPLETE),
                flow.copy(invocations = fixture.flow(fixture.base()).invocations),
                flow.copy(binding = binding.copy(position = 1)),
                flow.copy(binding = binding.copy(binary = binding.binary.copy(unsafe = "A".repeat(64)))),
                flow.copy(binding = binding.copy(occurrence = fixture.occurrence("Seed.kt", 8, 20))),
                flow.copy(binding = binding.copy(target = binding.owner.compilerTarget)),
            )) fixture.assertRejected(wire.copy(flow = wrong))
    }

    private fun observation(): QueryCallbackObservationWireDocument {
        val wire = fixture.base()
        val flow = fixture.flow(wire)
        val initial = flow.binding as QueryCallbackBindingWireDocument.Bound
        val target =
            QueryCallbackWireFixture()
                .callable(
                    "jar:///stdlib.jar!/kotlin/LazyKt.class",
                    0,
                    30,
                    "kotlin.lazy",
                    listOf("kotlin.Function0<T>"),
                )
                .callbackWire()
        val owner = (initial.invocationOwner as QueryCallbackBodyWireDocument.Named).callable
        return wire.copy(
            flow =
                flow.copy(
                    binding =
                        QueryCallbackBindingWireDocument.DeferredDependency(
                            basis = flow.basis,
                            occurrence = initial.invocationOccurrence,
                            owner = owner,
                            target = target.compilerTarget,
                            position = 0,
                            binary =
                                QueryKotlinLazyBinaryWireDocument(
                                    model = QueryDeferredDependencyModelDocument.KOTLIN_LAZY_JVM_V1,
                                    facade = "0f432817ab2bf3d06e5095a83b872c1e1e2632a2aea3bce3b1e0fbd50cbd32c4",
                                    factory = "0b114cc831af3a8f239210550a7c827b220dcfabf7affbbfb39916a4a50c846a",
                                    synchronized = "e1e31f2d66b92e44fd5232af6a0f40e0fd1a42ed93b3eb44e26fe111e0e10294",
                                    publication = "f4bcf09dcc22d212208914c804a924fb94e9ae5336a2d4bfaf0b92072c48135a",
                                    unsafe = "1b745b04099715a6eeb9d4df8d593c769aa58b8f116cc88063e8d17d35d759c3",
                                    modeDispatch = "842bd12641b951e267c40fd2a57cb4fa4356167ccbf713722b541d8307429d7d",
                                ),
                        ),
                    invocations = emptyList(),
                    obligations = emptyList(),
                    scan = QueryCallbackInvocationScanDocument.EXHAUSTIVE,
                )
        )
    }
}
