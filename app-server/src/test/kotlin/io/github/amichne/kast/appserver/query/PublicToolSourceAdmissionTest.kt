package io.github.amichne.kast.appserver.query

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.registry.PublicToolIdentity
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PublicToolSourceAdmissionTest {
    @Test
    fun `every source region lowers to its existing canonical selection`() {
        val candidate = text("candidate:v5:AAAAAAAAAAAAAAAAAAAAAA")
        val expected =
            listOf(
                io.github.amichne.kast.protocol.contract.SourceRegionSelectionDocument.Anchor,
                io.github.amichne.kast.protocol.contract.SourceRegionSelectionDocument.Body(
                    io.github.amichne.kast.protocol.contract.SourceBodyKindDocument.CALLABLE
                ),
                io.github.amichne.kast.protocol.contract.SourceRegionSelectionDocument.Body(
                    io.github.amichne.kast.protocol.contract.SourceBodyKindDocument.CLASS
                ),
                io.github.amichne.kast.protocol.contract.SourceRegionSelectionDocument.File,
                io.github.amichne.kast.protocol.contract.SourceRegionSelectionDocument.Enclosing(
                    io.github.amichne.kast.protocol.contract.SourceEnclosingRegionKindDocument.DECLARATION
                ),
                io.github.amichne.kast.protocol.contract.SourceRegionSelectionDocument.Enclosing(
                    io.github.amichne.kast.protocol.contract.SourceEnclosingRegionKindDocument.CALLABLE_BODY
                ),
                io.github.amichne.kast.protocol.contract.SourceRegionSelectionDocument.Enclosing(
                    io.github.amichne.kast.protocol.contract.SourceEnclosingRegionKindDocument.CLASS_BODY
                ),
            )
        assertEquals(expected.size, PublicToolRegion.entries.size)
        for ((index, region) in PublicToolRegion.entries.withIndex()) {
            val raw = Json.encodeToJsonElement(PublicToolQuerySymbols(PublicToolReadSourceAction(candidate, region)))
            val admitted = PublicToolContract.admit(PublicToolIdentity.QUERY_SYMBOLS, raw) as Refinement.Refined
            val request = (admitted.value.canonical as PublicToolCanonical.Source).request
            assertEquals(expected[index], request.region)
        }
    }

    @Test
    fun `source selector reentry preserves the token and nullable presentation defaults`() {
        // This fixture proves syntax admission only. Source admission still needs an issued snapshot.
        val selector = text("source-selector-v2:eA:2d711642b726b04401627ca9fbac32f5c8530fb1903cc4db02258717921a4881")
        for (presentation in
            listOf(null, PublicToolSourceTextNone, PublicToolSourceTextComplete, PublicToolSourceTextWindow())) {
            val raw = Json {
                encodeDefaults = true
            }
                .encodeToJsonElement(PublicToolQuerySymbols(PublicToolReadSourceAction(selector, text = presentation)))
            val admitted = PublicToolContract.admit(PublicToolIdentity.QUERY_SYMBOLS, raw) as Refinement.Refined
            val request = (admitted.value.canonical as PublicToolCanonical.Source).request
            assertEquals(
                io.github.amichne.kast.protocol.contract.SourceReadAnchorDocument.Source(selector),
                request.anchor,
            )
            assertEquals(io.github.amichne.kast.protocol.contract.SourceRegionSelectionDocument.Anchor, request.region)
            when (presentation) {
                null,
                PublicToolSourceTextComplete ->
                    assertEquals(
                        io.github.amichne.kast.protocol.contract.SourceTextRequestDocument.Complete,
                        request.text,
                    )
                PublicToolSourceTextNone ->
                    assertEquals(io.github.amichne.kast.protocol.contract.SourceTextRequestDocument.None, request.text)
                is PublicToolSourceTextWindow -> {
                    val window =
                        request.text as io.github.amichne.kast.protocol.contract.SourceTextRequestDocument.Window
                    assertEquals(5, window.beforeLines.value)
                    assertEquals(5, window.afterLines.value)
                }
            }
        }
    }

    @Test
    fun `unknown regions coordinates and excess line counts reject before source admission`() {
        val invalid =
            listOf(
                "\"region\":\"NEAREST_GUESS\"",
                "\"startOffset\":20",
                "\"text\":{\"type\":\"WINDOW\",\"beforeLines\":-1}",
                "\"text\":{\"type\":\"WINDOW\",\"afterLines\":1001}",
                "\"text\":{\"type\":\"COMPLETE\",\"afterLines\":2}",
            )
        for (fields in invalid) {
            val raw =
                Json.parseToJsonElement(
                    """
                    {"request":{
                      "type":"READ_SOURCE",
                      "candidateRef":"candidate:v5:AAAAAAAAAAAAAAAAAAAAAA",
                      $fields
                    }}
                    """
                        .trimIndent()
                )
            assertTrue(PublicToolContract.admit(PublicToolIdentity.QUERY_SYMBOLS, raw) is Refinement.Rejected, fields)
        }
    }

    @Test
    fun `read source selects an enclosing body and independent text window without coordinates`() {
        val input =
            FocusedReadInput(
                FocusedReadAction(
                    candidateRef = "candidate:v5:AAAAAAAAAAAAAAAAAAAAAA",
                    region = "ENCLOSING_CALLABLE_BODY",
                    text = FocusedTextWindow(beforeLines = 2, afterLines = 3),
                )
            )
        val raw = Json { encodeDefaults = true }.encodeToJsonElement(input)
        val admitted = PublicToolContract.admit(PublicToolIdentity.QUERY_SYMBOLS, raw)
        assertTrue(admitted is Refinement.Refined, admitted.toString())
        val request = ((admitted as Refinement.Refined).value.canonical as PublicToolCanonical.Source).request
        assertEquals(
            io.github.amichne.kast.protocol.contract.SourceRegionSelectionDocument.Enclosing(
                io.github.amichne.kast.protocol.contract.SourceEnclosingRegionKindDocument.CALLABLE_BODY
            ),
            request.region,
        )
        val window = request.text as io.github.amichne.kast.protocol.contract.SourceTextRequestDocument.Window
        assertEquals(2, window.beforeLines.value)
        assertEquals(3, window.afterLines.value)
        assertEquals(io.github.amichne.kast.protocol.contract.SourceEntitySelectionDocument.None, request.entities)
    }

    @Test
    fun `read source retains exact candidate and delegates source anchor admission`() {
        val candidate = (ProtocolText.parse("candidate:v5:AAAAAAAAAAAAAAAAAAAAAA") as Refinement.Refined).value
        val input = PublicToolQuerySymbols(PublicToolReadSourceAction(candidate))
        val raw = Json.encodeToJsonElement(PublicToolQuerySymbols.serializer(), input)
        val admitted = (PublicToolContract.admit(PublicToolIdentity.QUERY_SYMBOLS, raw) as Refinement.Refined).value
        val source = (admitted.canonical as PublicToolCanonical.Source).request
        assertEquals(
            io.github.amichne.kast.protocol.contract.SourceReadAnchorDocument.Candidate(candidate),
            source.anchor,
        )
        assertEquals(io.github.amichne.kast.protocol.contract.SourceRegionSelectionDocument.Anchor, source.region)
        assertEquals(io.github.amichne.kast.protocol.contract.SourceEntitySelectionDocument.None, source.entities)
        assertEquals(io.github.amichne.kast.protocol.contract.SourceTextRequestDocument.Complete, source.text)
        val badDigest = (ProtocolText.parse("candidate:v3:eA:" + "0".repeat(64)) as Refinement.Refined).value
        val malformed =
            Json.encodeToJsonElement(
                PublicToolQuerySymbols.serializer(),
                PublicToolQuerySymbols(PublicToolReadSourceAction(badDigest)),
            )
        assertTrue(
            PublicToolContract.schema(PublicToolIdentity.QUERY_SYMBOLS).admit(malformed)
                is io.github.amichne.kast.kernel.Validation.Validated
        )
        assertEquals(
            Refinement.Rejected(
                PublicToolInputFailure.SourceAnchor(
                    io.github.amichne.kast.protocol.contract.SourceReadAnchorDocumentFailure.PAYLOAD_DIGEST_MISMATCH
                )
            ),
            PublicToolContract.admit(PublicToolIdentity.QUERY_SYMBOLS, malformed),
        )
    }

    private fun text(raw: String): ProtocolText = (ProtocolText.parse(raw) as Refinement.Refined).value
}

@Serializable private data class FocusedReadInput(val request: FocusedReadAction)

@Serializable
private data class FocusedReadAction(
    val candidateRef: String,
    val region: String,
    val text: FocusedTextWindow,
    val type: String = "READ_SOURCE",
)

@Serializable
private data class FocusedTextWindow(
    val beforeLines: Int,
    val afterLines: Int,
    val type: String = "WINDOW",
)
