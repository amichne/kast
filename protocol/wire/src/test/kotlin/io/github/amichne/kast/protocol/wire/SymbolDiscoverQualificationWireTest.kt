package io.github.amichne.kast.protocol.wire

import io.github.amichne.kast.kernel.EvidenceEnvelope
import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.SymbolDiscoverLimitation
import io.github.amichne.kast.protocol.contract.SymbolDiscoverQualification
import io.github.amichne.kast.protocol.contract.SymbolDiscoverResult
import io.github.amichne.kast.protocol.contract.SymbolDiscoveryDocument
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SymbolDiscoverQualificationWireTest {
    @Test
    fun `single limitation encodes as an ordered limitations object`() {
        val evidence = emptyEvidence()
        val qualification = SymbolDiscoverQualification.from(
            setOf(SymbolDiscoverLimitation.WORK_LIMIT),
        ).refinedValue()

        val document = CanonicalOperationWireBindings.symbolDiscover.encodeOutcome(
            OperationOutcome.Qualified(evidence, qualification),
        ).encodedDocument()

        assertTrue(document.contains("\"qualification\":{\"limitations\":[\"work-limit\"]}"))
        assertEquals(
            WireDecoding.Decoded(OperationOutcome.Qualified(evidence, qualification)),
            CanonicalOperationWireBindings.symbolDiscover.decodeOutcome(document),
        )
    }

    @Test
    fun `multiple limitations round trip in deterministic order`() {
        val evidence = emptyEvidence()
        val qualification = SymbolDiscoverQualification.from(
            setOf(
                SymbolDiscoverLimitation.PROVIDER_FAILURE,
                SymbolDiscoverLimitation.WORK_LIMIT,
            ),
        ).refinedValue()

        val document = CanonicalOperationWireBindings.symbolDiscover.encodeOutcome(
            OperationOutcome.Qualified(evidence, qualification),
        ).encodedDocument()

        assertTrue(
            document.contains("\"limitations\":[\"work-limit\",\"provider-failure\"]"),
        )
        val decoded = CanonicalOperationWireBindings.symbolDiscover.decodeOutcome(document)
        assertEquals(
            WireDecoding.Decoded(OperationOutcome.Qualified(evidence, qualification)),
            decoded,
        )
        assertEquals(
            listOf(SymbolDiscoverLimitation.WORK_LIMIT, SymbolDiscoverLimitation.PROVIDER_FAILURE),
            (decoded as WireDecoding.Decoded)
                .value.let { (it as OperationOutcome.Qualified<*, *>).qualification }
                .let { (it as SymbolDiscoverQualification).limitations },
        )
    }

    private fun emptyEvidence(): EvidenceEnvelope<SymbolDiscoverResult> = EvidenceEnvelope(
        operation = CanonicalOperationWireBindings.symbolDiscover.operation.id,
        generation = EvidenceGeneration.parse(17).refinedValue(),
        payload = SymbolDiscoverResult(
            BoundedProtocolList.create(emptyList<SymbolDiscoveryDocument>()).refinedValue(),
        ),
    )

    private fun <Strong, Failure> Refinement<Strong, Failure>.refinedValue(): Strong = when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> error("Expected refined value, got $failure")
    }

    private fun WireEncoding.encodedDocument(): String = when (this) {
        is WireEncoding.Encoded -> document
        is WireEncoding.Rejected -> error("Expected encoded document, got $failure")
    }
}
