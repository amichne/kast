package io.github.amichne.kast.protocol.wire

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.ImpactCastKindDocument
import io.github.amichne.kast.protocol.contract.ImpactDeclarationReferenceDocument
import io.github.amichne.kast.protocol.contract.ImpactEvidenceRevisionDocument
import io.github.amichne.kast.protocol.contract.ImpactExecutionReferenceDocument
import io.github.amichne.kast.protocol.contract.ImpactFlowUnsupportedDocument
import io.github.amichne.kast.protocol.contract.ImpactPathDocument
import io.github.amichne.kast.protocol.contract.ImpactPathStepDocument
import io.github.amichne.kast.protocol.contract.ImpactPathTerminalDocument
import io.github.amichne.kast.protocol.contract.ImpactRepresentationEvidenceDocument
import io.github.amichne.kast.protocol.contract.ImpactSemanticBasisDocument
import io.github.amichne.kast.protocol.contract.ImpactSourceRangeDocument
import io.github.amichne.kast.protocol.contract.ImpactValueConditionDocument
import io.github.amichne.kast.protocol.contract.ImpactValueConditionOriginDocument
import io.github.amichne.kast.protocol.contract.ImpactValueRoleDocument
import io.github.amichne.kast.protocol.contract.ImpactValueSiteReferenceDocument
import io.github.amichne.kast.protocol.contract.ProtocolOffset
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.contract.QueryResultItemDocument
import io.github.amichne.kast.protocol.contract.QueryResultRowReference
import io.github.amichne.kast.protocol.wire.presentation.QueryResultItemCliDocument
import io.github.amichne.kast.protocol.wire.presentation.toCliDocument
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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

    @Test
    fun `wire and CLI preserve both cast prerequisites with explicit discriminators`() {
        for ((kind, encodedKind) in
            listOf(ImpactCastKindDocument.UNSAFE to "UNSAFE", ImpactCastKindDocument.SAFE to "SAFE")) {
            val item = castItem(kind)
            val wire = Json.encodeToJsonElement(QueryResultItemWireDocument.serializer(), item.toWire())
            val cli = Json.encodeToJsonElement(QueryResultItemCliDocument.serializer(), item.toCliDocument())
            assertEquals(wire, cli)
            val fact =
                wire.jsonObject
                    .getValue("path")
                    .jsonObject
                    .getValue("producer")
                    .jsonObject
                    .getValue("conditions")
                    .jsonArray
                    .single()
                    .jsonObject
            assertEquals(setOf("type", "origin", "useRange", "kind", "originalType", "targetType"), fact.keys)
            assertEquals("CAST_SUCCESS", fact.getValue("type").jsonPrimitive.content)
            assertEquals(encodedKind, fact.getValue("kind").jsonPrimitive.content)
            assertEquals("kotlin/collections/List<kotlin/String>", fact.getValue("targetType").jsonPrimitive.content)
            assertEquals(item, (item.toWire().toContract() as WireDocumentConversion.Converted).value)
        }
    }

    private fun castItem(kind: ImpactCastKindDocument): QueryResultItemDocument.ValuePath {
        val original = item()
        val site = original.path.producer
        val condition =
            ImpactValueConditionDocument.CastSuccess(
                ImpactValueConditionOriginDocument(
                    ImpactExecutionReferenceDocument(site.enclosing),
                    site.range,
                    site.role,
                ),
                ImpactSourceRangeDocument(offset(10), offset(30)),
                kind,
                text("kotlin/Any?"),
                text("kotlin/collections/List<kotlin/String>"),
            )
        val conditioned =
            site.copy(
                conditions = BoundedProtocolList.create<ImpactValueConditionDocument>(listOf(condition)).refined()
            )
        return original.copy(
            path =
                original.path.copy(
                    producer = conditioned,
                    terminal =
                        ImpactPathTerminalDocument.UnresolvedFlow(
                            conditioned,
                            ImpactFlowUnsupportedDocument.EXTERNAL_CALL,
                        ),
                )
        )
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
