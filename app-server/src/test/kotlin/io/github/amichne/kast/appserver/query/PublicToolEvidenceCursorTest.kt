package io.github.amichne.kast.appserver.query

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.ImpactWitnessSectionDocument
import io.github.amichne.kast.protocol.contract.QueryEvidenceCursor
import io.github.amichne.kast.protocol.contract.QueryOutputDocument
import io.github.amichne.kast.protocol.contract.QueryResultCursor
import io.github.amichne.kast.protocol.contract.QueryResultReference
import io.github.amichne.kast.protocol.contract.QueryRunRequest
import io.github.amichne.kast.protocol.contract.QuerySymbolFieldDocument
import io.github.amichne.kast.protocol.registry.PublicToolIdentity
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PublicToolEvidenceCursorTest {
    private val json = Json { encodeDefaults = true }
    private val result =
        (QueryResultReference.parse("result:v1:00000000-0000-0000-0000-000000000001") as Refinement.Refined).value
    private val rowCursor = (QueryResultCursor.parse(7) as Refinement.Refined).value
    private val evidenceCursor = (QueryEvidenceCursor.parse(11) as Refinement.Refined).value

    @Test
    fun `every retained output preserves independent row and evidence cursors`() {
        val fields = (BoundedProtocolList.create(emptyList<PublicToolFields>()) as Refinement.Refined).value
        val canonicalFields =
            (BoundedProtocolList.create(emptyList<QuerySymbolFieldDocument>()) as Refinement.Refined).value
        val outputs =
            listOf<Pair<PublicToolReadResultOutput, QueryOutputDocument>>(
                PublicToolSymbolsOutput(fields) to QueryOutputDocument.Symbols(canonicalFields),
                PublicToolOccurrencesOutput to QueryOutputDocument.Occurrences,
                PublicToolTraversalRecordsOutput to QueryOutputDocument.TraversalRecords,
                PublicToolBindingRowsOutput to QueryOutputDocument.BindingRows,
                PublicToolValuePathsOutput to QueryOutputDocument.ValuePaths,
            ) +
                ImpactWitnessSectionDocument.entries.map { section ->
                    PublicToolImpactWitnessOutput(section) to QueryOutputDocument.ImpactWitness(section)
                }
        for ((output, expected) in outputs) {
            val action = PublicToolReadResultAction(result, rowCursor, output, evidenceCursor = evidenceCursor)
            val encoded = json.encodeToJsonElement(PublicToolQuerySymbols(action))
            val request = encoded.jsonObject.getValue("request").jsonObject
            assertEquals("7", request.getValue("cursor").jsonPrimitive.content)
            assertEquals("11", request.getValue("evidence_cursor").jsonPrimitive.content)
            val canonical = admit(encoded)
            assertEquals(result, canonical.result)
            assertEquals(rowCursor, canonical.cursor)
            assertEquals(evidenceCursor, canonical.evidenceCursor)
            assertEquals(expected, canonical.output)
        }
    }

    @Test
    fun `omitted and null evidence cursors retain existing presentation defaults`() {
        val omitted = json.encodeToJsonElement(PublicToolQuerySymbols(PublicToolReadResultAction(result)))
        assertFalse(omitted.jsonObject.getValue("request").jsonObject.containsKey("evidence_cursor"))
        val explicitNull = evidenceInput("null")
        for (input in listOf(omitted, explicitNull)) {
            val canonical = admit(input)
            assertEquals(result, canonical.result)
            assertEquals(QueryResultCursor.Start, canonical.cursor)
            assertNull(canonical.evidenceCursor)
        }
    }

    @Test
    fun `evidence cursor bounds reject incompatible values before lowering`() {
        for (valid in listOf("0", "1000000")) {
            assertEquals(valid.toInt(), admit(evidenceInput(valid)).evidenceCursor!!.value)
        }
        for (invalid in listOf("-1", "1000001", "1.5", "\"11\"", "true")) {
            assertTrue(
                PublicToolContract.admit(PublicToolIdentity.QUERY_SYMBOLS, evidenceInput(invalid))
                    is Refinement.Rejected,
                invalid,
            )
        }
    }

    private fun admit(input: JsonElement): QueryRunRequest.ReadResult {
        val admitted = PublicToolContract.admit(PublicToolIdentity.QUERY_SYMBOLS, input) as Refinement.Refined
        return (admitted.value.canonical as PublicToolCanonical.Query).request as QueryRunRequest.ReadResult
    }

    private fun evidenceInput(raw: String): JsonElement =
        json.encodeToJsonElement(EvidenceCursorInput(EvidenceCursorAction(result, Json.parseToJsonElement(raw))))
}

/** Deliberately incompatible scalar values exercise public schema and typed cursor rejection. */
@Serializable private data class EvidenceCursorInput(val request: EvidenceCursorAction)

@Serializable
private data class EvidenceCursorAction(
    val result: QueryResultReference,
    @SerialName("evidence_cursor") val evidenceCursor: JsonElement,
    val type: EvidenceCursorActionType = EvidenceCursorActionType.READ_RESULT,
)

@Serializable
private enum class EvidenceCursorActionType {
    READ_RESULT
}
