package io.github.amichne.kast.protocol.wire

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.ImpactDeclarationReferenceDocument
import io.github.amichne.kast.protocol.contract.ImpactEvidenceRevisionDocument
import io.github.amichne.kast.protocol.contract.ImpactFlowUnsupportedDocument
import io.github.amichne.kast.protocol.contract.ImpactPathDocument
import io.github.amichne.kast.protocol.contract.ImpactPathStepDocument
import io.github.amichne.kast.protocol.contract.ImpactPathTerminalDocument
import io.github.amichne.kast.protocol.contract.ImpactRepresentationEvidenceDocument
import io.github.amichne.kast.protocol.contract.ImpactSemanticBasisDocument
import io.github.amichne.kast.protocol.contract.ImpactSourceRangeDocument
import io.github.amichne.kast.protocol.contract.ImpactValueRoleDocument
import io.github.amichne.kast.protocol.contract.ImpactValueSiteReferenceDocument
import io.github.amichne.kast.protocol.contract.ProtocolOffset
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.contract.QueryResultItemDocument
import io.github.amichne.kast.protocol.contract.QueryResultRowReference
import io.github.amichne.kast.protocol.wire.presentation.QueryResultItemCliDocument
import io.github.amichne.kast.protocol.wire.presentation.toCliDocument
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ImpactPathWireTest {
    @Test
    fun `wire and CLI retain complete canonical VALUE_PATH payload and required discriminators`() {
        val item = item()
        val expected = Json.parseToJsonElement(javaClass.getResource("/impact-path.expected.json")!!.readText())
        assertEquals(expected, Json.encodeToJsonElement(QueryResultItemWireDocument.serializer(), item.toWire()))
        assertEquals(expected, Json.encodeToJsonElement(QueryResultItemCliDocument.serializer(), item.toCliDocument()))
        val restored = item.toWire().toContract() as WireDocumentConversion.Converted
        assertEquals(item, restored.value)
    }

    @Test
    fun `retained row identity survives wire and CLI without becoming a live selector`() {
        val row = QueryResultRowReference.parse("result-row:v1:00000000-0000-0000-0000-000000000004").refined()
        val item = item().copy(rowId = row)
        val wire = item.toWire() as QueryResultItemWireDocument.ValuePath
        val cli = item.toCliDocument() as QueryResultItemCliDocument.ValuePath
        assertEquals(row.value, wire.rowId)
        assertEquals(row.value, cli.rowId)
        assertEquals(item, (wire.toContract() as WireDocumentConversion.Converted).value)
    }

    private fun item(): QueryResultItemDocument.ValuePath {
        val site =
            ImpactValueSiteReferenceDocument(
                ImpactDeclarationReferenceDocument(
                    ImpactSemanticBasisDocument.Published(
                        text("/workspace"),
                        ImpactEvidenceRevisionDocument.parse(7).refined(),
                    ),
                    text("/workspace/File.kt"),
                    ImpactSourceRangeDocument(offset(0), offset(100)),
                    text("canonical-signature-sha256-v1|" + "a".repeat(64)),
                ),
                ImpactSourceRangeDocument(offset(10), offset(11)),
                ImpactValueRoleDocument.ExpressionResult,
            )
        return QueryResultItemDocument.ValuePath(
            ImpactPathDocument(
                site,
                BoundedProtocolList.create(emptyList<ImpactPathStepDocument>()).refined(),
                ImpactRepresentationEvidenceDocument.NotModeled,
                ImpactPathTerminalDocument.UnresolvedFlow(site, ImpactFlowUnsupportedDocument.EXTERNAL_CALL),
            )
        )
    }

    private fun text(raw: String) = ProtocolText.parse(raw).refined()

    private fun offset(raw: Int) = ProtocolOffset.parse(raw).refined()

    private fun <S, F> Refinement<S, F>.refined(): S =
        when (this) {
            is Refinement.Refined -> value
            is Refinement.Rejected -> error(failure.toString())
        }
}
