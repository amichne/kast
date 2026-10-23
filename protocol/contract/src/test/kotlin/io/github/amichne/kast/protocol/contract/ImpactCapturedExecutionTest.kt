package io.github.amichne.kast.protocol.contract

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ImpactCapturedExecutionTest {
    @Test
    fun `capture serialization retains anonymous identity supplier and invocation separately`() {
        val fixture = ImpactFindingFixture()
        val host = fixture.declaration
        val body = ImpactCapturedBodyDocument(host.file, range(fixture, 40, 80), fixture.text("anonymous-inner"))
        val invocation =
            ImpactCaptureInvocationDocument(
                body = body,
                enclosing = host,
                supply = ImpactInvocationReferenceDocument(range(fixture, 30, 90), host),
                supplyOwner = ImpactCapturedBodyDocument(host.file, host.range, fixture.text("named-supplier")),
                parameter = range(fixture, 5, 10),
                position = fixture.offset(0),
                invocation = range(fixture, 12, 19),
                invocationOwner = ImpactCapturedBodyDocument(host.file, host.range, fixture.text("named-invoker")),
            )
        val owner: ImpactExecutionOwnerDocument =
            ImpactExecutionOwnerDocument.Callback(
                body,
                fixture.bounded(listOf(invocation)),
            )
        val encoded = Json.parseToJsonElement(Json.encodeToString(owner)).jsonObject
        assertEquals(setOf("type", "body", "invocations"), encoded.keys)
        assertEquals("CALLBACK", encoded.getValue("type").jsonPrimitive.content)
        assertEquals(
            "anonymous-inner",
            encoded.getValue("body").jsonObject.getValue("compilerIdentity").jsonPrimitive.content,
        )
        val frame = encoded.getValue("invocations").jsonArray.single().jsonObject
        assertEquals(
            setOf(
                "body",
                "enclosing",
                "supply",
                "supplyOwner",
                "parameter",
                "position",
                "invocation",
                "invocationOwner",
            ),
            frame.keys,
        )
        assertEquals("DECLARATION", supplyExecutionKind(frame))
        assertTrue(owner.admits(host, range(fixture, 50, 55)))
        assertFalse(owner.admits(host, range(fixture, 20, 25)))
        assertFalse(owner.admits(host.copy(file = fixture.text("/workspace/Other.kt")), range(fixture, 50, 55)))
    }

    private fun range(fixture: ImpactFindingFixture, start: Int, end: Int) =
        ImpactSourceRangeDocument(fixture.offset(start), fixture.offset(end))

    private fun supplyExecutionKind(frame: kotlinx.serialization.json.JsonObject): String =
        frame.getValue("supply").jsonObject.getValue("executionOwner").jsonObject.getValue("type").jsonPrimitive.content
}
