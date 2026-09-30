package io.github.amichne.kast.appserver.query

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.contract.QueryOutputDocument
import io.github.amichne.kast.protocol.contract.QueryResultCursor
import io.github.amichne.kast.protocol.contract.QueryResultReference
import io.github.amichne.kast.protocol.contract.QueryRunRequest
import io.github.amichne.kast.protocol.registry.PublicToolIdentity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PublicToolOccurrenceOutputTest {
    @Test
    fun `retained traversal output preserves the exact depth bearing presentation choice`() {
        val result =
            (QueryResultReference.parse("result:v1:00000000-0000-0000-0000-000000000001") as Refinement.Refined).value
        val cursor = (QueryResultCursor.parse(100) as Refinement.Refined).value
        val action = PublicToolReadResultAction(result, cursor, PublicToolTraversalRecordsOutput)
        val input = Json.encodeToJsonElement(PublicToolQuerySymbols.serializer(), PublicToolQuerySymbols(action))
        val request = input.jsonObject.getValue("request").jsonObject
        assertEquals("READ_RESULT", request.getValue("type").jsonPrimitive.content)
        assertEquals("TRAVERSAL_RECORDS", request.getValue("output").jsonObject.getValue("type").jsonPrimitive.content)
        val admitted = PublicToolContract.admit(PublicToolIdentity.QUERY_SYMBOLS, input) as Refinement.Refined
        val canonical = (admitted.value.canonical as PublicToolCanonical.Query).request as QueryRunRequest.ReadResult
        assertEquals(result, canonical.result)
        assertEquals(cursor, canonical.cursor)
        assertEquals(QueryOutputDocument.TraversalRecords, canonical.output)
    }

    @Test
    fun `retained occurrence output preserves the admitted result and presentation cursor`() {
        val result =
            (QueryResultReference.parse("result:v1:00000000-0000-0000-0000-000000000001") as Refinement.Refined).value
        val cursor = (QueryResultCursor.parse(100) as Refinement.Refined).value
        val action = PublicToolReadResultAction(result, cursor, PublicToolOccurrencesOutput)
        val input = Json.encodeToJsonElement(PublicToolQuerySymbols.serializer(), PublicToolQuerySymbols(action))
        val request = input.jsonObject.getValue("request").jsonObject
        assertEquals("READ_RESULT", request.getValue("type").jsonPrimitive.content)
        assertEquals(result.value, request.getValue("result").jsonPrimitive.content)
        assertEquals("100", request.getValue("cursor").jsonPrimitive.content)
        assertEquals("OCCURRENCES", request.getValue("output").jsonObject.getValue("type").jsonPrimitive.content)
        val admitted = PublicToolContract.admit(PublicToolIdentity.QUERY_SYMBOLS, input) as Refinement.Refined
        val canonical = (admitted.value.canonical as PublicToolCanonical.Query).request as QueryRunRequest.ReadResult
        assertEquals(result, canonical.result)
        assertEquals(cursor, canonical.cursor)
        assertEquals(QueryOutputDocument.Occurrences, canonical.output)
    }

    @Test
    fun `occurrence output lowers from run to the canonical choice`() {
        val reference = (ProtocolText.parse("NON_ISSUED_SCHEMA_TEST_ONLY") as Refinement.Refined).value
        val refs = (BoundedProtocolList.create(listOf(reference)) as Refinement.Refined).value
        val action = PublicToolRunAction(PublicToolReferenceSource(refs), null, PublicToolOccurrencesOutput)
        val input = Json.encodeToJsonElement(PublicToolQuerySymbols.serializer(), PublicToolQuerySymbols(action))
        val request = input.jsonObject.getValue("request").jsonObject
        assertEquals("OCCURRENCES", request.getValue("output").jsonObject.getValue("type").jsonPrimitive.content)
        val admitted = PublicToolContract.admit(PublicToolIdentity.QUERY_SYMBOLS, input) as Refinement.Refined
        val canonical = (admitted.value.canonical as PublicToolCanonical.Query).request as QueryRunRequest.Run
        assertEquals(QueryOutputDocument.Occurrences, canonical.output)
    }
}
