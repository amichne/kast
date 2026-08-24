package io.github.amichne.kast.protocol.wire

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.DiagnosticCheckResult
import io.github.amichne.kast.protocol.contract.ProtocolCount
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.contract.RelationKindDocument
import io.github.amichne.kast.protocol.contract.RelationReadRequest
import io.github.amichne.kast.protocol.contract.SymbolDiscoverLimitation
import io.github.amichne.kast.protocol.contract.SymbolDiscoverQualification
import io.github.amichne.kast.protocol.contract.WorkspaceInspectRequest
import kotlinx.serialization.json.JsonElement
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CanonicalReadGeneratedSerializationTest {
    @Test
    fun `generated request document preserves shape and rejects malformed fields`() {
        val codec = CanonicalReadSerializers.relationReadRequest
        val request = RelationReadRequest(
            exactSelector = text("exact:Target"),
            relation = RelationKindDocument.CALLERS,
            limit = count(25),
        )
        val document = json("""{"exactSelector":"exact:Target","relation":"callers","limit":25}""")

        assertEquals(WireValueEncoding.Encoded(document), codec.encode(request, WireValueRole.REQUEST))
        assertEquals(WireDecoding.Decoded(request), codec.decode(document, WireValueRole.REQUEST))
        listOf(
            """{"exactSelector":"exact:Target","relation":"callers"}""",
            """{"exactSelector":"exact:Target","relation":"callers","limit":25,"extra":true}""",
            """{"exactSelector":"exact:Target","relation":"unknown","limit":25}""",
            """{"exactSelector":"exact:Target","relation":"callers","limit":0}""",
        ).forEach { malformed ->
            assertEquals(
                WireDecoding.Rejected(WireFailure.InvalidPayload(WireValueRole.REQUEST)),
                codec.decode(json(malformed), WireValueRole.REQUEST),
            )
        }
    }

    @Test
    fun `generated qualification document preserves hyphenated names and closed variants`() {
        val codec = CanonicalReadSerializers.symbolDiscoverQualification
        val qualification = SymbolDiscoverQualification.from(
            setOf(
                SymbolDiscoverLimitation.WORK_LIMIT,
                SymbolDiscoverLimitation.PROVIDER_FAILURE,
            ),
        ).refinedValue()
        val document = json("""{"limitations":["work-limit","provider-failure"]}""")

        assertEquals(
            WireValueEncoding.Encoded(document),
            codec.encode(qualification, WireValueRole.QUALIFICATION),
        )
        assertEquals(
            WireDecoding.Decoded(qualification),
            codec.decode(document, WireValueRole.QUALIFICATION),
        )
        listOf(
            """{}""",
            """{"limitations":[]}""",
            """{"limitations":["unknown"]}""",
            """{"limitations":["work-limit"],"extra":true}""",
        ).forEach { malformed ->
            assertEquals(
                WireDecoding.Rejected(WireFailure.InvalidPayload(WireValueRole.QUALIFICATION)),
                codec.decode(json(malformed), WireValueRole.QUALIFICATION),
            )
        }
    }

    @Test
    fun `generated empty and list documents reject unknown missing and invalid content`() {
        val requestCodec = CanonicalReadSerializers.workspaceInspectRequest
        assertEquals(
            WireValueEncoding.Encoded(json("{}")),
            requestCodec.encode(WorkspaceInspectRequest, WireValueRole.REQUEST),
        )
        assertEquals(
            WireDecoding.Rejected(WireFailure.InvalidPayload(WireValueRole.REQUEST)),
            requestCodec.decode(json("""{"extra":true}"""), WireValueRole.REQUEST),
        )

        val resultCodec = CanonicalReadSerializers.diagnosticCheckResult
        val result = DiagnosticCheckResult(texts("warning:unused"))
        val document = json("""{"diagnostics":["warning:unused"]}""")
        assertEquals(WireValueEncoding.Encoded(document), resultCodec.encode(result, WireValueRole.RESULT))
        assertEquals(WireDecoding.Decoded(result), resultCodec.decode(document, WireValueRole.RESULT))
        listOf(
            """{}""",
            """{"diagnostics":[""]}""",
            """{"diagnostics":["warning:unused"],"extra":true}""",
        ).forEach { malformed ->
            assertEquals(
                WireDecoding.Rejected(WireFailure.InvalidPayload(WireValueRole.RESULT)),
                resultCodec.decode(json(malformed), WireValueRole.RESULT),
            )
        }
    }

    private fun json(document: String): JsonElement = wireJson.parseToJsonElement(document)

    private fun text(raw: String): ProtocolText = ProtocolText.parse(raw).refinedValue()

    private fun count(raw: Int): ProtocolCount = ProtocolCount.parse(raw).refinedValue()

    private fun texts(vararg raw: String): BoundedProtocolList<ProtocolText> =
        BoundedProtocolList.create(raw.map(::text)).refinedValue()

    private fun <Strong, Failure> Refinement<Strong, Failure>.refinedValue(): Strong = when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> error("Expected refined value, got $failure")
    }
}
