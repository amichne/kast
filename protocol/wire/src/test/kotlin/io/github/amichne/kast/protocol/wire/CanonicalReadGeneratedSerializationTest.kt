package io.github.amichne.kast.protocol.wire

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.DiagnosticCheckQualification
import io.github.amichne.kast.protocol.contract.DiagnosticCheckResult
import io.github.amichne.kast.protocol.contract.DiagnosticDocument
import io.github.amichne.kast.protocol.contract.DiagnosticKnownCountDocument
import io.github.amichne.kast.protocol.contract.DiagnosticLimitationDocument
import io.github.amichne.kast.protocol.contract.DiagnosticLimitationReasonDocument
import io.github.amichne.kast.protocol.contract.DiagnosticLocationDocument
import io.github.amichne.kast.protocol.contract.DiagnosticRangeDocument
import io.github.amichne.kast.protocol.contract.DiagnosticSeverityDocument
import io.github.amichne.kast.protocol.contract.ProtocolOffset
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.contract.SymbolDiscoverLimitation
import io.github.amichne.kast.protocol.contract.SymbolDiscoverQualification
import kotlinx.serialization.json.JsonElement
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CanonicalReadGeneratedSerializationTest {
    @Test
    fun `generated qualification document preserves hyphenated names and closed variants`() {
        val codec = CanonicalReadSerializers.symbolDiscoverQualification
        val qualification =
            SymbolDiscoverQualification.from(
                    setOf(
                        SymbolDiscoverLimitation.WORK_LIMIT,
                        SymbolDiscoverLimitation.PROVIDER_FAILURE,
                    )
                )
                .refinedValue()
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
            )
            .forEach { malformed ->
                assertEquals(
                    WireDecoding.Rejected(WireFailure.InvalidPayload(WireValueRole.QUALIFICATION)),
                    codec.decode(json(malformed), WireValueRole.QUALIFICATION),
                )
            }
    }

    @Test
    fun `proof carrying qualifications round trip and malformed claims fail closed`() {
        val diagnostic =
            DiagnosticCheckQualification.create(
                    DiagnosticKnownCountDocument.parse(3).refinedValue(),
                    resultLimitReached = true,
                    analyzedFiles = listOf(text("src/A.kt")),
                    limitations =
                        listOf(
                            DiagnosticLimitationDocument(
                                text("src/B.kt"),
                                DiagnosticLimitationReasonDocument.INDEXING,
                            )
                        ),
                )
                .refinedValue()
        assertQualification(
            CanonicalReadSerializers.diagnosticCheckQualification,
            diagnostic,
            """{"knownDiagnosticCount":3,"resultLimitReached":true,"analyzedFiles":["src/A.kt"],"limitations":[{"file":"src/B.kt","reason":"indexing"}]}""",
            listOf(
                """{"knownDiagnosticCount":0,"resultLimitReached":false,"analyzedFiles":[],"limitations":[]}""",
                """{"knownDiagnosticCount":3,"resultLimitReached":true,"analyzedFiles":["src/A.kt"],"limitations":[{"file":"src/A.kt","reason":"indexing"}]}""",
            ),
        )
    }

    @Test
    fun `generated empty and list documents reject unknown missing and invalid content`() {
        val resultCodec = CanonicalReadSerializers.diagnosticCheckResult
        val result =
            DiagnosticCheckResult(
                BoundedProtocolList.create(
                        listOf(
                            DiagnosticDocument(
                                DiagnosticSeverityDocument.WARNING,
                                text("UNUSED"),
                                text("unused"),
                                DiagnosticLocationDocument(
                                    text("candidate:v2:diagnostic"),
                                    text("src/A.kt"),
                                    DiagnosticRangeDocument.create(offset(7), offset(7)).refinedValue(),
                                ),
                            )
                        )
                    )
                    .refinedValue()
            )
        val document =
            json(
                """{"diagnostics":[{"severity":"warning","code":"UNUSED","message":"unused","location":{"candidateSelector":"candidate:v2:diagnostic","file":"src/A.kt","range":{"startInclusive":7,"endExclusive":7}}}]}"""
            )
        assertEquals(WireValueEncoding.Encoded(document), resultCodec.encode(result, WireValueRole.RESULT))
        assertEquals(WireDecoding.Decoded(result), resultCodec.decode(document, WireValueRole.RESULT))
        listOf(
                """{}""",
                """{"diagnostics":[{"severity":"unknown","code":"UNUSED","message":"unused","location":{"file":"src/A.kt","range":{"startInclusive":7,"endExclusive":7}}}]}""",
                """{"diagnostics":[],"extra":true}""",
            )
            .forEach { malformed ->
                assertEquals(
                    WireDecoding.Rejected(WireFailure.InvalidPayload(WireValueRole.RESULT)),
                    resultCodec.decode(json(malformed), WireValueRole.RESULT),
                )
            }
    }

    private fun json(document: String): JsonElement = wireJson.parseToJsonElement(document)

    private fun <Qualification> assertQualification(
        codec: WireValueCodec<Qualification>,
        qualification: Qualification,
        encoded: String,
        malformed: List<String>,
    ) {
        val document = json(encoded)
        assertEquals(
            WireValueEncoding.Encoded(document),
            codec.encode(qualification, WireValueRole.QUALIFICATION),
        )
        assertEquals(
            WireDecoding.Decoded(qualification),
            codec.decode(document, WireValueRole.QUALIFICATION),
        )
        malformed.forEach { raw ->
            assertEquals(
                WireDecoding.Rejected(WireFailure.InvalidPayload(WireValueRole.QUALIFICATION)),
                codec.decode(json(raw), WireValueRole.QUALIFICATION),
            )
        }
    }

    private fun text(raw: String): ProtocolText = ProtocolText.parse(raw).refinedValue()

    private fun offset(raw: Int): ProtocolOffset = ProtocolOffset.parse(raw).refinedValue()

    private fun <Strong, Failure> Refinement<Strong, Failure>.refinedValue(): Strong =
        when (this) {
            is Refinement.Refined -> value
            is Refinement.Rejected -> error("Expected refined value, got $failure")
        }
}
