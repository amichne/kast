package io.github.amichne.kast.protocol.contract

import io.github.amichne.kast.kernel.Refinement
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SourceReadSimpleRequestTest {
    private val json = Json { classDiscriminator = "type" }
    private val symbol = "exact:v5:${"A".repeat(21)}Q"

    @Test
    fun `one exact symbol admits source read defaults`() {
        val input = json.encodeToJsonElement(SourceReadSimpleRequest.serializer(), SourceReadSimpleRequest(exact()))
        assertEquals(setOf("symbol"), input.jsonObject.keys)
        val admitted = SourceRequestIngress.decode(input, json)
        assertTrue(admitted is Refinement.Refined)
        val request = (admitted as Refinement.Refined).value
        assertEquals(SourceReadAnchorDocument.Symbol(protocolText(symbol)), request.anchor)
        assertEquals(SourceRegionSelectionDocument.Anchor, request.region)
        assertEquals(SourceEntitySelectionDocument.None, request.entities)
        assertEquals(SourceTextRequestDocument.Complete, request.text)
        assertEquals(SourceReadFormatDocument.COMPACT, request.format)
        assertEquals(SourceReadPageDocument.First, request.page)
    }

    @Test
    fun `simple exact request rejects a second anchor`() {
        val input =
            json.encodeToJsonElement(
                MixedSourceInput.serializer(),
                MixedSourceInput(symbol, SourceReadAnchorDocument.Symbol(protocolText(symbol))),
            )
        assertTrue(SourceRequestIngress.decode(input, json) is Refinement.Rejected)
    }

    @Test
    fun `short request rejects unchecked legacy selector`() {
        val unchecked = "exact:v2:A:${"0".repeat(64)}"
        assertEquals(
            Refinement.Rejected(ExactSymbolSelectorFailure.MALFORMED),
            ExactSymbolSelector.parse(unchecked),
        )
        val input = json.encodeToJsonElement(UncheckedSourceInput.serializer(), UncheckedSourceInput(unchecked))
        assertTrue(SourceRequestIngress.decode(input, json) is Refinement.Rejected)
    }

    @Test
    fun `simple request rejects an unknown text discriminator as finite data`() {
        val input =
            json.encodeToJsonElement(
                UnknownTextRequest.serializer(),
                UnknownTextRequest(symbol, UnknownTextMode()),
            )
        assertEquals(
            Refinement.Rejected(
                SourceReadFailureDetail.RequestRejected(
                    SourceRequestField(SourceRequestPath.DOCUMENT),
                    SourceRequestRule.INVALID_JSON,
                )
            ),
            SourceRequestIngress.decode(input, json),
        )
    }

    @Test
    fun `documented overrides normalize through the typed source request`() {
        val input =
            json.encodeToJsonElement(
                SourceReadSimpleRequest.serializer(),
                SourceReadSimpleRequest(
                    symbol = exact(),
                    region = SimpleSourceRegion.BODY,
                    text = SimpleSourceText.Window(maximumBytes = byteLimit(12_000)),
                    entities = SimpleSourceEntities.Declarations(entityLimit(50)),
                    format = SourceReadFormatDocument.EXPANDED,
                ),
            )
        val admitted = SourceRequestIngress.decode(input, json)
        assertTrue(admitted is Refinement.Refined)
        val request = (admitted as Refinement.Refined).value
        assertEquals(SourceRegionSelectionDocument.Body(SourceBodyKindDocument.CALLABLE), request.region)
        assertEquals(SourceTextRequestDocument.Window(lineCount(20), lineCount(20)), request.text)
        assertEquals(12_000L, request.textByteLimit.value)
        assertEquals(50, request.entityLimit.value)
        assertEquals(SourceReadFormatDocument.EXPANDED, request.format)
    }

    @Test
    fun `all published short request discriminator values admit`() {
        val texts =
            listOf(
                SimpleSourceText.Complete,
                SimpleSourceText.None,
                SimpleSourceText.Window(maximumBytes = byteLimit(12_000)),
            )
        val entitySelections =
            listOf(
                SimpleSourceEntities.None,
                SimpleSourceEntities.Declarations(entityLimit(50)),
            )
        for (region in SimpleSourceRegion.entries) {
            for (text in texts) {
                for (entities in entitySelections) {
                    val input =
                        json.encodeToJsonElement(
                            SourceReadSimpleRequest.serializer(),
                            SourceReadSimpleRequest(exact(), region, text, entities),
                        )
                    assertTrue(
                        SourceRequestIngress.decode(input, json) is Refinement.Refined,
                        "$region $text $entities",
                    )
                }
            }
        }
    }

    private fun protocolText(raw: String): ProtocolText = (ProtocolText.parse(raw) as Refinement.Refined).value

    private fun lineCount(raw: Int): SourceLineCountDocument =
        (SourceLineCountDocument.parse(raw) as Refinement.Refined).value

    private fun exact(): ExactSymbolSelector = (ExactSymbolSelector.parse(symbol) as Refinement.Refined).value

    private fun byteLimit(raw: Long): SourceTextByteLimitDocument =
        (SourceTextByteLimitDocument.parse(raw) as Refinement.Refined).value

    private fun entityLimit(raw: Int): SourceEntityLimitDocument =
        (SourceEntityLimitDocument.parse(raw) as Refinement.Refined).value
}

@Serializable private data class MixedSourceInput(val symbol: String, val anchor: SourceReadAnchorDocument)

@Serializable private data class UncheckedSourceInput(val symbol: String)

@Serializable private data class UnknownTextRequest(val symbol: String, val text: UnknownTextMode)

@Serializable private data class UnknownTextMode(val mode: String = "invalid")
