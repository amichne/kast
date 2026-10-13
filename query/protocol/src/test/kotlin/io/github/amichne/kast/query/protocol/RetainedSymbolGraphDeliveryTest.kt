package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.protocol.contract.QueryOutputDocument
import io.github.amichne.kast.protocol.contract.QueryRunRequest
import io.github.amichne.kast.protocol.contract.QuerySymbolFieldDocument
import io.github.amichne.kast.protocol.contract.ToolOutputDetail
import io.github.amichne.kast.protocol.wire.CanonicalOperationWireBindings
import io.github.amichne.kast.protocol.wire.WireDecoding
import io.github.amichne.kast.protocol.wire.WireEncoding
import io.github.amichne.kast.protocol.wire.presentation.CanonicalJsonDocument
import io.github.amichne.kast.protocol.wire.presentation.CanonicalQueryCliDocuments
import io.github.amichne.kast.protocol.wire.presentation.ProjectedOperationOutcome
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RetainedSymbolGraphDeliveryTest {
    @Test
    fun `minimal symbol fields preserve 53 compact exact connection occurrences`() = runTest {
        val fixture = RetainedSymbolGraphFixture()
        val issued = fixture.issue()
        val page =
            fixture.retained.protocol.executePage(
                QueryRunRequest.ReadResult.symbols(issued.reference, output = fixture.selectedOutput),
                fixture.retained.source.authority,
                fixture.retained.budget,
            )
        val document = documentThroughWire(page, emptyList())
        val full = Json.parseToJsonElement(document.present(ToolOutputDetail.VERBOSE).value).jsonObject
        val compact = Json.parseToJsonElement(document.present(ToolOutputDetail.COMPACT).value).jsonObject
        assertEquals(53, full.getValue("items").jsonArray.size)
        assertEquals(
            53,
            full.getValue("items").jsonArray.sumOf { it.jsonObject.getValue("connections").jsonArray.size },
        )
        assertEquals(0, fixture.retained.semanticCalls)
        println("D3 SYMBOLS baseline full_bytes=${bytes(full)} compact_bytes=${bytes(compact)} connections=53")
        assertNotNull(
            compact["relation_graph"],
            "Minimal SYMBOLS rows need compact connections in the existing graph table",
        )
        assertConnections(compact)
        assertLosslessRows(full, compact)
        val output =
            compact
                .getValue("relation_graph")
                .jsonObject
                .getValue("detail")
                .jsonObject
                .getValue("request")
                .jsonObject
                .getValue("output")
                .jsonObject
        assertEquals("SYMBOLS", output.getValue("type").jsonPrimitive.content)
        assertEquals(
            0,
            output.getValue("fields").jsonArray.size,
            "READ_RESULT fields differ from the original RUN NAME field",
        )
        assertTrue(bytes(compact) < bytes(full))
        assertEquals(full.getValue("question"), compact.getValue("question"))
        assertEquals(full.getValue("coverage"), compact.getValue("coverage"))
    }

    @Test
    fun `requested unavailable source preserves current field selection and finite blockers through wire`() = runTest {
        val fixture = RetainedSymbolGraphFixture()
        val issued = fixture.issue(sourceUnavailable = true)
        val output = QueryOutputDocument.Symbols(graphFixtureList(listOf(QuerySymbolFieldDocument.SOURCE)))
        val page =
            fixture.retained.protocol.executePage(
                QueryRunRequest.ReadResult.symbols(issued.reference, output = output),
                fixture.retained.source.authority,
                fixture.retained.budget,
            )
        val document = documentThroughWire(page, listOf(QuerySymbolFieldDocument.SOURCE))
        val full = Json.parseToJsonElement(document.present(ToolOutputDetail.VERBOSE).value).jsonObject
        val compact = Json.parseToJsonElement(document.present(ToolOutputDetail.COMPACT).value).jsonObject
        assertConnections(compact)
        assertLosslessRows(full, compact)
        val fields =
            compact
                .getValue("relation_graph")
                .jsonObject
                .getValue("detail")
                .jsonObject
                .getValue("request")
                .jsonObject
                .getValue("output")
                .jsonObject
                .getValue("fields")
                .jsonArray
        assertEquals(listOf("SOURCE"), fields.map { it.jsonPrimitive.content })
        assertEquals(full.getValue("qualification"), compact.getValue("qualification"))
        assertEquals(full.getValue("failures"), compact.getValue("failures"))
        assertTrue(compact.getValue("items").jsonArray.all { "source" !in it.jsonObject })
        assertEquals(0, fixture.retained.semanticCalls)
    }

    private fun documentThroughWire(
        page: QueryPublishedPage,
        fields: List<QuerySymbolFieldDocument>,
    ): CanonicalJsonDocument {
        val encoded = (CanonicalOperationWireBindings.queryRun.encodeOutcome(page) as WireEncoding.Encoded).document
        val output =
            Json.parseToJsonElement(encoded)
                .jsonObject
                .getValue("body")
                .jsonObject
                .getValue("result")
                .jsonObject
                .getValue("presentation_output")
        assertEquals(
            Json.encodeToJsonElement(
                ExpectedSymbolPresentationOutput.serializer(),
                ExpectedSymbolPresentationOutput(type = ExpectedSymbolPresentationKind.SYMBOLS, fields = fields),
            ),
            output,
        )
        val decoded = (CanonicalOperationWireBindings.queryRun.decodeOutcome(encoded) as WireDecoding.Decoded).value
        return when (val projected = CanonicalQueryCliDocuments.project(decoded)) {
            is ProjectedOperationOutcome.Complete -> projected.document
            is ProjectedOperationOutcome.Qualified -> projected.document
            is ProjectedOperationOutcome.Rejected ->
                error("The frozen retained page must preserve its producer outcome")
        }
    }

    private fun assertConnections(document: JsonObject) {
        val reconstruction = SymbolGraphReconstruction(document)
        println(
            "D3 SYMBOLS connection_variants=" +
                reconstruction.connections.values.groupingBy { it.getValue("type").jsonPrimitive.content }.eachCount()
        )
        val observed =
            document.getValue("items").jsonArray.flatMap { item ->
                item.jsonObject.getValue("connections").jsonArray.map { link ->
                    val restored = reconstruction.connection(link.jsonPrimitive.content)
                    listOf(
                        restored.source.getValue("qualifiedIdentity").jsonPrimitive.content,
                        restored.target.getValue("qualifiedIdentity").jsonPrimitive.content,
                        restored.evidence.getValue("meaning").jsonPrimitive.content,
                        "SOURCE_TO_TARGET",
                        restored.evidence
                            .getValue("occurrence")
                            .jsonObject
                            .getValue("range")
                            .jsonObject
                            .getValue("startInclusive")
                            .jsonPrimitive
                            .content,
                        restored.evidence.getValue("provenance").jsonPrimitive.content,
                        restored.evidence.getValue("coverage").jsonPrimitive.content,
                    )
                }
            }
        assertConnectionOracle(observed)
    }

    private fun assertConnectionOracle(observed: List<List<String>>) {
        val expected =
            (0 until 53).map { index ->
                listOf(
                    "fixture.owner${index % 2}",
                    "sample.subject",
                    "references",
                    "SOURCE_TO_TARGET",
                    "${100 + index * 16}",
                    "k2-authored-source",
                    "exact-compiler-confirmed",
                )
            }
        assertEquals(expected, observed)
        assertThrows(AssertionError::class.java) { assertEquals(expected, observed.dropLast(1)) }
        assertThrows(AssertionError::class.java) { assertEquals(expected, observed + observed.last()) }
        assertThrows(AssertionError::class.java) {
            assertEquals(
                expected,
                observed.map { it.map { value -> if (value == "SOURCE_TO_TARGET") "TARGET_TO_SOURCE" else value } },
            )
        }
    }

    private fun bytes(document: JsonObject): Int = document.toString().toByteArray(Charsets.UTF_8).size
}

@Serializable
private data class ExpectedSymbolPresentationOutput(
    val type: ExpectedSymbolPresentationKind,
    val fields: List<QuerySymbolFieldDocument>,
)

@Serializable
private enum class ExpectedSymbolPresentationKind {
    @SerialName("symbols") SYMBOLS
}
