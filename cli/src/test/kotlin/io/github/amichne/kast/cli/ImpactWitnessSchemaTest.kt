package io.github.amichne.kast.cli

import com.networknt.schema.InputFormat
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SpecificationVersion
import io.github.amichne.kast.kernel.ElapsedTimeLimitMillis
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.kernel.ReturnedByteLimit
import io.github.amichne.kast.kernel.WorkUnitLimit
import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.ImpactBoundaryObligationDocument
import io.github.amichne.kast.protocol.contract.ImpactDeclarationReferenceDocument
import io.github.amichne.kast.protocol.contract.ImpactEvidenceRevisionDocument
import io.github.amichne.kast.protocol.contract.ImpactExecutionAccountingCause
import io.github.amichne.kast.protocol.contract.ImpactExecutionBoundaryCause
import io.github.amichne.kast.protocol.contract.ImpactExecutionFailureDocument
import io.github.amichne.kast.protocol.contract.ImpactExecutionLedgerCause
import io.github.amichne.kast.protocol.contract.ImpactExecutionModelHistoryCause
import io.github.amichne.kast.protocol.contract.ImpactExecutionPathCause
import io.github.amichne.kast.protocol.contract.ImpactExecutionRepresentationCause
import io.github.amichne.kast.protocol.contract.ImpactExecutionRowIdentityCause
import io.github.amichne.kast.protocol.contract.ImpactExecutionSelectionCause
import io.github.amichne.kast.protocol.contract.ImpactFindingDocument
import io.github.amichne.kast.protocol.contract.ImpactFindingEvidenceReferenceDocument
import io.github.amichne.kast.protocol.contract.ImpactFindingRepresentationDocument
import io.github.amichne.kast.protocol.contract.ImpactFindingTerminalDocument
import io.github.amichne.kast.protocol.contract.ImpactFlowBudgetDocument
import io.github.amichne.kast.protocol.contract.ImpactFlowUnsupportedDocument
import io.github.amichne.kast.protocol.contract.ImpactInvocationReferenceDocument
import io.github.amichne.kast.protocol.contract.ImpactNativeReadRejectionDocument
import io.github.amichne.kast.protocol.contract.ImpactReadRejectionDocument
import io.github.amichne.kast.protocol.contract.ImpactRequestedBoundaryDocument
import io.github.amichne.kast.protocol.contract.ImpactSemanticBasisDocument
import io.github.amichne.kast.protocol.contract.ImpactSiteAccountingDocument
import io.github.amichne.kast.protocol.contract.ImpactSiteAdmissionDocument
import io.github.amichne.kast.protocol.contract.ImpactSiteOutcomeDocument
import io.github.amichne.kast.protocol.contract.ImpactSourceRangeDocument
import io.github.amichne.kast.protocol.contract.ImpactValueRoleDocument
import io.github.amichne.kast.protocol.contract.ImpactValueSiteReferenceDocument
import io.github.amichne.kast.protocol.contract.ImpactWitnessDocument
import io.github.amichne.kast.protocol.contract.ProtocolOffset
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.contract.QueryDiscoveryCountDocument
import io.github.amichne.kast.protocol.contract.QueryResultRowReference
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ImpactWitnessSchemaTest {
    @Test
    fun `witness schema closes each finite section record and requires its proof fields`() {
        val document = generatedRequestSchema(ImpactWitnessDocument.serializer())
        val variants = document.getValue("anyOf").jsonArray
        assertEquals(
            setOf(
                "PRODUCER",
                "PRODUCER_SITE_ONLY",
                "REPRESENTATION_MODEL",
                "BOUNDARY_MODEL",
                "NATIVE_READ",
                "COMPILER_TRANSFER",
                "FLOW_OBLIGATION",
                "READ_REJECTION",
                "FINDING",
                "SITE_ACCOUNTING",
            ),
            variants
                .map {
                    it.jsonObject
                        .getValue("properties")
                        .jsonObject
                        .getValue("type")
                        .jsonObject
                        .getValue("enum")
                        .jsonArray
                        .single()
                        .jsonPrimitive
                        .content
                }
                .toSet(),
        )
        for (variant in variants) {
            assertEquals("false", variant.jsonObject.getValue("additionalProperties").jsonPrimitive.content)
            assertTrue(variant.jsonObject.getValue("required").jsonArray.any { it.jsonPrimitive.content == "type" })
        }
        val schema =
            SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12).getSchema(document.toString())
        val cases = witnessExamples()
        for (case in cases) {
            val raw = Json.encodeToString(ImpactWitnessDocument.serializer(), case)
            assertTrue(schema.validate(raw, InputFormat.JSON).isEmpty(), raw)
            assertTrue(schema.validate(raw.dropLast(1) + ",\"complete\":true}", InputFormat.JSON).isNotEmpty())
            assertTrue(
                schema.validate(raw.replaceFirst("\"type\":", "\"unknownType\":"), InputFormat.JSON).isNotEmpty()
            )
        }
    }

    @Test
    fun `finding schema requires both original path identity fields`() {
        val document = generatedRequestSchema(ImpactWitnessDocument.serializer())
        val schema =
            SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12).getSchema(document.toString())
        val finding = witnessExamples().filterIsInstance<ImpactWitnessDocument.Finding>().single()
        val raw = Json.encodeToString(ImpactWitnessDocument.serializer(), finding)
        assertTrue(schema.validate(raw, InputFormat.JSON).isEmpty(), raw)
        val missingOrdinal = raw.replace("\"pathOrdinal\":0,", "")
        val missingRow =
            raw.replace(
                ",\"pathRowId\":\"result-row:v1:00000000-0000-0000-0000-000000000001\"",
                "",
            )
        assertTrue(missingOrdinal != raw)
        assertTrue(missingRow != raw)
        assertTrue(schema.validate(missingOrdinal, InputFormat.JSON).isNotEmpty())
        assertTrue(schema.validate(missingRow, InputFormat.JSON).isNotEmpty())
    }

    @Test
    fun `interpreter failure schema closes all finite owners and rejects unknown codes`() {
        val document = generatedRequestSchema(ImpactExecutionFailureDocument.serializer())
        val variants = document.getValue("anyOf").jsonArray
        assertEquals(
            setOf(
                "PATH",
                "LEDGER",
                "ACCOUNTING",
                "REPRESENTATION",
                "BOUNDARY",
                "MODEL_HISTORY",
                "SELECTION",
                "ROW_IDENTITY",
                "PRESENTATION_ONLY",
            ),
            variants
                .map {
                    it.jsonObject
                        .getValue("properties")
                        .jsonObject
                        .getValue("type")
                        .jsonObject
                        .getValue("enum")
                        .jsonArray
                        .single()
                        .jsonPrimitive
                        .content
                }
                .toSet(),
        )
        val schema =
            SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12).getSchema(document.toString())
        val cases = interpreterExamples()
        for (case in cases) assertTrue(
            schema
                .validate(Json.encodeToString(ImpactExecutionFailureDocument.serializer(), case), InputFormat.JSON)
                .isEmpty()
        )
        val malformed =
            Json.encodeToString(ImpactExecutionFailureDocument.serializer(), cases.first())
                .replace("TERMINAL_UNPROVEN", "ASSUMED_COMPLETE")
        assertTrue(schema.validate(malformed, InputFormat.JSON).isNotEmpty())
    }

    private fun witnessExamples(): List<ImpactWitnessDocument> {
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
        val site =
            ImpactValueSiteReferenceDocument(
                owner,
                ImpactSourceRangeDocument(offset(10), offset(11)),
                ImpactValueRoleDocument.ExpressionResult,
            )
        val cases =
            listOf(
                ImpactWitnessDocument.Producer(site, ImpactInvocationReferenceDocument(site.range, owner)),
                ImpactWitnessDocument.ProducerSiteOnly(site),
                findingExample(site),
                siteAccountingExample(site),
                ImpactWitnessDocument.FlowObligation(
                    count(0),
                    count(0),
                    site,
                    ImpactFlowUnsupportedDocument.EXTERNAL_CALL,
                ),
                ImpactWitnessDocument.ReadRejection(
                    ImpactReadRejectionDocument.Native(
                        site,
                        ImpactRequestedBoundaryDocument.Workspace,
                        ImpactNativeReadRejectionDocument.NATIVE_UNAVAILABLE,
                        count(3),
                    )
                ),
            )
        return cases
    }

    private fun siteAccountingExample(site: ImpactValueSiteReferenceDocument) =
        ImpactWitnessDocument.SiteAccounting(
            ImpactSiteAccountingDocument(
                count(0),
                site,
                ImpactSiteOutcomeDocument.RelationshipUnproven,
                ImpactSiteAdmissionDocument(
                    ImpactFlowBudgetDocument(
                        ElapsedTimeLimitMillis.parse(1000).value(),
                        WorkUnitLimit.parse(10).value(),
                        ResultLimit.parse(10).value(),
                        ReturnedByteLimit.parse(4096).value(),
                    ),
                    count(1),
                ),
            )
        )

    private fun findingExample(site: ImpactValueSiteReferenceDocument): ImpactWitnessDocument.Finding =
        ImpactWitnessDocument.Finding(
            ImpactFindingDocument(
                path =
                    ImpactFindingEvidenceReferenceDocument(
                        count(0),
                        QueryResultRowReference.parse("result-row:v1:00000000-0000-0000-0000-000000000001").value(),
                    ),
                producer = site,
                destination = site,
                representation = ImpactFindingRepresentationDocument.NotModeled,
                terminal =
                    ImpactFindingTerminalDocument.UnresolvedFlow(ImpactFlowUnsupportedDocument.MUTABLE_CONTROL_FLOW),
                boundaryObligations = BoundedProtocolList.create(emptyList<ImpactBoundaryObligationDocument>()).value(),
            )
        )

    private fun interpreterExamples(): List<ImpactExecutionFailureDocument> {
        val cases =
            listOf(
                ImpactExecutionFailureDocument.Path(ImpactExecutionPathCause.TERMINAL_UNPROVEN),
                ImpactExecutionFailureDocument.Ledger(ImpactExecutionLedgerCause.MISSING_BRANCH),
                ImpactExecutionFailureDocument.Accounting(ImpactExecutionAccountingCause.DUPLICATE_PATH),
                ImpactExecutionFailureDocument.Representation(ImpactExecutionRepresentationCause.CALLABLE_MISMATCH),
                ImpactExecutionFailureDocument.Boundary(ImpactExecutionBoundaryCause.KIND_MISMATCH),
                ImpactExecutionFailureDocument.ModelHistory(ImpactExecutionModelHistoryCause.MISSING_APPLICATION),
                ImpactExecutionFailureDocument.Selection(ImpactExecutionSelectionCause.PRESENTATION_ONLY_ROWS),
                ImpactExecutionFailureDocument.RowIdentity(ImpactExecutionRowIdentityCause.CHANGED_RETAINED_ROWS),
                ImpactExecutionFailureDocument.PresentationOnly,
            )
        return cases
    }

    private fun text(value: String) = ProtocolText.parse(value).value()

    private fun offset(value: Int) = ProtocolOffset.parse(value).value()

    private fun count(value: Long) = QueryDiscoveryCountDocument.parse(value).value()
}

private fun <T> Refinement<T, *>.value(): T = (this as Refinement.Refined).value
