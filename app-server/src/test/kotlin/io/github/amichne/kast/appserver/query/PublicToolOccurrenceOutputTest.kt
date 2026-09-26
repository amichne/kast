package io.github.amichne.kast.appserver.query

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.contract.QueryOutputDocument
import io.github.amichne.kast.protocol.contract.QueryRunRequest
import io.github.amichne.kast.protocol.registry.PublicToolIdentity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PublicToolOccurrenceOutputTest {
    @Test
    fun `occurrence output lowers from run to the canonical choice`() {
        val reference = (ProtocolText.parse("NON_ISSUED_SCHEMA_TEST_ONLY") as Refinement.Refined).value
        val refs = (BoundedProtocolList.create(listOf(reference)) as Refinement.Refined).value
        val action = PublicToolRunAction(PublicToolReferenceSource(refs), null, QueryOutputDocument.Occurrences)
        val input = Json.encodeToJsonElement(PublicToolQuerySymbols.serializer(), PublicToolQuerySymbols(action))
        val request = input.jsonObject.getValue("request").jsonObject
        assertEquals("occurrences", request.getValue("output").jsonObject.getValue("type").jsonPrimitive.content)
        val admitted = PublicToolContract.admit(PublicToolIdentity.QUERY_SYMBOLS, input) as Refinement.Refined
        val canonical = (admitted.value.canonical as PublicToolCanonical.Query).request as QueryRunRequest.Run
        assertEquals(QueryOutputDocument.Occurrences, canonical.output)
    }
}
