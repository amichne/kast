package io.github.amichne.kast.cli

import io.github.amichne.kast.appserver.query.PublicToolCanonical
import io.github.amichne.kast.appserver.query.PublicToolContract
import io.github.amichne.kast.kernel.EvidenceBasis
import io.github.amichne.kast.kernel.EvidenceEnvelope
import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.CanonicalOperation
import io.github.amichne.kast.protocol.contract.ProtocolCount
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.contract.QueryExactFailureDocument
import io.github.amichne.kast.protocol.contract.QueryFromDocument
import io.github.amichne.kast.protocol.contract.QueryItemFailureDocument
import io.github.amichne.kast.protocol.contract.QueryPredicateFailureDocument
import io.github.amichne.kast.protocol.contract.QueryReferenceDocument
import io.github.amichne.kast.protocol.contract.QueryRelationFailureDocument
import io.github.amichne.kast.protocol.contract.QueryResultItemDocument
import io.github.amichne.kast.protocol.contract.QueryRunRequest
import io.github.amichne.kast.protocol.contract.QueryRunResult
import io.github.amichne.kast.protocol.contract.RelationKindDocument
import io.github.amichne.kast.protocol.contract.RelationReadRequest
import io.github.amichne.kast.protocol.contract.SourceReadAnchorDocument
import io.github.amichne.kast.protocol.contract.SymbolIdDocument
import io.github.amichne.kast.protocol.contract.SymbolKindDocument
import io.github.amichne.kast.protocol.registry.PublicToolIdentity
import io.github.amichne.kast.protocol.wire.presentation.CanonicalQueryCliDocuments
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Representation and admission tests; the synthetic references do not establish live IDE authority. */
class QueryReferenceSurfaceTest {
    private val json = Json { encodeDefaults = true }
    private val schema = LiveReadOutputSchemaTest()
    private val exactToken = "exact:v5:" + "A".repeat(22)
    private val candidateToken = "candidate:v5:" + "A".repeat(22)

    @Test
    fun `query items expose the issued reference once without canonical identity reconstruction`() {
        val item = exact()
        val document = project(listOf(item))
        schema.assertAdmits(CanonicalOperation.QUERY_RUN, document)
        val output = document.getValue("items").jsonArray.single().jsonObject
        assertEquals(JsonPrimitive(exactToken), output["ref"])
        assertEquals(JsonPrimitive("exact-symbol"), output["type"])
        assertEquals(
            setOf("type", "ref", "kind", "name", "location", "signature", "connections"),
            output.keys,
        )
        assertEquals(JsonPrimitive("run"), output["name"])
        // These fields remain governed by the existing requested projection in this iteration.
        assertEquals(JsonNull, output["location"])
        assertEquals(JsonNull, output["signature"])
        assertTrue(output.getValue("connections").jsonArray.isEmpty())
    }

    @Test
    fun `legacy reference objects and duplicated identity aliases are not the new output contract`() {
        val legacy = json.encodeToJsonElement(LegacyReference.serializer(), LegacyReference("exact-symbol", exactToken))
        for (invalid in
            listOf(
                InvalidExactItem(legacy),
                InvalidExactItem(JsonPrimitive(exactToken), symbolRef = exactToken),
                InvalidExactItem(JsonPrimitive(exactToken), symbolId = "sym:" + "A".repeat(43)),
            )) {
            schema.assertRejects(CanonicalOperation.QUERY_RUN, negative(invalid))
        }
    }

    @Test
    fun `the returned exact reference is copied verbatim into query relation and source admission`() {
        val returned =
            project(listOf(exact()))
                .getValue("items")
                .jsonArray
                .single()
                .jsonObject
                .getValue("ref")
                .jsonPrimitive
                .content
        val followup = json.parseToJsonElement(PublicQueryInputFixture.references(listOf(returned)))
        val admitted = PublicToolContract.admit(PublicToolIdentity.QUERY_SYMBOLS, followup).refined()
        val request = (admitted.canonical as PublicToolCanonical.Query).request as QueryRunRequest.Run
        val references = request.from as QueryFromDocument.References
        assertEquals(returned, references.values.values.single().token.value)
        assertTrue(request.steps.values.isEmpty())

        val relation =
            RelationReadRequest(text(returned), RelationKindDocument.CALLERS, ProtocolCount.parse(10).refined())
        assertEquals(
            JsonPrimitive(returned),
            json.encodeToJsonElement(RelationReadRequest.serializer(), relation).jsonObject["exactSelector"],
        )
        val source = SourceReadAnchorDocument.admit(text(returned)).refined()
        assertTrue(source is SourceReadAnchorDocument.Symbol)
        assertEquals(text(returned), (source as SourceReadAnchorDocument.Symbol).selector)
    }

