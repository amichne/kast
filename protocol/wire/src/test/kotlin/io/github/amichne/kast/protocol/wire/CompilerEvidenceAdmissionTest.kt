package io.github.amichne.kast.protocol.wire

import io.github.amichne.kast.kernel.EvidenceEnvelope
import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.CompilerSignatureDocument
import io.github.amichne.kast.protocol.contract.CompilerSymbolEvidenceDocument
import io.github.amichne.kast.protocol.contract.CompilerReceiverDocument
import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.ProtocolOffset
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.contract.SourceRangeDocument
import io.github.amichne.kast.protocol.contract.SymbolInspectResult
import io.github.amichne.kast.protocol.contract.SymbolDocument
import io.github.amichne.kast.protocol.contract.SymbolKindDocument
import io.github.amichne.kast.protocol.contract.SymbolQualifiedIdentityDocument
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CompilerEvidenceAdmissionTest {
    @Test
    fun `wire retains extension property receiver proof`() {
        val qualifiedIdentity = text("sample.tag")
        val signature = CompilerSignatureDocument.Property(
            qualifiedIdentity = qualifiedIdentity,
            receiver = CompilerReceiverDocument.Present(text("kotlin.String")),
            contextReceivers = BoundedProtocolList.create(listOf(text("sample.Context"))).refined(),
            returnType = text("kotlin.Int"),
        )
        val evidence = CompilerSymbolEvidenceDocument.fromSignature(signature).refined()
        val symbol = SymbolDocument.create(
            selector = text("exact:v2:property"),
            kind = SymbolKindDocument.PROPERTY,
            name = text("tag"),
            qualifiedIdentity = SymbolQualifiedIdentityDocument.Available(qualifiedIdentity),
            file = text("src/Extensions.kt"),
            range = SourceRangeDocument.create(offset(0), offset(10)).refined(),
            compilerEvidence = evidence,
        ).refined()
        val outcome = OperationOutcome.Complete(
            EvidenceEnvelope(
                CanonicalOperationWireBindings.symbolInspect.operation.id,
                EvidenceGeneration.parse(3).refined(),
                SymbolInspectResult(symbol),
            ),
        )

        val encoded = CanonicalOperationWireBindings.symbolInspect.encodeOutcome(outcome)
            .encodedDocument()

        assertEquals(
            WireDecoding.Decoded(outcome),
            CanonicalOperationWireBindings.symbolInspect.decodeOutcome(encoded),
        )
        assertTrue(encoded.contains("\"receiver\":{\"type\":\"present\",\"compilerType\":\"kotlin.String\"}"), encoded)
        assertTrue(encoded.contains("\"contextReceivers\":[\"sample.Context\"]"), encoded)
    }

    @Test
    fun `wire rejects compiler evidence that contradicts identity kind or qualified identity`() {
        val qualifiedIdentity = text("sample.Controller")
        val signature = CompilerSignatureDocument.ClassLike(qualifiedIdentity)
        val evidence = CompilerSymbolEvidenceDocument.fromSignature(signature).refined()
        val outcome = OperationOutcome.Complete(
            EvidenceEnvelope(
                CanonicalOperationWireBindings.symbolInspect.operation.id,
                EvidenceGeneration.parse(3).refined(),
                SymbolInspectResult(
                    SymbolDocument.create(
                        selector = text("exact:v2:payload"),
                        kind = SymbolKindDocument.CLASSLIKE,
                        name = text("Controller"),
                        qualifiedIdentity = SymbolQualifiedIdentityDocument.Available(
                            qualifiedIdentity,
                        ),
                        file = text("src/Controller.kt"),
                        range = SourceRangeDocument.create(offset(0), offset(10)).refined(),
                        compilerEvidence = evidence,
                    ).refined(),
                ),
            ),
        )
        val encoded = CanonicalOperationWireBindings.symbolInspect.encodeOutcome(outcome)
            .encodedDocument()

        val malformed = listOf(
            encoded.replace(evidence.identity.value, "canonical-signature-sha256-v1|manufactured"),
            encoded.replaceFirst("\"kind\":\"classlike\"", "\"kind\":\"property\""),
            encoded.replaceFirst(
                "\"qualifiedIdentity\":\"sample.Controller\"",
                "\"qualifiedIdentity\":null",
            ),
        )

        malformed.forEach { document ->
            assertEquals(
                WireDecoding.Rejected(WireFailure.InvalidPayload(WireValueRole.RESULT)),
                CanonicalOperationWireBindings.symbolInspect.decodeOutcome(document),
            )
        }
    }

    private fun text(raw: String): ProtocolText = ProtocolText.parse(raw).refined()

    private fun offset(raw: Int): ProtocolOffset = ProtocolOffset.parse(raw).refined()

    private fun WireEncoding.encodedDocument(): String = when (this) {
        is WireEncoding.Encoded -> document
        is WireEncoding.Rejected -> error(failure.toString())
    }

    private fun <Value, Failure> Refinement<Value, Failure>.refined(): Value = when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> error(failure.toString())
    }
}
