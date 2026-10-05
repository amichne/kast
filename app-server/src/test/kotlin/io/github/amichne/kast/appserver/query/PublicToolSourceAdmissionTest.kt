package io.github.amichne.kast.appserver.query

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.registry.PublicToolIdentity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PublicToolSourceAdmissionTest {
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
}