    @Test
    fun `reference projection does not insert deduplication or reorder results`() {
        val second = "exact:v5:" + "B".repeat(22)
        val document = project(listOf(exact(), exact(second), exact()))
        schema.assertAdmits(CanonicalOperation.QUERY_RUN, document)
        assertEquals(
            listOf(exactToken, second, exactToken),
            document.getValue("items").jsonArray.map { it.jsonObject.getValue("ref").jsonPrimitive.content },
        )
    }

    @Test
    fun `query item failures preserve the reference and the finite failure`() {
        val exact = QueryReferenceDocument.ExactSymbol(text(exactToken))
        val failures =
            listOf(
                QueryItemFailureDocument.Refinement(
                    QueryReferenceDocument.DeclarationCandidate(text(candidateToken)),
                    QueryExactFailureDocument.STALE_GENERATION,
                ),
                QueryItemFailureDocument.ExactReference(exact, QueryExactFailureDocument.STALE_GENERATION),
                QueryItemFailureDocument.Predicate(exact, QueryPredicateFailureDocument.PREDICATE_UNPROVEN),
                QueryItemFailureDocument.Relation(
                    exact,
                    RelationKindDocument.CALLERS,
                    QueryRelationFailureDocument.STALE_SELECTOR,
                ),
            )
        val document = project(emptyList(), failures)
        schema.assertAdmits(CanonicalOperation.QUERY_RUN, document)
        val output = document.getValue("failures").jsonArray.map { it.jsonObject }
        assertEquals(
            listOf(candidateToken, exactToken, exactToken, exactToken),
            output.map { it.getValue("ref").jsonPrimitive.content },
        )
        assertEquals(
            listOf("refinement", "exact-reference", "predicate", "relation"),
            output.map { it.getValue("type").jsonPrimitive.content },
        )
        assertEquals(
            listOf("stale-generation", "stale-generation", "predicate-unproven", "stale-selector"),
            output.map { it.getValue("reason").jsonPrimitive.content },
        )
        assertEquals(JsonPrimitive("callers"), output.last()["relation"])
    }

    @Test
    fun `reference families have named reusable standalone output schemas`() {
        val definitions = installedServerOutputSchema(CanonicalOperation.QUERY_RUN).getValue("\$defs").jsonObject
        for (name in listOf("CandidateRef", "ExactSymbolRef", "ContinuationRef")) {
            assertEquals(JsonPrimitive("string"), definitions.getValue(name).jsonObject["type"], name)
        }
        assertEquals(JsonPrimitive("^exact:v[2345]:"), definitions.getValue("ExactSymbolRef").jsonObject["pattern"])
        assertEquals(JsonPrimitive("^candidate:v[2345]:"), definitions.getValue("CandidateRef").jsonObject["pattern"])
    }

    private fun exact(token: String = exactToken) =
        QueryResultItemDocument.ExactSymbol(
            QueryReferenceDocument.ExactSymbol(text(token)),
            SymbolKindDocument.FUNCTION,
            text("run"),
            null,
            null,
            bounded(emptyList()),
            SymbolIdDocument.parse("sym:" + "A".repeat(43)).refined(),
        )

    private fun project(
        items: List<QueryResultItemDocument>,
        failures: List<QueryItemFailureDocument> = emptyList(),
    ): JsonObject {
        val outcome =
            CanonicalQueryCliDocuments.project(
                OperationOutcome.Complete(
                    EvidenceEnvelope(
                        CanonicalOperation.QUERY_RUN.id,
                        EvidenceBasis.Published(EvidenceGeneration.parse(7).refined()),
                        QueryRunResult(bounded(items), bounded(failures)),
                    )
                )
            )
        return with(schema) { outcome.document() }
    }

    private fun text(value: String) = ProtocolText.parse(value).refined()

    private fun <T> bounded(values: List<T>) = BoundedProtocolList.create(values).refined()

    private fun <T, F> Refinement<T, F>.refined(): T = (this as Refinement.Refined).value

    private fun negative(item: InvalidExactItem): JsonObject =
        json.encodeToJsonElement(InvalidQueryOutput.serializer(), InvalidQueryOutput(listOf(item))).jsonObject

    @Serializable
    private data class InvalidQueryOutput(
        val items: List<InvalidExactItem>,
        val operation: String = "query.run",
        val status: String = "complete",
        val failures: List<String> = emptyList(),
    )

    // JsonElement is confined to a deliberately incompatible reference in negative schema fixtures.
    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    @Serializable
    private data class InvalidExactItem(
        val ref: JsonElement,
        val type: String = "exact-symbol",
        val kind: String = "function",
        val name: String = "run",
        val location: String? = null,
        val signature: String? = null,
        val connections: List<String> = emptyList(),
        @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
        @SerialName("symbol_ref")
        val symbolRef: String? = null,
        @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
        @SerialName("symbol_id")
        val symbolId: String? = null,
    )

    @Serializable private data class LegacyReference(val kind: String, val token: String)
}
