package io.github.amichne.kast.protocol.wire

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.CompilerSignatureDocument
import io.github.amichne.kast.protocol.contract.CompilerSymbolEvidenceDocument
import io.github.amichne.kast.protocol.contract.DiagnosticCheckResult
import io.github.amichne.kast.protocol.contract.DiagnosticDocument
import io.github.amichne.kast.protocol.contract.DiagnosticLocationDocument
import io.github.amichne.kast.protocol.contract.DiagnosticRangeDocument
import io.github.amichne.kast.protocol.contract.DiagnosticSeverityDocument
import io.github.amichne.kast.protocol.contract.ProtocolOffset
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.contract.RelationFactCoverageDocument
import io.github.amichne.kast.protocol.contract.RelationFactDocument
import io.github.amichne.kast.protocol.contract.RelationKindDocument
import io.github.amichne.kast.protocol.contract.RelationOccurrenceDocument
import io.github.amichne.kast.protocol.contract.RelationProvenanceDocument
import io.github.amichne.kast.protocol.contract.RelationReadResult
import io.github.amichne.kast.protocol.contract.SourceRangeDocument
import io.github.amichne.kast.protocol.contract.SymbolDocument
import io.github.amichne.kast.protocol.contract.SymbolKindDocument
import io.github.amichne.kast.protocol.contract.SymbolQualifiedIdentityDocument
import kotlinx.serialization.json.JsonElement
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CanonicalSelectableLocationWireTest {
    @Test
    fun `relation occurrence selector is required and round trips`() {
        val symbol = symbol()
        val result = RelationReadResult(
            BoundedProtocolList.create(
                listOf(
                    RelationFactDocument(
                        RelationKindDocument.REFERENCES,
                        symbol,
                        symbol,
                        RelationOccurrenceDocument(
                            text("candidate:v2:occurrence"),
                            text("src/A.kt"),
                            sourceRange(4, 8),
                        ),
                        RelationProvenanceDocument.K2_AUTHORED_SOURCE,
                        RelationFactCoverageDocument.EXACT_COMPILER_CONFIRMED,
                    ),
                ),
            ).refined(),
        )
        val codec = CanonicalReadSerializers.relationReadResult
        val encoded = codec.encode(result, WireValueRole.RESULT)

        assertEquals(WireDecoding.Decoded(result), codec.decode(encoded.element(), WireValueRole.RESULT))
        assertEquals(
            WireDecoding.Rejected(WireFailure.InvalidPayload(WireValueRole.RESULT)),
            codec.decode(
                json(
                    """{"relations":[{"meaning":"references","source":${symbolJson()},"target":${symbolJson()},"occurrence":{"file":"src/A.kt","range":{"startInclusive":4,"endExclusive":8}},"provenance":"k2-authored-source","coverage":"exact-compiler-confirmed"}]}""",
                ),
                WireValueRole.RESULT,
            ),
        )
    }

    @Test
    fun `zero width diagnostic selector is required and round trips`() {
        val result = DiagnosticCheckResult(
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
                    ),
                ),
            ).refined(),
        )
        val codec = CanonicalReadSerializers.diagnosticCheckResult
        val encoded = codec.encode(result, WireValueRole.RESULT)

        assertEquals(WireDecoding.Decoded(result), codec.decode(encoded.element(), WireValueRole.RESULT))
        assertEquals(
            WireDecoding.Rejected(WireFailure.InvalidPayload(WireValueRole.RESULT)),
            codec.decode(
                json(
                    """{"diagnostics":[{"severity":"warning","code":"INSERTION_POINT","message":"insert here","location":{"file":"src/A.kt","range":{"startInclusive":7,"endExclusive":7}}}]}""",
                ),
                WireValueRole.RESULT,
            ),
        )
    }

    private fun symbol(): SymbolDocument {
        val signature = CompilerSignatureDocument.ClassLike(text("sample.A"))
        return SymbolDocument.create(
            text("exact:v1:A"),
            SymbolKindDocument.CLASSLIKE,
            text("A"),
            SymbolQualifiedIdentityDocument.Available(text("sample.A")),
            text("src/A.kt"),
            sourceRange(0, 10),
            CompilerSymbolEvidenceDocument.fromSignature(signature).refined(),
        ).refined()
    }

    private fun symbolJson(): String =
        """{"selector":"exact:v1:A","kind":"classlike","name":"A","qualifiedIdentity":"sample.A","file":"src/A.kt","range":{"startInclusive":0,"endExclusive":10},"compilerEvidence":{"identity":"${symbol().compilerEvidence.identity.value}","signature":{"type":"class-like","qualifiedIdentity":"sample.A"}}}"""

    private fun sourceRange(start: Int, end: Int): SourceRangeDocument =
        SourceRangeDocument.create(offset(start), offset(end)).refined()

    private fun offset(raw: Int): ProtocolOffset = ProtocolOffset.parse(raw).refined()

    private fun text(raw: String): ProtocolText = ProtocolText.parse(raw).refined()

    private fun json(raw: String): JsonElement = wireJson.parseToJsonElement(raw)
}

private fun WireValueEncoding.element(): JsonElement = when (this) {
    is WireValueEncoding.Encoded -> value
    is WireValueEncoding.Rejected -> error("unexpected wire encoding rejection: $failure")
}

private fun <Value, Failure> Refinement<Value, Failure>.refined(): Value = when (this) {
    is Refinement.Refined -> value
    is Refinement.Rejected -> error("unexpected refinement rejection: $failure")
}
