package io.github.amichne.kast.cli

import com.networknt.schema.InputFormat
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SpecificationVersion
import io.github.amichne.kast.cli.projection.CanonicalChangeCliDocuments
import io.github.amichne.kast.kernel.EvidenceEnvelope
import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.CanonicalOperation
import io.github.amichne.kast.protocol.contract.ChangeApplyQualification
import io.github.amichne.kast.protocol.contract.ChangeApplyRecoveryReason
import io.github.amichne.kast.protocol.contract.ChangeApplyResult
import io.github.amichne.kast.protocol.contract.ChangeApplyUnverifiedReason
import io.github.amichne.kast.protocol.contract.ChangeFilePreview
import io.github.amichne.kast.protocol.contract.ChangeFilePreviewKind
import io.github.amichne.kast.protocol.contract.ChangeFilePreviewSet
import io.github.amichne.kast.protocol.contract.ChangePlanRequest
import io.github.amichne.kast.protocol.contract.ChangePreviewDiff
import io.github.amichne.kast.protocol.contract.ChangePreviewPath
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.registry.CanonicalOperationDefinitions
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HostedChangeProjectionTest {
    private val schemas = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
    private val changes =
        (ChangeFilePreviewSet.admit(
                listOf(
                    ChangeFilePreview(
                        (ChangePreviewPath.parse("src/Target.kt") as Refinement.Refined).value,
                        ChangeFilePreviewKind.UPDATE,
                        (ChangePreviewDiff.parse("+fun added() = Unit") as Refinement.Refined).value,
                    )
                )
            ) as Refinement.Refined)
            .value

    private fun text(raw: String) = (ProtocolText.parse(raw) as Refinement.Refined).value

    @Test
    fun `hosted plan schema retains generated constraints and only canonical supported intent`() {
        val schema =
            schemas.getSchema(
                generatedHostedRequestSchema(
                        ChangePlanRequest.serializer(),
                        CanonicalOperationDefinitions.changePlan.hostedVariants,
                    )
                    .toString()
            )
        assertTrue(
            schema
                .validate(
                    """{"intent":{"kind":"add-declaration","exactTarget":"exact:opaque",""" +
                        """"declaration":"fun added() = Unit"}}""",
                    InputFormat.JSON,
                )
                .isEmpty()
        )
        for (request in
            listOf(
                """{"intent":{"kind":"add-file","relativePath":"src/Other.kt","content":"class Other"}}""",
                """{"intent":{"kind":"rename-symbol","exactTarget":"exact:opaque","newName":"Other"}}""",
                """{"intent":{"kind":"add-declaration","declaration":"fun added() = Unit"}}""",
                """{"intent":{"kind":"add-declaration","exactTarget":"exact:opaque","declaration":"x","extra":1}}""",
            )) assertTrue(schema.validate(request, InputFormat.JSON).isNotEmpty())
    }

    @Test
    fun `every observed apply effect satisfies output schema without a counterfeit receipt`() {
        val schema = schemas.getSchema(installedServerOutputSchema(CanonicalOperation.CHANGE_APPLY).toString())
        val results =
            listOf<ChangeApplyResult>(ChangeApplyResult.Verified(text("receipt:1"), changes)) +
                ChangeApplyUnverifiedReason.entries.map {
                    ChangeApplyResult.AppliedUnverified(text("plan:1"), changes, it)
                } +
                ChangeApplyRecoveryReason.entries.map {
                    ChangeApplyResult.RecoveryRequired(text("plan:1"), changes, it)
                }
        for (result in results) {
            val document = project(result)
            val output = buildJsonObject {
                put("status", "completed")
                put("document", document)
            }
            assertTrue(schema.validate(output.toString(), InputFormat.JSON).isEmpty(), output.toString())
            if (result !is ChangeApplyResult.Verified) {
                assertFalse(document.containsKey("receiptIdentity"))
                val counterfeit =
                    JsonObject(
                        output + ("document" to JsonObject(document + ("receiptIdentity" to JsonPrimitive("fake"))))
                    )
                assertTrue(schema.validate(counterfeit.toString(), InputFormat.JSON).isNotEmpty())
            }
        }
    }

    private fun project(result: ChangeApplyResult): JsonObject {
        val envelope =
            EvidenceEnvelope(
                CanonicalOperation.CHANGE_APPLY.id,
                (EvidenceGeneration.parse(1) as Refinement.Refined).value,
                result,
            )
        val projected =
            CanonicalChangeCliDocuments.projectApplication(
                when (result) {
                    is ChangeApplyResult.Verified -> OperationOutcome.Complete(envelope)
                    is ChangeApplyResult.AppliedUnverified ->
                        OperationOutcome.Qualified(envelope, ChangeApplyQualification.APPLIED_UNVERIFIED)
                    is ChangeApplyResult.RecoveryRequired ->
                        OperationOutcome.Qualified(envelope, ChangeApplyQualification.RECOVERY_REQUIRED)
                }
            )
        return Json.parseToJsonElement(
                when (projected) {
                    is ProjectedCliOutcome.Complete -> projected.document
                    is ProjectedCliOutcome.Qualified -> projected.document
                    is ProjectedCliOutcome.Rejected -> projected.document
                }.value
            )
            .jsonObject
    }
}
