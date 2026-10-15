package io.github.amichne.kast.protocol.contract

import io.github.amichne.kast.kernel.Refinement
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class QueryCallbackGraphSubjectDocumentTest {
    private val occurrence =
        QueryCallbackGraphBoundaryDocument.from(
            ProtocolText.parse("src/sample.kt").refined(),
            SourceRangeDocument.create(ProtocolOffset.parse(2).refined(), ProtocolOffset.parse(3).refined()).refined(),
        )

    @Test
    fun `callback and callable subjects encode distinct lists and exact source locations`() {
        val cases =
            listOf(
                QueryCallbackGraphSubjectDocument.Callback(occurrence, occurrence) to "CALLBACK",
                QueryCallbackGraphSubjectDocument.Callable(occurrence) to "CALLABLE",
            )
        for ((subject, type) in cases) {
            val encoded = Json.encodeToJsonElement(QueryCallbackGraphSubjectDocument.serializer(), subject).jsonObject
            assertEquals(type, encoded.getValue("type").jsonPrimitive.content)
            assertEquals(
                if (type == "CALLBACK") setOf("type", "occurrence", "body") else setOf("type", "occurrence"),
                encoded.keys,
            )
            val location = encoded.getValue("occurrence").jsonObject
            assertEquals(setOf("file", "start", "end"), location.keys)
            assertEquals("src/sample.kt", location.getValue("file").jsonPrimitive.content)
            assertEquals("2", location.getValue("start").jsonPrimitive.content)
            assertEquals("3", location.getValue("end").jsonPrimitive.content)
            assertEquals(subject, Json.decodeFromJsonElement(QueryCallbackGraphSubjectDocument.serializer(), encoded))
        }
    }

    @Test
    fun `missing callback body unknown list and callable body are rejected`() {
        for (subject in
            listOf(
                InvalidGraphSubject("CALLBACK", occurrence),
                InvalidGraphSubject("UNKNOWN", occurrence),
                InvalidGraphSubject("CALLABLE", occurrence, occurrence),
            )) {
            val encoded = Json.encodeToJsonElement(InvalidGraphSubject.serializer(), subject)
            assertThrows(SerializationException::class.java) {
                Json.decodeFromJsonElement(QueryCallbackGraphSubjectDocument.serializer(), encoded)
            }
        }
    }
}

@Serializable
private data class InvalidGraphSubject(
    val type: String,
    val occurrence: QueryCallbackGraphBoundaryDocument,
    val body: QueryCallbackGraphBoundaryDocument? = null,
)

private fun <T, F> Refinement<T, F>.refined(): T =
    when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> error("Fixture rejected: $failure")
    }
