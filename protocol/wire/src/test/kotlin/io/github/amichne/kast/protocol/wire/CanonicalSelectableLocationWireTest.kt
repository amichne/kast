package io.github.amichne.kast.protocol.wire

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.DiagnosticCheckResult
import io.github.amichne.kast.protocol.contract.DiagnosticDocument
import io.github.amichne.kast.protocol.contract.DiagnosticLocationDocument
import io.github.amichne.kast.protocol.contract.DiagnosticRangeDocument
import io.github.amichne.kast.protocol.contract.DiagnosticSeverityDocument
import io.github.amichne.kast.protocol.contract.ProtocolOffset
import io.github.amichne.kast.protocol.contract.ProtocolText
import kotlinx.serialization.json.JsonElement
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CanonicalSelectableLocationWireTest {
    @Test
    fun `zero width diagnostic selector is required and round trips`() {
        val result =
            DiagnosticCheckResult(
                BoundedProtocolList.create(
                        listOf(
                            DiagnosticDocument(
                                DiagnosticSeverityDocument.WARNING,
                                text("INSERTION_POINT"),
                                text("insert here"),
                                DiagnosticLocationDocument(
                                    text("candidate:v2:diagnostic"),
                                    text("src/A.kt"),
                                    DiagnosticRangeDocument.create(offset(7), offset(7)).refined(),
                                ),
                            )
                        )
                    )
                    .refined()
            )
        val codec = CanonicalReadSerializers.diagnosticCheckResult
        val encoded = codec.encode(result, WireValueRole.RESULT)

        assertEquals(WireDecoding.Decoded(result), codec.decode(encoded.element(), WireValueRole.RESULT))
        assertEquals(
            WireDecoding.Rejected(WireFailure.InvalidPayload(WireValueRole.RESULT)),
            codec.decode(
                json(
                    """{"diagnostics":[{"severity":"warning","code":"INSERTION_POINT","message":"insert here","location":{"file":"src/A.kt","range":{"startInclusive":7,"endExclusive":7}}}]}"""
                ),
                WireValueRole.RESULT,
            ),
        )
    }

    private fun offset(raw: Int): ProtocolOffset = ProtocolOffset.parse(raw).refined()

    private fun text(raw: String): ProtocolText = ProtocolText.parse(raw).refined()

    private fun json(raw: String): JsonElement = wireJson.parseToJsonElement(raw)
}

private fun WireValueEncoding.element(): JsonElement =
    when (this) {
        is WireValueEncoding.Encoded -> value
        is WireValueEncoding.Rejected -> error("unexpected wire encoding rejection: $failure")
    }

private fun <Value, Failure> Refinement<Value, Failure>.refined(): Value =
    when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> error("unexpected refinement rejection: $failure")
    }
