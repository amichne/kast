package io.github.amichne.kast.appserver.query

import io.github.amichne.kast.appserver.publicNameQuery
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.QueryDeclarationKindDocument
import io.github.amichne.kast.protocol.contract.QueryExecutionContinuation
import io.github.amichne.kast.protocol.contract.QueryFromDocument
import io.github.amichne.kast.protocol.contract.QueryMatchDocument
import io.github.amichne.kast.protocol.contract.QueryOutputDocument
import io.github.amichne.kast.protocol.contract.QueryResultCursor
import io.github.amichne.kast.protocol.contract.QueryResultReference
import io.github.amichne.kast.protocol.contract.QueryResultRowReference
import io.github.amichne.kast.protocol.contract.QueryRetentionModeDocument
import io.github.amichne.kast.protocol.contract.QueryRunRequest
import io.github.amichne.kast.protocol.contract.QueryStepDocument
import io.github.amichne.kast.protocol.contract.QuerySymbolFieldDocument
import io.github.amichne.kast.protocol.contract.SymbolDiscoveryMatchDocument
import io.github.amichne.kast.protocol.registry.PublicToolIdentity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PublicToolContinuationTest {
    @Test
    fun `typed pipeline and output continuations retain exact opaque bytes through facade lowering`() {
        val tokens =
            listOf(
                (QueryExecutionContinuation.Pipeline.parse("query:v1:00000000-0000-0000-0000-000000000000")
                        as Refinement.Refined)
                    .value,
                (QueryExecutionContinuation.Output.parse("query-output:v1:00000000-0000-0000-0000-000000000000")
                        as Refinement.Refined)
                    .value,
            )
        tokens.forEach { token ->
            val document = PublicToolQuerySymbols(PublicToolResumeAction(token))
            val encoded = Json.encodeToJsonElement(PublicToolQuerySymbols.serializer(), document)
            val admitted = PublicToolContract.admit(PublicToolIdentity.QUERY_SYMBOLS, encoded) as Refinement.Refined
            val canonical = (admitted.value.canonical as PublicToolCanonical.Query).request
            assertEquals(token, (canonical as QueryRunRequest.Resume).continuation)
            assertEquals(
                token.value,
                PublicToolContract.encode(admitted.value)
                    .jsonObject
                    .getValue("request")
                    .jsonObject
                    .getValue("continuation")
                    .jsonPrimitive
                    .content,
            )
        }
    }

    @Test
    fun `retained result source lowers without reconstructing exact symbol references`() {
        val reference =
            (QueryResultReference.parse("result:v1:00000000-0000-0000-0000-000000000000") as Refinement.Refined).value
        val input =
            PublicToolQuerySymbols(
                PublicToolRunAction(
                    source = PublicToolResultSource(reference),
                    steps = null,
                    output = null,
                    retention = PublicToolRetention.RETAIN,
                )
            )
        val admitted =
            PublicToolContract.admit(
                PublicToolIdentity.QUERY_SYMBOLS,
                Json.encodeToJsonElement(PublicToolQuerySymbols.serializer(), input),
            ) as Refinement.Refined
        val run = (admitted.value.canonical as PublicToolCanonical.Query).request as QueryRunRequest.Run
        assertEquals(QueryFromDocument.Result(reference), run.from)
        assertEquals(QueryRetentionModeDocument.RETAIN, run.retention)
        assertEquals(
            "RESULT",
            PublicToolContract.encode(admitted.value)
                .jsonObject
                .getValue("request")
                .jsonObject
                .getValue("source")
                .jsonObject
                .getValue("type")
                .jsonPrimitive
                .content,
        )
    }

    @Test
    fun `selected retained rows lower through source concat and retained only set steps`() {
        val reference =
            (QueryResultReference.parse("result:v1:00000000-0000-0000-0000-000000000000") as Refinement.Refined).value
        val rowId =
            (QueryResultRowReference.parse("result-row:v1:00000000-0000-0000-0000-000000000001") as Refinement.Refined)
                .value
        val rowIds = (BoundedProtocolList.create(listOf(rowId)) as Refinement.Refined).value
        val selected = PublicToolResultSource(reference, rowIds)
        val steps =
            (BoundedProtocolList.create(
                    listOf<PublicToolStep>(
                        PublicToolConcat(selected),
                        PublicToolIntersect(selected),
                        PublicToolUnion(selected),
                        PublicToolDifference(selected),
                    )
                ) as Refinement.Refined)
                .value
        val input = PublicToolQuerySymbols(PublicToolRunAction(selected, steps, null))
        val admitted =
            PublicToolContract.admit(
                PublicToolIdentity.QUERY_SYMBOLS,
                Json.encodeToJsonElement(PublicToolQuerySymbols.serializer(), input),
            ) as Refinement.Refined
        val request = (admitted.value.canonical as PublicToolCanonical.Query).request as QueryRunRequest.Run
        val expected = QueryFromDocument.Result(reference, rowIds)
        assertEquals(expected, request.from)
        assertEquals(expected, (request.steps.values[0] as QueryStepDocument.Concat).input)
        assertEquals(expected, (request.steps.values[1] as QueryStepDocument.Intersect).right)
        assertEquals(expected, (request.steps.values[2] as QueryStepDocument.Union).right)
        assertEquals(expected, (request.steps.values[3] as QueryStepDocument.Difference).right)

        val emptyRows = (BoundedProtocolList.create(emptyList<QueryResultRowReference>()) as Refinement.Refined).value
        val emptyInput =
            PublicToolQuerySymbols(PublicToolRunAction(PublicToolResultSource(reference, emptyRows), null, null))
        val emptyAdmitted =
            PublicToolContract.admit(
                PublicToolIdentity.QUERY_SYMBOLS,
                Json.encodeToJsonElement(PublicToolQuerySymbols.serializer(), emptyInput),
            ) as Refinement.Refined
        val emptyRequest = (emptyAdmitted.value.canonical as PublicToolCanonical.Query).request as QueryRunRequest.Run
        assertEquals(emptyRows, (emptyRequest.from as QueryFromDocument.Result).rowIds)
    }

    @Test
    fun `read result carries a presentation cursor and projection without execution plan`() {
        val reference =
            (QueryResultReference.parse("result:v1:00000000-0000-0000-0000-000000000000") as Refinement.Refined).value
        val cursor = (QueryResultCursor.parse(7) as Refinement.Refined).value
        val fields = (BoundedProtocolList.create(listOf(PublicToolFields.SIGNATURE)) as Refinement.Refined).value
        val input =
            PublicToolQuerySymbols(PublicToolReadResultAction(reference, cursor, PublicToolSymbolsOutput(fields)))
        val admitted =
            PublicToolContract.admit(
                PublicToolIdentity.QUERY_SYMBOLS,
                Json.encodeToJsonElement(PublicToolQuerySymbols.serializer(), input),
            ) as Refinement.Refined
        val request = (admitted.value.canonical as PublicToolCanonical.Query).request as QueryRunRequest.ReadResult
        assertEquals(reference, request.result)
        assertEquals(cursor, request.cursor)
        assertEquals(
            listOf(QuerySymbolFieldDocument.SIGNATURE),
            (request.output as QueryOutputDocument.Symbols).fields.values,
        )
        assertEquals(
            7,
            PublicToolContract.encode(admitted.value)
                .jsonObject
                .getValue("request")
                .jsonObject
                .getValue("cursor")
                .jsonPrimitive
                .int,
        )
    }

    @Test
    fun `query name search compiles explicit null once to exact scoped exhaustive search`() {
        val admitted =
            PublicToolContract.admit(
                PublicToolIdentity.QUERY_SYMBOLS,
                publicNameQuery(
                    "OrderService",
                    listOf(PublicToolDeclarationKinds.CLASS),
                    output =
                        PublicToolSymbolsOutput(
                            (BoundedProtocolList.create(
                                    listOf(
                                        PublicToolFields.NAME,
                                        PublicToolFields.LOCATION,
                                        PublicToolFields.SIGNATURE,
                                    )
                                ) as Refinement.Refined)
                                .value
                        ),
                ),
            )
        val request =
            ((admitted as Refinement.Refined).value.canonical as PublicToolCanonical.Query).request
                as QueryRunRequest.Run
        val source = request.from as QueryFromDocument.Symbols
        assertEquals(SymbolDiscoveryMatchDocument.EXACT_NAME, (source.match as QueryMatchDocument.Name).matching)
        assertEquals(listOf(QueryDeclarationKindDocument.CLASS), source.declarationKinds.values)
        assertEquals(listOf("main", "test"), source.scope.sourceSets.values.map { it.value })
        assertEquals(".", source.scope.directory!!.path.value)
        assertEquals(
            listOf(
                QuerySymbolFieldDocument.NAME,
                QuerySymbolFieldDocument.LOCATION,
                QuerySymbolFieldDocument.SIGNATURE,
            ),
            (request.output as QueryOutputDocument.Symbols).fields.values,
        )
        assertTrue(request.steps.values.isEmpty())
    }
}
