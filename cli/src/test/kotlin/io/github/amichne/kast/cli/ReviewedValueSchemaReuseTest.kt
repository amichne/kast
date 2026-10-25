package io.github.amichne.kast.cli

import com.networknt.schema.InputFormat
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SpecificationVersion
import io.github.amichne.kast.protocol.contract.ImpactReviewedValueBindingDocument
import io.github.amichne.kast.protocol.contract.ImpactReviewedValueTransferDocument
import io.github.amichne.kast.protocol.registry.PublicToolIdentity
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReviewedValueSchemaReuseTest {
    private val json = Json { encodeDefaults = true }
    private val schema by lazy { installedPublicToolOutputSchema(PublicToolIdentity.QUERY_SYMBOLS) }

    @Test
    fun `shared reviewed definitions preserve complete independent serializer shapes`() {
        val assertions = LocalSchemaAssertions(schema)
        assertions.assertLocalReferences()
        for ((name, serializer) in
            mapOf(
                "impactReviewedValueBinding" to ImpactReviewedValueBindingDocument.serializer(),
                "impactReviewedValueTransfer" to ImpactReviewedValueTransferDocument.serializer(),
            )) {
            assertions.assertDefinitionEquivalent(name, independentImpactSchema(serializer))
            assertTrue(referenceCount(schema, name) > 1, "The repeated schema must share $name")
        }
    }

    @Test
    fun `shared reviewed transfer admits typed evidence and rejects malformed provenance`() {
        val registry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
        val validators =
            listOf(
                CallbackSchemaFixture(schema).validator("impactReviewedValueTransfer"),
                registry.getSchema(
                    independentImpactSchemaWithDefinitions(ImpactReviewedValueTransferDocument.serializer()).toString()
                ),
            )
        val admitted =
            json.encodeToString(ImpactReviewedValueTransferDocument.serializer(), reviewedTransferSchemaFixture())
        validators.forEach { assertTrue(it.validate(admitted, InputFormat.JSON).isEmpty(), admitted) }
        for ((before, after) in
            listOf(
                "\"binaryArtifactSha256\":\"bd8250210584cb659847dce9cda660f8e9906e5def7fbebcea93dc6dcb6a88a3\"" to
                    "\"binaryArtifactSha256\":\"bad\"",
                "\"sourceEntry\":" to "\"unprovenSourceEntry\":",
                "\"selected\":" to "\"unprovenSelected\":",
                "\"formalIndex\":0" to "\"formalIndex\":-1",
                "\"mapLines\":" to "\"unprovenMapLines\":",
                "\"CONDITIONAL_CALLBACK_RESULT_TO_OUTPUT_ELEMENT\"" to "\"ACTUAL_EXECUTION\"",
                "\"actualExecution\":\"NOT_ESTABLISHED\"" to "\"actualExecution\":\"EXECUTED\"",
                "\"invocationCount\":\"NOT_ESTABLISHED\"" to "\"invocationCount\":1",
                "\"REVIEWED_CALLBACK\"" to "\"ASSUMED_CALLBACK\"",
                "\"ELEMENT\"" to "\"ASSUMED_ELEMENT\"",
            )) {
            val rejected = admitted.replace(before, after)
            assertNotEquals(admitted, rejected, "Negative control must change the admitted proof")
            validators.forEach { assertFalse(it.validate(rejected, InputFormat.JSON).isEmpty(), rejected) }
        }
    }
}
