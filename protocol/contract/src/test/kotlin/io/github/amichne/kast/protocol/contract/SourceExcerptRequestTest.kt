package io.github.amichne.kast.protocol.contract

import io.github.amichne.kast.kernel.Refinement
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SourceExcerptRequestTest {
    @Test
    fun `canonical excerpt encodes its discriminator and positive count independently of its region`() {
        val request = request(7)
        val encoded = Json.encodeToJsonElement(SourceReadRequest.serializer(), request)
        val text = encoded.jsonObject.getValue("text").jsonObject
        assertEquals(setOf("type", "maxLines"), text.keys)
        assertEquals("excerpt", text.getValue("type").jsonPrimitive.content)
        assertEquals("7", text.getValue("maxLines").jsonPrimitive.content)
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
        val valid = request(7)
        val json = Json {
            encodeDefaults = true
            explicitNulls = false
        }
        for (lines in listOf(null, 0, 1001)) {
            val raw =
                json.encodeToJsonElement(
                    UncheckedSourceExcerpt.serializer(),
                    UncheckedSourceExcerpt(
                        anchor = valid.anchor,
                        region = valid.region,
                        entities = valid.entities,
                        text = UncheckedExcerptText(lines),
                    ),
                )
            assertTrue(SourceRequestIngress.decode(raw, Json) is Refinement.Rejected, "maxLines=$lines")
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

/** The negative fixture deliberately bypasses the positive line-count contract. */
@Serializable
private data class UncheckedSourceExcerpt(
    val anchor: SourceReadAnchorDocument,
    val region: SourceRegionSelectionDocument,
    val entities: SourceEntitySelectionDocument,
    val text: UncheckedExcerptText,
)

@Serializable private data class UncheckedExcerptText(val maxLines: Int?, val type: String = "excerpt")
