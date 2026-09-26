package io.github.amichne.kast.protocol.contract

import io.github.amichne.kast.kernel.Refinement
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class QueryResultReferencesTest {
    @Test
    fun `retained result reference cannot be an exact symbol or execution continuation`() {
        assertInstanceOf(
            Refinement.Refined::class.java,
            QueryResultReference.parse("result:v1:00000000-0000-0000-0000-000000000001"),
        )
        for (other in listOf("exact:v5:opaque", "query:v1:00000000-0000-0000-0000-000000000001")) {
            assertEquals(
                Refinement.Rejected(QueryResultReferenceFailure.MALFORMED),
                QueryResultReference.parse(other),
            )
        }
    }

    @Test
    fun `presentation cursor has a bounded nonnegative domain`() {
        assertEquals(Refinement.Refined(QueryResultCursor.Start), QueryResultCursor.parse(0))
        for (outside in listOf(-1, 1_000_001)) {
            assertEquals(Refinement.Rejected(QueryResultCursorFailure.OUT_OF_RANGE), QueryResultCursor.parse(outside))
        }
    }

    @Test
    fun `row reference serializer admits only its issued family`() {
        val issued = "result-row:v1:00000000-0000-0000-0000-000000000001"
        val row = (QueryResultRowReference.parse(issued) as Refinement.Refined).value
        val json = Json
        assertEquals("\"$issued\"", json.encodeToString(QueryResultRowReference.serializer(), row))
        assertEquals(row, json.decodeFromString(QueryResultRowReference.serializer(), "\"$issued\""))
        for (other in
            listOf(
                "result:v1:00000000-0000-0000-0000-000000000001",
                "query:v1:00000000-0000-0000-0000-000000000001",
                "exact:v2:opaque",
                "result-row:v1:00000000-0000-0000-0000-00000000000G",
            )) {
            assertEquals(
                Refinement.Rejected(QueryResultRowReferenceFailure.MALFORMED),
                QueryResultRowReference.parse(other),
            )
            assertThrows(SerializationException::class.java) {
                json.decodeFromString(QueryResultRowReference.serializer(), "\"$other\"")
            }
        }
    }
}
