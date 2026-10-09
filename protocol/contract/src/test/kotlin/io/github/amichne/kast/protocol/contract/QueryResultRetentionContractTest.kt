package io.github.amichne.kast.protocol.contract

import io.github.amichne.kast.kernel.Refinement
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class QueryResultRetentionContractTest {
    private val reference = "result:v1:00000000-0000-0000-0000-000000000001"

    @Test
    fun `successful retention has only the current requested and retained encodings`() {
        assertEquals(
            Json.encodeToString(ExpectedRetentionKindDocument("not_requested")),
            Json.encodeToString<QueryResultRetention>(QueryResultRetention.NotRequested),
        )
        val retained =
            QueryResultRetention.Retained((QueryResultReference.parse(reference) as Refinement.Refined).value)
        assertEquals(
            Json.encodeToString(ExpectedRetainedDocument("retained", reference)),
            Json.encodeToString<QueryResultRetention>(retained),
        )
    }

    @Test
    fun `obsolete successful capacity state is rejected`() {
        assertThrows(SerializationException::class.java) {
            Json.decodeFromString<QueryResultRetention>(
                Json.encodeToString(ExpectedRetentionKindDocument("capacity_exceeded"))
            )
        }
    }
}

@Serializable private data class ExpectedRetentionKindDocument(val kind: String)

@Serializable private data class ExpectedRetainedDocument(val kind: String, val reference: String)
