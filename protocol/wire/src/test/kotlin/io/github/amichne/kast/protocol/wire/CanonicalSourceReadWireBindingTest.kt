package io.github.amichne.kast.protocol.wire

import io.github.amichne.kast.protocol.contract.ProtocolSourceText
import io.github.amichne.kast.protocol.contract.SourceLineRangeDocument

import io.github.amichne.kast.kernel.EvidenceEnvelope
import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.ProtocolOffset
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.contract.SourceBodyKindDocument
import io.github.amichne.kast.protocol.contract.SourceContainmentDocument
import io.github.amichne.kast.protocol.contract.SourceCoordinateUnitDocument
import io.github.amichne.kast.protocol.contract.SourceDeclarationKindDocument
import io.github.amichne.kast.protocol.contract.SourceDeclarationVisibilityDocument
import io.github.amichne.kast.protocol.contract.SourceEntityCountDocument
import io.github.amichne.kast.protocol.contract.SourceEntityDocument
import io.github.amichne.kast.protocol.contract.SourceEntityFilterDocument
import io.github.amichne.kast.protocol.contract.SourceEntityLimitDocument
import io.github.amichne.kast.protocol.contract.SourceEntitySelectionDocument
import io.github.amichne.kast.protocol.contract.SourceLengthDocument
import io.github.amichne.kast.protocol.contract.SourceReadAnchorDocument
import io.github.amichne.kast.protocol.contract.SourceReadContinuationStateDocument
import io.github.amichne.kast.protocol.contract.SourceReadLimitationDocument
import io.github.amichne.kast.protocol.contract.SourceReadPageDocument
import io.github.amichne.kast.protocol.contract.SourceReadQualification
import io.github.amichne.kast.protocol.contract.SourceReadRejection
import io.github.amichne.kast.protocol.contract.SourceReadRequest
import io.github.amichne.kast.protocol.contract.SourceReadResult
import io.github.amichne.kast.protocol.contract.SourceRegionDocument
import io.github.amichne.kast.protocol.contract.SourceRegionKindDocument
import io.github.amichne.kast.protocol.contract.SourceRegionSelectionDocument
import io.github.amichne.kast.protocol.contract.SourceSelectionDocument
import io.github.amichne.kast.protocol.contract.SourceSelectionRangeDocument
import io.github.amichne.kast.protocol.contract.SourceSnapshotDocument
import io.github.amichne.kast.protocol.contract.SourceTextByteLimitDocument
import io.github.amichne.kast.protocol.contract.SourceTextProjectionDocument
import io.github.amichne.kast.protocol.contract.SourceTextRequestDocument
import io.github.amichne.kast.protocol.contract.SourceVisibilitySelectionDocument
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CanonicalSourceReadWireBindingTest {
    @Test
    fun `returned source line evidence round trips and rejects impossible line spans`() {
        val binding = CanonicalOperationWireBindings.sourceRead
        val result = sourceReadResult()
        val returned = result.copy(text = SourceTextProjectionDocument.Returned(
            result.region.selection,
            ProtocolSourceText.parse("x".repeat(80)).refinedValue(),
            SourceLineRangeDocument.parse(2, 2).refinedValue(),
        ))
        val outcome = OperationOutcome.Complete(EvidenceEnvelope(
            binding.operation.id, EvidenceGeneration.parse(17).refinedValue(), returned,
        ))
        val document = binding.encodeOutcome(outcome).encodedDocument()
        assertEquals(WireDecoding.Decoded(outcome), binding.decodeOutcome(document))
        for (invalid in listOf(
            document.replace("\"lines\":{\"startInclusive\":2,\"endInclusive\":2}",
                "\"lines\":{\"startInclusive\":0,\"endInclusive\":2}"),
            document.replace("\"lines\":{\"startInclusive\":2,\"endInclusive\":2}",
                "\"lines\":{\"startInclusive\":2,\"endInclusive\":3}"),
        )) {
            assertEquals(WireDecoding.Rejected(WireFailure.InvalidPayload(WireValueRole.RESULT)),
                binding.decodeOutcome(invalid))
        }
    }

    @Test
    fun `canonical source read request and all outcome families round trip`() {
        val binding = CanonicalOperationWireBindings.sourceRead
        val request = sourceReadRequest()
        val result = sourceReadResult()
        val qualification = SourceReadQualification.create(
            SourceEntityCountDocument.parse(0).refinedValue(),
            listOf(SourceReadLimitationDocument.TEXT_BYTE_LIMIT_REACHED),
            SourceReadContinuationStateDocument.Unavailable,
        ).refinedValue()

        val encodedRequest = binding.encodeRequest(request).encodedDocument()
        assertEquals(
            WireDecoding.Decoded(request),
            binding.decodeRequest(WireRequestEnvelope.admit(encodedRequest).admittedRequest()),
        )

        val evidence = EvidenceEnvelope(
            binding.operation.id,
            EvidenceGeneration.parse(17).refinedValue(),
            result,
        )
        listOf(
            OperationOutcome.Complete(evidence),
            OperationOutcome.Qualified(evidence, qualification),
            OperationOutcome.Rejected(SourceReadRejection.SOURCE_SELECTOR_STALE),
        ).forEach { outcome ->
            assertEquals(
                WireDecoding.Decoded(outcome),
                binding.decodeOutcome(binding.encodeOutcome(outcome).encodedDocument()),
            )
        }
    }

    @Test
    fun `every source read rejection round trips`() {
        val binding = CanonicalOperationWireBindings.sourceRead
        SourceReadRejection.entries.forEach { rejection ->
            val outcome = OperationOutcome.Rejected(rejection)
            assertEquals(
                WireDecoding.Decoded(outcome),
                binding.decodeOutcome(binding.encodeOutcome(outcome).encodedDocument()),
            )
        }
    }

    @Test
    fun `strict request decoder rejects unknown unions additional fields and invalid bounds`() {
        val binding = CanonicalOperationWireBindings.sourceRead
        val encoded = binding.encodeRequest(sourceReadRequest()).encodedDocument()
        val invalidDocuments = listOf(
            encoded.replace("\"type\":\"symbol\"", "\"type\":\"unknown\""),
            encoded.replace(
                "\"selector\":\"exact:Target\"",
                "\"selector\":\"exact:Target\",\"unknown\":true",
            ),
            encoded.replace("\"entityLimit\":250", "\"entityLimit\":0"),
        )

        invalidDocuments.forEach { invalid ->
            assertEquals(
                WireDecoding.Rejected(WireFailure.InvalidPayload(WireValueRole.REQUEST)),
                binding.decodeRequest(WireRequestEnvelope.admit(invalid).admittedRequest()),
            )
        }
    }

    private fun sourceReadRequest(): SourceReadRequest = SourceReadRequest(
        anchor = SourceReadAnchorDocument.Symbol(text("exact:Target")),
        region = SourceRegionSelectionDocument.Body(SourceBodyKindDocument.CLASS),
        entities = SourceEntitySelectionDocument.Matching(
            SourceContainmentDocument.DIRECT,
            listOf(
                SourceEntityFilterDocument.Declarations(
                    listOf(SourceDeclarationKindDocument.FUNCTION),
                    SourceVisibilitySelectionDocument.Exact(
                        listOf(SourceDeclarationVisibilityDocument.PUBLIC),
                    ),
                ),
            ),
        ),
        text = SourceTextRequestDocument.None,
        entityLimit = SourceEntityLimitDocument.parse(250).refinedValue(),
        textByteLimit = SourceTextByteLimitDocument.parse(65_536).refinedValue(),
        page = SourceReadPageDocument.First,
    )

    private fun sourceReadResult(): SourceReadResult {
        val range = SourceSelectionRangeDocument.create(offset(10), offset(90)).refinedValue()
        val selection = SourceSelectionDocument(text("source-selector-v1:fixture"), range)
        return SourceReadResult(
            snapshot = SourceSnapshotDocument(
                canonicalRoot = text("/workspace"),
                generation = 17,
                sourceState = text("source-state:v1"),
                file = text("src/Target.kt"),
                textIdentity = text("text-identity:v1"),
                coordinateUnit = SourceCoordinateUnitDocument.UTF16_CODE_UNIT,
                length = SourceLengthDocument.parse(100).refinedValue(),
            ),
            region = SourceRegionDocument(SourceRegionKindDocument.CLASS_BODY, selection),
            entities = BoundedProtocolList.create(emptyList<SourceEntityDocument>()).refinedValue(),
            text = SourceTextProjectionDocument.NotRequested,
        )
    }

    private fun offset(raw: Int): ProtocolOffset = ProtocolOffset.parse(raw).refinedValue()
    private fun text(raw: String): ProtocolText = ProtocolText.parse(raw).refinedValue()

    private fun <Strong, Failure> Refinement<Strong, Failure>.refinedValue(): Strong = when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> error("Expected refined value, got $failure")
    }

    private fun WireEncoding.encodedDocument(): String = when (this) {
        is WireEncoding.Encoded -> document
        is WireEncoding.Rejected -> error("Expected encoded document, got $failure")
    }

    private fun WireRequestAdmission.admittedRequest(): AdmittedWireRequest = when (this) {
        is WireRequestAdmission.Admitted -> request
        is WireRequestAdmission.Rejected -> error("Expected admitted request, got $failure")
    }
}
