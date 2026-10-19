package io.github.amichne.kast.protocol.wire

import io.github.amichne.kast.protocol.contract.QueryCallbackDependencyContractProvenanceDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackDependencyInvocationKindDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackInvocationScanDocument
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class QueryCallbackLexicalRootWireTest {
    private val fixture = QueryCallbackWireFixture()

    @Test
    fun `direct nested callback preserves its exact named lexical root`() {
        assertTrue(decode(observation(Route.DIRECT, "sample.read")) is WireDocumentConversion.Converted)
    }

    @Test
    fun `direct nested callback rejects another compiler identity at the same root range`() {
        assertEquals(WireDocumentConversion.Rejected, decode(observation(Route.DIRECT, "sample.other")))
    }

    @Test
    fun `dependency nested callback preserves its exact named lexical root`() {
        assertTrue(decode(observation(Route.DEPENDENCY, "sample.read")) is WireDocumentConversion.Converted)
    }

    @Test
    fun `dependency nested callback rejects another compiler identity at the same root range`() {
        assertEquals(WireDocumentConversion.Rejected, decode(observation(Route.DEPENDENCY, "sample.other")))
    }

    private fun observation(route: Route, rootIdentity: String): QueryCallbackObservationWireDocument {
        val wire = fixture.callbackDocument().toWireDocument()
        val flow = wire.flow as QueryCallbackFlowWireDocument.Observed
        val outer = outerOwner(flow, rootIdentity)
        return wire.copy(
            flow =
                flow.copy(
                    binding = routeBinding(route, flow, outer.body),
                    invocations = emptyList(),
                    ownerBindings = listOf(outer),
                    scan =
                        when (route) {
                            Route.DIRECT -> QueryCallbackInvocationScanDocument.NOT_APPLICABLE
                            Route.DEPENDENCY -> QueryCallbackInvocationScanDocument.EXHAUSTIVE
                        },
                )
        )
    }

    private fun outerOwner(
        flow: QueryCallbackFlowWireDocument.Observed,
        rootIdentity: String,
    ): QueryCallbackBodyBindingWireDocument {
        val outerBody =
            QueryCallbackBodyWireDocument.Anonymous(
                fixture.occurrence("Seed.kt", 1, 25).callbackWire(),
                fixture.signature("anonymous@Seed.kt#1:25", emptyList()).toWireDocument(),
            )
        val outerCall = fixture.occurrence("Seed.kt", 0, 26).callbackWire()
        val outerBinding =
            QueryCallbackBindingWireDocument.Direct(
                flow.basis,
                outerCall,
                QueryCallbackBodyWireDocument.Named(
                    fixture.callable("Seed.kt", 0, 40, rootIdentity, emptyList()).callbackWire()
                ),
            )
        return QueryCallbackBodyBindingWireDocument(
            outerBody,
            QueryCallbackBodySupplyWireDocument.DirectInvocation(outerCall),
            outerBinding,
            emptyList(),
            QueryCallbackOwnerExecutionWireDocument.Observed(
                flow.copy(
                    body = outerBody,
                    binding = outerBinding,
                    invocations = emptyList(),
                    ownerBindings = emptyList(),
                    scan = QueryCallbackInvocationScanDocument.NOT_APPLICABLE,
                )
            ),
        )
    }

    private fun routeBinding(
        route: Route,
        flow: QueryCallbackFlowWireDocument.Observed,
        outerBody: QueryCallbackBodyWireDocument.Anonymous,
    ): QueryCallbackBindingWireDocument {
        val call = fixture.occurrence("Seed.kt", 2, 20).callbackWire()
        return when (route) {
            Route.DIRECT -> QueryCallbackBindingWireDocument.Direct(flow.basis, call, outerBody)
            Route.DEPENDENCY ->
                QueryCallbackBindingWireDocument.DependencyContract(
                    flow.basis,
                    call,
                    outerBody,
                    fixture
                        .callable(
                            "jar:///stdlib.jar!/kotlin/StandardKt.class",
                            0,
                            30,
                            "kotlin.also",
                            listOf("kotlin.Function1<T,kotlin.Unit>"),
                        )
                        .callbackWire()
                        .compilerTarget,
                    0,
                    "a".repeat(64),
                    QueryCallbackDependencyContractProvenanceDocument.KOTLIN_BINARY_CONTRACT,
                    QueryCallbackDependencyInvocationKindDocument.EXACTLY_ONCE,
                )
        }
    }

    private fun decode(wire: QueryCallbackObservationWireDocument) =
        wireJson
            .decodeFromString(
                QueryCallbackObservationWireDocument.serializer(),
                wireJson.encodeToString(QueryCallbackObservationWireDocument.serializer(), wire),
            )
            .toContract()

    private enum class Route {
        DIRECT,
        DEPENDENCY,
    }
}
