package io.github.amichne.kast.protocol.contract

import io.github.amichne.kast.kernel.Refinement
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SourceExcerptRequestTest {
    @Test
    fun `canonical excerpt encodes its discriminator and positive count independently of its region`() {
        val request = request(7)
        val encoded = Json.encodeToJsonElement(SourceReadRequest.serializer(), request)
        assertEquals(Json.parseToJsonElement("""{"type":"excerpt","maxLines":7}"""), encoded.jsonObject["text"])
        assertEquals(Refinement.Refined(request), SourceRequestIngress.decode(encoded, Json))
    }

    @Test
    fun `zero lines cannot serialize as a canonical excerpt`() {
        assertThrows(SerializationException::class.java) {
            Json.encodeToJsonElement(SourceReadRequest.serializer(), request(0))
        }
    }

    @Test
    fun `canonical ingress rejects absent zero and excess excerpt limits`() {
        for (text in
            listOf(
                """{"type":"excerpt"}""",
                """{"type":"excerpt","maxLines":0}""",
                """{"type":"excerpt","maxLines":1001}""",
            )) {
            val raw =
                Json.parseToJsonElement(
                    """
                {"anchor":{"type":"symbol-snapshot","selector":"exact:v5:AAAAAAAAAAAAAAAAAAAAAA"},
                 "region":{"type":"body","kind":"callable"},"entities":{"type":"none"},"text":$text}
                """
                        .trimIndent()
                )
            assertTrue(SourceRequestIngress.decode(raw, Json) is Refinement.Rejected, text)
        }
    }

    private fun request(lines: Int) =
        SourceReadRequest(
            anchor =
                SourceReadAnchorDocument.SnapshotSymbol(
                    (ProtocolText.parse("exact:v5:AAAAAAAAAAAAAAAAAAAAAA") as Refinement.Refined).value
                ),
            region = SourceRegionSelectionDocument.Body(SourceBodyKindDocument.CALLABLE),
            entities = SourceEntitySelectionDocument.None,
            text =
                SourceTextRequestDocument.Excerpt((SourceLineCountDocument.parse(lines) as Refinement.Refined).value),
        )
}
