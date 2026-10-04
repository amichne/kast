package io.github.amichne.kast.protocol.wire

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.ImpactDeclarationReferenceDocument
import io.github.amichne.kast.protocol.contract.ImpactEvidenceRevisionDocument
import io.github.amichne.kast.protocol.contract.ImpactFlowUnsupportedDocument
import io.github.amichne.kast.protocol.contract.ImpactInvocationReferenceDocument
import io.github.amichne.kast.protocol.contract.ImpactNativeReadRejectionDocument
import io.github.amichne.kast.protocol.contract.ImpactReadRejectionDocument
import io.github.amichne.kast.protocol.contract.ImpactRequestedBoundaryDocument
import io.github.amichne.kast.protocol.contract.ImpactSemanticBasisDocument
import io.github.amichne.kast.protocol.contract.ImpactSourceRangeDocument
import io.github.amichne.kast.protocol.contract.ImpactValueRoleDocument
import io.github.amichne.kast.protocol.contract.ImpactValueSiteReferenceDocument
import io.github.amichne.kast.protocol.contract.ImpactWitnessDocument
import io.github.amichne.kast.protocol.contract.ImpactWitnessItemDocument
import io.github.amichne.kast.protocol.contract.ImpactWitnessSectionDocument
import io.github.amichne.kast.protocol.contract.ProtocolOffset
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.contract.QueryDiscoveryCountDocument
import io.github.amichne.kast.protocol.contract.QueryResultItemDocument
import io.github.amichne.kast.protocol.wire.presentation.QueryResultItemCliDocument
import io.github.amichne.kast.protocol.wire.presentation.toCliDocument
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonArray
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ImpactWitnessWireTest {
    @Test
    fun `witness records preserve exact producer and obligation identities in independent wire and CLI shapes`() {
        val cases = records()
        val expected =
            Json.parseToJsonElement(javaClass.getResource("/impact-witness.expected.json")!!.readText()).jsonArray
        assertEquals(4, expected.size)
        for ((index, record) in cases.withIndex()) {
            val item = QueryResultItemDocument.ImpactWitness(record)
            val wire = item.toWire()
            assertEquals(expected[index], Json.encodeToJsonElement(QueryResultItemWireDocument.serializer(), wire))
            assertEquals(
                expected[index],
                Json.encodeToJsonElement(QueryResultItemCliDocument.serializer(), item.toCliDocument()),
            )
            assertEquals(item, (wire.toContract() as WireDocumentConversion.Converted).value)
            assertNull(item.rowId)
        }
    }

    private fun records(): List<ImpactWitnessItemDocument> {
        val site = witnessSite()
        val owner = site.enclosing
        val invocation = ImpactInvocationReferenceDocument(site.range, owner)
        val cases =
            listOf(
                ImpactWitnessItemDocument(
                    ImpactWitnessSectionDocument.PRODUCERS,
                    count(0),
                    ImpactWitnessDocument.Producer(site, invocation),
                ),
                ImpactWitnessItemDocument(
                    ImpactWitnessSectionDocument.PRODUCERS,
                    count(1),
                    ImpactWitnessDocument.ProducerSiteOnly(site),
                ),
                ImpactWitnessItemDocument(
                    ImpactWitnessSectionDocument.NATIVE_READS,
                    count(2),
                    ImpactWitnessDocument.FlowObligation(
                        count(0),
                        count(0),
                        site,
                        ImpactFlowUnsupportedDocument.EXTERNAL_CALL,
                    ),
                ),
                ImpactWitnessItemDocument(
                    ImpactWitnessSectionDocument.READ_REJECTIONS,
                    count(0),
                    ImpactWitnessDocument.ReadRejection(
                        ImpactReadRejectionDocument.Native(
                            site,
                            ImpactRequestedBoundaryDocument.Workspace,
                            ImpactNativeReadRejectionDocument.NATIVE_UNAVAILABLE,
                            count(3),
                        )
                    ),
                ),
            )
        return cases
    }

    private fun witnessSite(): ImpactValueSiteReferenceDocument {
        val owner =
            ImpactDeclarationReferenceDocument(
                ImpactSemanticBasisDocument.Published(
                    text("/workspace"),
                    ImpactEvidenceRevisionDocument.parse(7).value(),
                ),
                text("/workspace/File.kt"),
                ImpactSourceRangeDocument(offset(0), offset(100)),
                text("canonical-signature-sha256-v1|" + "a".repeat(64)),
            )
        return ImpactValueSiteReferenceDocument(
            owner,
            ImpactSourceRangeDocument(offset(10), offset(11)),
            ImpactValueRoleDocument.ExpressionResult,
        )
    }

    private fun text(value: String) = ProtocolText.parse(value).value()

    private fun offset(value: Int) = ProtocolOffset.parse(value).value()

    private fun count(value: Long) = QueryDiscoveryCountDocument.parse(value).value()
}

private fun <T> Refinement<T, *>.value(): T = (this as Refinement.Refined).value
