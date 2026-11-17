package io.github.amichne.kast.cli.mcp

import io.github.amichne.kast.appserver.query.PublicToolContract
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.registry.PublicToolIdentity
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class McpProducerQueriesTest {
    private val exact = "exact:v4:" + "a".repeat(64)
    private val candidate = "candidate:v4:" + "b".repeat(64)

    @Test
    fun `discovery uses exact callers and submits the selected seed unchanged`() {
        val discover =
            McpToolCall("producer_invocations", Json.encodeToJsonElement(Discovery(exact)).jsonObject).asProducerQuery()
        assertEquals("query_symbols", discover.name)
        assertInstanceOf(
            Refinement.Refined::class.java,
            PublicToolContract.admit(PublicToolIdentity.QUERY_SYMBOLS, discover.arguments!!),
        )
        val run = discover.arguments.getValue("request").jsonObject
        assertEquals(
            "CALLERS",
            run.getValue("steps").jsonArray.single().jsonObject.getValue("relation").jsonPrimitive.content,
        )
        assertEquals(
            "OCCURRENCES",
            run.getValue("terminalStep")
                .jsonObject
                .getValue("output")
                .jsonObject
                .getValue("type")
                .jsonPrimitive
                .content,
        )
        val selection = Json.encodeToJsonElement(Selection(Seed(exact, exact, Span(20, 36), candidate))).jsonObject
        val impact = McpToolCall("impact", selection).asProducerQuery()
        assertInstanceOf(
            Refinement.Refined::class.java,
            PublicToolContract.admit(PublicToolIdentity.QUERY_SYMBOLS, impact.arguments!!),
        )
        val impactRun = impact.arguments.getValue("request").jsonObject
        assertEquals(
            selection.getValue("producerSeed"),
            impactRun.getValue("source").jsonObject.getValue("seeds").jsonArray.single(),
        )
        assertEquals(
            "FINDINGS",
            impactRun
                .getValue("terminalStep")
                .jsonObject
                .getValue("output")
                .jsonObject
                .getValue("section")
                .jsonPrimitive
                .content,
        )
    }

    @Test
    fun `impact rejects a seed without an issued range`() {
        val input = Json {
            explicitNulls = false
        }
            .encodeToJsonElement(Selection(Seed(exact, exact, Span(20, 36), null)))
            .jsonObject
        assertThrows(SerializationException::class.java) { McpToolCall("impact", input).asProducerQuery() }
    }

    @Serializable private data class Discovery(val callable: String)

    @Serializable private data class Selection(val producerSeed: Seed)

    @Serializable
    private data class Seed(val enclosing: String, val callable: String, val anchor: Span, val candidateRef: String?)

    @Serializable private data class Span(val start: Int, val end: Int)
}
