package io.github.amichne.kast.cli

import io.github.amichne.kast.kernel.EvidenceBasis
import io.github.amichne.kast.kernel.EvidenceEnvelope
import io.github.amichne.kast.kernel.LiveReadContentView
import io.github.amichne.kast.kernel.LiveReadEvidence
import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.CanonicalOperation
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.contract.ReadReferenceAcquisition
import io.github.amichne.kast.protocol.contract.ReadReferenceAcquisitions
import io.github.amichne.kast.protocol.contract.SourceReadFormatDocument
import io.github.amichne.kast.protocol.wire.CanonicalOperationWireBindings
import io.github.amichne.kast.protocol.wire.WireDecoding
import io.github.amichne.kast.protocol.wire.WireEncoding
import io.github.amichne.kast.protocol.wire.presentation.CanonicalSourceReadCliDocuments
import java.util.UUID
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ReadAcquisitionSchemaTest {
    @Test
    fun `source handle acquisition survives wire and both output formats`() =
        with(LiveReadOutputSchemaTest()) {
            val basis =
                EvidenceBasis.Live(
                    LiveReadEvidence.create("/workspace", UUID(0, 1), 7, LiveReadContentView.SAVED_PSI_COMMITTED, 1)
                        .proven()
                )
            val acquisitions =
                ReadReferenceAcquisitions.admit(
                        listOf(ReadReferenceAcquisition(text("exact:old"), text("exact:current")))
                    )
                    .proven()
            for (format in SourceReadFormatDocument.entries) {
                val result = sourceResult(basis).copy(format = format, referenceAcquisitions = acquisitions)
                val outcome =
                    OperationOutcome.Complete(EvidenceEnvelope(CanonicalOperation.SOURCE_READ.id, basis, result))
                val binding = CanonicalOperationWireBindings.sourceRead
                val encoded = binding.encodeOutcome(outcome) as WireEncoding.Encoded
                val decoded = binding.decodeOutcome(encoded.document) as WireDecoding.Decoded
                assertEquals(outcome, decoded.value)
                val projected = CanonicalSourceReadCliDocuments.project(decoded.value).document()
                assertEquals(setOf("references"), projected.getValue("reference_acquisitions").jsonObject.keys)
                assertAdmits(CanonicalOperation.SOURCE_READ, projected)
            }
        }

    private fun text(value: String) = ProtocolText.parse(value).proven()

    private fun <V> Refinement<V, *>.proven(): V = (this as Refinement.Refined).value
}
