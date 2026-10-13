package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.protocol.contract.QueryResultCursor
import io.github.amichne.kast.protocol.contract.QueryRunRequest
import io.github.amichne.kast.protocol.contract.ToolOutputDetail
import io.github.amichne.kast.protocol.wire.presentation.CanonicalJsonDocument
import io.github.amichne.kast.protocol.wire.presentation.CanonicalQueryCliDocuments
import io.github.amichne.kast.protocol.wire.presentation.ProjectedOperationOutcome
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class RetainedSymbolGraphFallbackTest {
    @Test
    fun `legacy absent selection proof preserves full symbol rows`() = runTest {
        val (fixture, page) = page()
        val legacy =
            page.copy(evidence = page.evidence.copy(payload = page.evidence.payload.copy(presentationOutput = null)))
        val projected = (CanonicalQueryCliDocuments.project(legacy) as ProjectedOperationOutcome.Complete).document
        val compact = json(projected, ToolOutputDetail.COMPACT)
        assertNull(compact["relation_graph"])
        assertEquals(json(projected, ToolOutputDetail.VERBOSE).getValue("items"), compact.getValue("items"))
        assertEquals(0, fixture.retained.semanticCalls)
    }

    @Test
    fun `symbols without connection proof preserve full output`() = runTest {
        val (fixture, page) = page()
        val items =
            page.evidence.payload.items.values.map { item ->
                (item as io.github.amichne.kast.protocol.contract.QueryResultItemDocument.ExactSymbol).copy(
                    connections = graphFixtureList(emptyList())
                )
            }
        val noConnections =
            page.copy(
                evidence = page.evidence.copy(payload = page.evidence.payload.copy(items = graphFixtureList(items)))
            )
        val projected =
            (CanonicalQueryCliDocuments.project(noConnections) as ProjectedOperationOutcome.Complete).document
        val compact = json(projected, ToolOutputDetail.COMPACT)
        assertNull(compact["relation_graph"])
        assertEquals(json(projected, ToolOutputDetail.VERBOSE).getValue("items"), compact.getValue("items"))
        assertEquals(0, fixture.retained.semanticCalls)
    }

    @Test
    fun `empty symbol page preserves all reference evidence in full output`() = runTest {
        val fixture = RetainedSymbolGraphFixture()
        val issued = fixture.issue()
        val page =
            fixture.retained.protocol.executePage(
                QueryRunRequest.ReadResult.symbols(
                    issued.reference,
                    QueryResultCursor.parse(53).graphFixtureValue(),
                    fixture.selectedOutput,
                ),
                fixture.retained.source.authority,
                fixture.retained.budget,
            )
        val projected = (CanonicalQueryCliDocuments.project(page) as ProjectedOperationOutcome.Complete).document
        val compact = json(projected, ToolOutputDetail.COMPACT)
        assertNull(compact["relation_graph"])
        assertEquals(0, compact.getValue("items").jsonArray.size)
        assertEquals(53, compact.getValue("reference_observations").jsonArray.size)
        assertEquals(0, fixture.retained.semanticCalls)
    }

    private suspend fun page():
        Pair<
            RetainedSymbolGraphFixture,
            OperationOutcome.Complete<io.github.amichne.kast.protocol.contract.QueryRunResult>,
        > {
        val fixture = RetainedSymbolGraphFixture()
        val issued = fixture.issue()
        val page =
            fixture.retained.protocol.executePage(
                QueryRunRequest.ReadResult.symbols(issued.reference, output = fixture.selectedOutput),
                fixture.retained.source.authority,
                fixture.retained.budget,
            ) as OperationOutcome.Complete
        return fixture to page
    }

    private fun json(document: CanonicalJsonDocument, detail: ToolOutputDetail): JsonObject =
        Json.parseToJsonElement(document.present(detail).value).jsonObject
}
