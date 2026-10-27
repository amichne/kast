package io.github.amichne.kast.protocol.contract

import io.github.amichne.kast.kernel.Refinement
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class QueryOriginalEvidenceSummaryEncodingTest {
    private val json = Json { encodeDefaults = true }

    @Test
    fun `empty reason sets omit redundant arrays and preserve required counts`() {
        val summary = QueryOriginalEvidenceSummaryDocument(offset(0), offset(0), offset(7))
        val encoded = json.encodeToString(QueryOriginalEvidenceSummaryDocument.serializer(), summary)
        val fields = Json.parseToJsonElement(encoded).jsonObject
        assertEquals(setOf("failure_count", "omission_count", "evidence_count"), fields.keys)
        assertEquals("0", fields.getValue("failure_count").jsonPrimitive.content)
        assertEquals("0", fields.getValue("omission_count").jsonPrimitive.content)
        assertEquals("7", fields.getValue("evidence_count").jsonPrimitive.content)
        assertEquals(summary, json.decodeFromString(QueryOriginalEvidenceSummaryDocument.serializer(), encoded))
    }

    @Test
    fun `nonempty finite reason sets preserve their enum values`() {
        val summary =
            QueryOriginalEvidenceSummaryDocument(
                offset(2),
                offset(0),
                offset(2),
                failureKinds = bounded(listOf(QueryEvidenceFailureKind.PREDICATE)),
                predicateReasons = bounded(listOf(QueryPredicateFailureDocument.PREDICATE_UNPROVEN)),
            )
        val encoded = json.encodeToString(QueryOriginalEvidenceSummaryDocument.serializer(), summary)
        val fields = Json.parseToJsonElement(encoded).jsonObject
        assertEquals(
            setOf("failure_count", "omission_count", "evidence_count", "failure_kinds", "predicate_reasons"),
            fields.keys,
        )
        assertEquals(listOf("PREDICATE"), fields.getValue("failure_kinds").jsonArray.map { it.jsonPrimitive.content })
        assertEquals(
            listOf("PREDICATE_UNPROVEN"),
            fields.getValue("predicate_reasons").jsonArray.map { it.jsonPrimitive.content },
        )
        assertEquals(summary, json.decodeFromString(QueryOriginalEvidenceSummaryDocument.serializer(), encoded))
    }

    @Test
    fun `an absent required count cannot become a proven zero`() {
        val summary = QueryOriginalEvidenceSummaryDocument(offset(0), offset(0), offset(7))
        val encoded = json.encodeToString(QueryOriginalEvidenceSummaryDocument.serializer(), summary)
        val missingCounts =
            listOf(
                encoded.replace("\"failure_count\":0,", ""),
                encoded.replace("\"omission_count\":0,", ""),
                encoded.replace(",\"evidence_count\":7", ""),
            )
        for (document in missingCounts) {
            assertNotEquals(encoded, document)
            assertThrows(SerializationException::class.java) {
                json.decodeFromString(QueryOriginalEvidenceSummaryDocument.serializer(), document)
            }
        }
    }

    @Test
    fun `unknown finite reason and null reason collection remain rejected`() {
        val summary =
            QueryOriginalEvidenceSummaryDocument(
                offset(1),
                offset(0),
                offset(1),
                predicateReasons = bounded(listOf(QueryPredicateFailureDocument.PREDICATE_UNPROVEN)),
            )
        val encoded = json.encodeToString(QueryOriginalEvidenceSummaryDocument.serializer(), summary)
        val invalidReasons =
            listOf(
                encoded.replace("PREDICATE_UNPROVEN", "UNKNOWN"),
                encoded.replace("\"predicate_reasons\":[\"PREDICATE_UNPROVEN\"]", "\"predicate_reasons\":null"),
            )
        for (document in invalidReasons) {
            assertNotEquals(encoded, document)
            assertThrows(SerializationException::class.java) {
                json.decodeFromString(QueryOriginalEvidenceSummaryDocument.serializer(), document)
            }
        }
    }
}

private fun offset(value: Int): ProtocolOffset = (ProtocolOffset.parse(value) as Refinement.Refined).value

private fun <T> bounded(values: List<T>): BoundedProtocolList<T> =
    (BoundedProtocolList.create(values) as Refinement.Refined).value
