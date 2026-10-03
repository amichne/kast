package io.github.amichne.kast.cli

import com.networknt.schema.InputFormat
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SpecificationVersion
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.ImpactAccountingDocument
import io.github.amichne.kast.protocol.contract.ImpactAccountingStatusDocument
import io.github.amichne.kast.protocol.contract.ImpactAccountingViewDocument
import io.github.amichne.kast.protocol.contract.ImpactClosureDocument
import io.github.amichne.kast.protocol.contract.ImpactDeclarationReferenceDocument
import io.github.amichne.kast.protocol.contract.ImpactEvidenceRevisionDocument
import io.github.amichne.kast.protocol.contract.ImpactFlowSemanticsDocument
import io.github.amichne.kast.protocol.contract.ImpactRequestedBoundaryDocument
import io.github.amichne.kast.protocol.contract.ImpactRequiredObligationDocument
import io.github.amichne.kast.protocol.contract.ImpactSemanticBasisDocument
import io.github.amichne.kast.protocol.contract.ImpactSourceRangeDocument
import io.github.amichne.kast.protocol.contract.ImpactValueRoleDocument
import io.github.amichne.kast.protocol.contract.ImpactValueSiteReferenceDocument
import io.github.amichne.kast.protocol.contract.ProtocolOffset
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.contract.QueryDiscoveryCountDocument
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ImpactAccountingSchemaTest {
    @Test
    fun `accounting schema requires finite qualifiers and rejects unknown extra and missing fields`() {
        val document = generatedRequestSchema(ImpactAccountingDocument.serializer())
        val variants = document.getValue("anyOf").jsonArray
        assertEquals(
            setOf("NOT_APPLICABLE", "EVIDENCE_ONLY", "INVESTIGATED"),
            discriminatorValues(variants),
        )
        for (variant in variants) assertEquals(
            "false",
            variant.jsonObject.getValue("additionalProperties").jsonPrimitive.content,
        )
        assertInvestigationRequirements(variants)
        val schema =
            SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12).getSchema(document.toString())
        val examples = examples()
        for (example in examples) {
            val raw = Json.encodeToString(ImpactAccountingDocument.serializer(), example)
            assertTrue(schema.validate(raw, InputFormat.JSON).isEmpty(), raw)
            assertTrue(schema.validate(raw.dropLast(1) + ",\"complete\":true}", InputFormat.JSON).isNotEmpty())
            assertTrue(
                schema
                    .validate(
                        raw.replace("NOT_APPLICABLE", "ASSUMED")
                            .replace("EVIDENCE_ONLY", "ASSUMED")
                            .replace("INVESTIGATED", "ASSUMED"),
                        InputFormat.JSON,
                    )
                    .isNotEmpty()
            )
        }
        assertTrue(
            schema
                .validate(
                    javaClass.getResource("/impact-accounting-missing-count.invalid.json")!!.readText(),
                    InputFormat.JSON,
                )
                .isNotEmpty()
        )
    }

    private fun discriminatorValues(variants: kotlinx.serialization.json.JsonArray): Set<String> =
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
            .toSet()

    private fun examples(): List<ImpactAccountingDocument> {
        val site =
            ImpactValueSiteReferenceDocument(
                ImpactDeclarationReferenceDocument(
                    ImpactSemanticBasisDocument.Published(
                        text("/workspace"),
                        ImpactEvidenceRevisionDocument.parse(7).value(),
                    ),
                    text("/workspace/File.kt"),
                    ImpactSourceRangeDocument(offset(0), offset(100)),
                    text("canonical-signature-sha256-v1|" + "a".repeat(64)),
                ),
                ImpactSourceRangeDocument(offset(10), offset(11)),
                ImpactValueRoleDocument.ExpressionResult,
            )
        val required = bounded(listOf(ImpactRequiredObligationDocument.NATIVE_FLOW))
        val statuses =
            listOf(
                ImpactAccountingStatusDocument.Conserved,
                ImpactAccountingStatusDocument.Unresolved(required),
                ImpactAccountingStatusDocument.SelectedSubset(ImpactClosureDocument.Discharged),
                ImpactAccountingStatusDocument.SelectedSubset(ImpactClosureDocument.Unresolved(required)),
            )
        val examples =
            listOf(ImpactAccountingDocument.NotApplicable, ImpactAccountingDocument.EvidenceOnly(count(1))) +
                statuses.map { status ->
                    ImpactAccountingDocument.Investigated(
                        bounded(listOf(site)),
                        ImpactRequestedBoundaryDocument.Workspace,
                        ImpactFlowSemanticsDocument.KOTLIN_FORWARD_V1,
                        bounded(emptyList()),
                        bounded(emptyList()),
                        count(0),
                        count(1),
                        count(1),
                        count(1),
                        status,
                        ImpactAccountingViewDocument.Paths,
                    )
                }
        return examples
    }

    private fun assertInvestigationRequirements(variants: kotlinx.serialization.json.JsonArray) {
        val investigated =
            variants
                .single {
                    it.jsonObject
                        .getValue("properties")
                        .jsonObject
                        .getValue("type")
                        .jsonObject
                        .getValue("enum")
                        .jsonArray
                        .single()
                        .jsonPrimitive
                        .content == "INVESTIGATED"
                }
                .jsonObject
        assertTrue(
            investigated
                .getValue("required")
                .jsonArray
                .map { it.jsonPrimitive.content }
                .containsAll(
                    listOf(
                        "seeds",
                        "requestedDomain",
                        "semantics",
                        "representationModelReferences",
                        "boundaryModelReferences",
                        "originalReadRejectionCount",
                        "originalObservationCount",
                        "originalPathCount",
                        "pagePathCount",
                        "status",
                        "view",
                    )
                )
        )
    }

    private fun text(raw: String) = ProtocolText.parse(raw).value()

    private fun offset(raw: Int) = ProtocolOffset.parse(raw).value()

    private fun count(raw: Long) = QueryDiscoveryCountDocument.parse(raw).value()

    private fun <T> bounded(values: List<T>) = BoundedProtocolList.create(values).value()

    private fun <T> Refinement<T, *>.value(): T = (this as Refinement.Refined).value
}
