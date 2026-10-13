package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.protocol.contract.QueryEvidenceCursor
import io.github.amichne.kast.protocol.contract.QueryOutputDocument
import io.github.amichne.kast.protocol.contract.QueryResultCursor
import io.github.amichne.kast.protocol.contract.QueryResultReference
import io.github.amichne.kast.protocol.contract.QueryRunRequest
import io.github.amichne.kast.protocol.contract.QuerySymbolFieldDocument
import io.github.amichne.kast.protocol.contract.ToolOutputDetail
import io.github.amichne.kast.protocol.wire.presentation.CanonicalQueryCliDocuments
import io.github.amichne.kast.protocol.wire.presentation.ProjectedOperationOutcome
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class RetainedGraphNodeRetrievalTest {
    @Test
    fun `occurrence node description keeps identity and retrieves full signatures from its retained basis`() = runTest {
        val fixture = RetainedGraphDeliveryFixture()
        val issued = fixture.issue()
        val page =
            fixture.protocol.executePage(
                QueryRunRequest.ReadResult.occurrences(
                    issued.reference,
                    evidenceCursor = QueryEvidenceCursor.parse(0).graphFixtureValue(),
                ),
                fixture.source.authority,
                fixture.budget,
            ) as OperationOutcome.Complete
        val document = (CanonicalQueryCliDocuments.project(page) as ProjectedOperationOutcome.Complete).document
        val compact = Json.parseToJsonElement(document.present(ToolOutputDetail.COMPACT).value).jsonObject
        val full = Json.parseToJsonElement(document.present(ToolOutputDetail.VERBOSE).value).jsonObject
        assertCompactNodeDescriptions(full, compact)
        val request = graphDetailRequest(compact)
        assertEquals(
            QueryRunRequest.ReadResult.occurrences(
                issued.reference,
                evidenceCursor = QueryEvidenceCursor.parse(0).graphFixtureValue(),
            ),
            request,
        )
        val retrieved = fixture.protocol.executePage(request, fixture.source.authority, fixture.budget)
        val restored = (CanonicalQueryCliDocuments.project(retrieved) as ProjectedOperationOutcome.Complete).document
        assertEquals(full, Json.parseToJsonElement(restored.present(ToolOutputDetail.VERBOSE).value).jsonObject)
        assertEquals(0, fixture.semanticCalls)
    }

    @Test
    fun `symbol node description retrieves full signatures with current fields instead of the original run fields`() =
        runTest {
            val fixture = RetainedSymbolGraphFixture()
            val issued = fixture.issue()
            val page =
                fixture.retained.protocol.executePage(
                    QueryRunRequest.ReadResult.symbols(
                        issued.reference,
                        output = fixture.selectedOutput,
                        evidenceCursor = QueryEvidenceCursor.parse(0).graphFixtureValue(),
                    ),
                    fixture.retained.source.authority,
                    fixture.retained.budget,
                ) as OperationOutcome.Complete
            val document = (CanonicalQueryCliDocuments.project(page) as ProjectedOperationOutcome.Complete).document
            val compact = Json.parseToJsonElement(document.present(ToolOutputDetail.COMPACT).value).jsonObject
            val full = Json.parseToJsonElement(document.present(ToolOutputDetail.VERBOSE).value).jsonObject
            assertCompactNodeDescriptions(full, compact)
            val request = graphDetailRequest(compact)
            assertEquals(
                QueryRunRequest.ReadResult.symbols(
                    issued.reference,
                    output = fixture.selectedOutput,
                    evidenceCursor = QueryEvidenceCursor.parse(0).graphFixtureValue(),
                ),
                request,
            )
            val retrieved =
                fixture.retained.protocol.executePage(
                    request,
                    fixture.retained.source.authority,
                    fixture.retained.budget,
                )
            val restored =
                (CanonicalQueryCliDocuments.project(retrieved) as ProjectedOperationOutcome.Complete).document
            assertEquals(full, Json.parseToJsonElement(restored.present(ToolOutputDetail.VERBOSE).value).jsonObject)
            assertEquals(0, fixture.retained.semanticCalls)
        }
}

internal fun assertCompactNodeDescriptions(full: JsonObject, compact: JsonObject) {
    val completeSymbols =
        full
            .getValue("reference_observations")
            .jsonArray
            .flatMap { item ->
                val reference = item.jsonObject
                listOf(
                    reference.getValue("target").jsonObject,
                    reference.getValue("ownership").jsonObject.getValue("declaration").jsonObject,
                )
            }
            .associateBy { it.getValue("selector").jsonPrimitive.content }
    val graph = compact.getValue("relation_graph").jsonObject
    for (entry in graph.getValue("nodes").jsonArray) {
        val symbol = entry.jsonObject.getValue("symbol").jsonObject
        assertNotNull(
            symbol["compiler_identity"],
            "Compact nodes need an explicit compiler identity and a retrievable full signature",
        )
        assertNull(symbol["compilerEvidence"])
        assertNull(symbol["signature"])
        val original = completeSymbols.getValue(symbol.getValue("selector").jsonPrimitive.content)
        assertNodeDescription(original, symbol)
        assertNotNull(original.getValue("compilerEvidence").jsonObject["signature"])
    }
    assertEquals(
        graph.getValue("basis"),
        graph.getValue("detail").jsonObject.getValue("request").jsonObject.getValue("result"),
    )
}

internal fun graphDetailRequest(compact: JsonObject): QueryRunRequest.ReadResult {
    val detail = compact.getValue("relation_graph").jsonObject.getValue("detail").jsonObject
    assertEquals("true", detail.getValue("verbose").jsonPrimitive.content)
    val request = detail.getValue("request").jsonObject
    assertEquals("READ_RESULT", request.getValue("type").jsonPrimitive.content)
    val result = QueryResultReference.parse(request.getValue("result").jsonPrimitive.content).graphFixtureValue()
    val cursor = QueryResultCursor.parse(request.getValue("cursor").jsonPrimitive.content.toInt()).graphFixtureValue()
    val evidence =
        QueryEvidenceCursor.parse(request.getValue("evidence_cursor").jsonPrimitive.content.toInt()).graphFixtureValue()
    val output = request.getValue("output").jsonObject
    return when (output.getValue("type").jsonPrimitive.content) {
        "OCCURRENCES" -> QueryRunRequest.ReadResult.occurrences(result, cursor, evidenceCursor = evidence)
        "SYMBOLS" ->
            QueryRunRequest.ReadResult.symbols(
                result,
                cursor,
                QueryOutputDocument.Symbols(
                    graphFixtureList(
                        output.getValue("fields").jsonArray.map { field ->
                            QuerySymbolFieldDocument.valueOf(field.jsonPrimitive.content)
                        }
                    )
                ),
                evidenceCursor = evidence,
            )
        else -> error("An unknown output cannot establish an exact detail request")
    }
}
