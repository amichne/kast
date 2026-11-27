package io.github.amichne.kast.protocol.wire

import io.github.amichne.kast.protocol.contract.QueryCallbackFlowCauseDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackTargetDocument
import io.github.amichne.kast.protocol.contract.RelationKindDocument
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

/** A callback target is a source callable or a compiled declaration, and the encoding names which one it is. */
class QueryCallbackTargetWireTest {
    private val fixture = QueryCallbackWireFixture()

    @Test
    fun `a source target encodes its declaration handle beside its compiler facts`() {
        val wire = fixture.callbackDocument().toWireDocument()
        val target = encodedTarget(wire)
        assertEquals(setOf("type", "declaration", "compiler_target"), target.keys)
        assertEquals("SOURCE", target.getValue("type").jsonPrimitive.content)
        assertEquals("Target.kt", target.getValue("declaration").jsonObject.getValue("file").jsonPrimitive.content)
    }

    @Test
    fun `a binary callers target encodes compiler facts without a source handle and decodes`() {
        val wire = binaryCallers()
        val target = encodedTarget(wire)
        assertEquals(setOf("type", "compiler_target"), target.keys)
        assertEquals("BINARY", target.getValue("type").jsonPrimitive.content)
        assertEquals(BINARY_FILE, target.getValue("compiler_target").jsonObject.getValue("file").jsonPrimitive.content)
        val decoded =
            assertInstanceOf(WireDocumentConversion.Converted::class.java, wire.toContract()).value
                as io.github.amichne.kast.protocol.contract.QueryCallbackObservationDocument
        val binary = assertInstanceOf(QueryCallbackTargetDocument.Binary::class.java, decoded.target)
        assertEquals(BINARY_FILE, binary.compilerTarget.file.value)
    }

    @Test
    fun `a binary callee cannot be admitted to a source result site`() {
        val source = fixture.callbackDocument().toWireDocument()
        val binaryCallee = source.copy(target = binaryTarget(source))
        assertEquals(RelationKindDocument.CALLEES, binaryCallee.relation)
        assertEquals(WireDocumentConversion.Rejected, binaryCallee.toContract())
    }

    @Test
    fun `an unknown target variant is rejected`() {
        val raw =
            wireJson
                .encodeToString(QueryCallbackObservationWireDocument.serializer(), binaryCallers())
                .replace("\"type\":\"BINARY\"", "\"type\":\"COMPILED\"")
        val decoded = runCatching { wireJson.decodeFromString(QueryCallbackObservationWireDocument.serializer(), raw) }
        assertEquals(true, decoded.isFailure, "An unknown target type must not decode")
    }

    private fun binaryCallers(): QueryCallbackObservationWireDocument {
        val source = fixture.callbackDocument().toWireDocument()
        return source.copy(
            target = binaryTarget(source),
            relation = RelationKindDocument.CALLERS,
            resultProjection =
                QueryCallbackResultProjectionWireDocument.Unproven(QueryCallbackFlowCauseDocument.OUTSIDE_DOMAIN),
        )
    }

    private fun binaryTarget(source: QueryCallbackObservationWireDocument): QueryCallbackTargetWireDocument.Binary =
        QueryCallbackTargetWireDocument.Binary(source.sourceTarget.compilerTarget.copy(file = BINARY_FILE))

    private fun encodedTarget(wire: QueryCallbackObservationWireDocument): JsonObject =
        wireJson
            .encodeToJsonElement(QueryCallbackObservationWireDocument.serializer(), wire)
            .jsonObject
            .getValue("target")
            .jsonObject
}

private const val BINARY_FILE = "jar:///libraries/fixture.jar!/sample/TargetKt.class"
