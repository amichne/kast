package io.github.amichne.kast.appserver.query

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.ProtocolCount
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.contract.QueryFromDocument
import io.github.amichne.kast.protocol.contract.QueryOutputDocument
import io.github.amichne.kast.protocol.contract.QueryReferenceDocument
import io.github.amichne.kast.protocol.contract.QuerySymbolFieldDocument
import io.github.amichne.kast.protocol.contract.RelationKindDocument
import io.github.amichne.kast.protocol.contract.RelationReadRequest
import io.github.amichne.kast.protocol.contract.SourceReadAnchorDocument
import io.github.amichne.kast.protocol.contract.TraversalRunRequest
import io.github.amichne.kast.protocol.registry.PublicToolIdentity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Fixture handles prove surface preservation, not live host issuance or semantic resolution. */
class PublicReferenceSurfaceTest {
    private val token = text("exact:v5:AAAAAAAAAAAAAAAAAAAAAA")
    private val reference = ExactSymbolRef(token)

    @Test
    fun `reference types serialize as the existing strings without another identity envelope`() {
        assertEquals(JsonPrimitive(token.value), Json.encodeToJsonElement(ExactSymbolRef.serializer(), reference))
        assertEquals(reference, Json.decodeFromJsonElement(ExactSymbolRef.serializer(), JsonPrimitive(token.value)))
        val continuation = ContinuationRef(text("query:v1:EXAMPLE_NOT_ISSUED"))
        assertEquals(
            JsonPrimitive(continuation.token.value),
            Json.encodeToJsonElement(ContinuationRef.serializer(), continuation),
        )
        assertEquals(
            continuation,
            Json.decodeFromJsonElement(ContinuationRef.serializer(), JsonPrimitive(continuation.token.value)),
        )
    }

    @Test
    fun `ordinary search requests names and locations without repeating signatures`() {
        val name = text("Service")
        val cases =
            listOf(
                PublicToolIdentity.SEARCH_CLASSES to PublicToolSearchClasses(name, null, null),
                PublicToolIdentity.SEARCH_FUNCTIONS to PublicToolSearchFunctions(name, null, null),
                PublicToolIdentity.SEARCH_DECLARATIONS to PublicToolSearchDeclarations(name, null, null, null),
            )
        for ((identity, document) in cases) {
            val admitted = PublicToolContract.admit(identity, encodePublicTool(document, Json)) as Refinement.Refined
            val request = (admitted.value.canonical as PublicToolCanonical.Query).request
            assertEquals(
                listOf(QuerySymbolFieldDocument.NAME, QuerySymbolFieldDocument.LOCATION),
                (request.output as QueryOutputDocument.Symbols).fields.values,
            )
            assertTrue(request.steps.values.isEmpty())
        }
    }

    @Test
    fun `signature retrieval starts from the unchanged exact reference without discovery or refinement`() {
        val document =
            PublicToolQuerySymbols(
                PublicToolReferenceSource(bounded(listOf(reference))),
                null,
                bounded(listOf(PublicToolReturnFields.SIGNATURE)),
            )
        val encoded = Json.encodeToJsonElement(PublicToolQuerySymbols.serializer(), document)
        val admitted = PublicToolContract.admit(PublicToolIdentity.QUERY_SYMBOLS, encoded) as Refinement.Refined
        val request = (admitted.value.canonical as PublicToolCanonical.Query).request
        val source = request.from as QueryFromDocument.References
        assertEquals(token, (source.values.values.single() as QueryReferenceDocument.ExactSymbol).token)
        assertTrue(request.steps.values.isEmpty())
        assertEquals(
            listOf(QuerySymbolFieldDocument.SIGNATURE),
            (request.output as QueryOutputDocument.Symbols).fields.values,
        )
        assertEquals(encoded, PublicToolContract.encode(admitted.value))
    }

    @Test
    fun `the same exact reference remains a source anchor and a relation or traversal input`() {
        val source = SourceReadAnchorDocument.admit(reference.token) as Refinement.Refined
        assertEquals(token, (source.value as SourceReadAnchorDocument.Symbol).selector)
        val limit = (ProtocolCount.parse(1) as Refinement.Refined).value
        val relation = RelationReadRequest(reference.token, RelationKindDocument.CALLERS, limit)
        val traversal = TraversalRunRequest(reference.token, RelationKindDocument.CALLERS, limit, limit)
        assertEquals(
            JsonPrimitive(token.value),
            Json.encodeToJsonElement(RelationReadRequest.serializer(), relation).jsonObject.getValue("exactSelector"),
        )
        assertEquals(
            JsonPrimitive(token.value),
            Json.encodeToJsonElement(TraversalRunRequest.serializer(), traversal).jsonObject.getValue("exactSelector"),
        )
    }

    private fun text(raw: String): ProtocolText = (ProtocolText.parse(raw) as Refinement.Refined).value

    private fun <T> bounded(values: List<T>): BoundedProtocolList<T> =
        (BoundedProtocolList.create(values) as Refinement.Refined).value
}
