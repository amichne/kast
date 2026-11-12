package io.github.amichne.kast.cli

import com.networknt.schema.InputFormat
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SpecificationVersion
import io.github.amichne.kast.protocol.contract.ImpactNativeOperationDocument
import io.github.amichne.kast.protocol.contract.ImpactNativePrerequisiteDocument
import io.github.amichne.kast.protocol.contract.ImpactNativeReadRejectionDocument
import io.github.amichne.kast.protocol.contract.ImpactNativeRelationOriginDocument
import io.github.amichne.kast.protocol.contract.ImpactNativeRelationRejectionDocument
import io.github.amichne.kast.protocol.contract.ImpactNativeUnexpectedKindDocument
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ImpactNativeFailureSchemaTest {
    @Test
    fun `native failure schema requires all case evidence and rejects old or unsupported failures`() {
        val document = independentImpactSchemaWithDefinitions(ImpactNativeReadRejectionDocument.serializer())
        val schema =
            SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12).getSchema(document.toString())
        for (case in cases()) {
            val raw = Json.encodeToString(ImpactNativeReadRejectionDocument.serializer(), case)
            assertTrue(schema.validate(raw, InputFormat.JSON).isEmpty(), raw)
            assertTrue(schema.validate(raw.dropLast(1) + ",\"complete\":true}", InputFormat.JSON).isNotEmpty())
        }
        // Deliberately incomplete/unknown inputs, including absent (not merely empty) adapter frames.
        val invalid =
            Json.parseToJsonElement(
                    checkNotNull(javaClass.getResource("/impact-native-failures.invalid.json")).readText()
                )
                .jsonArray
        invalid.forEach { raw ->
            assertTrue(schema.validate(raw.toString(), InputFormat.JSON).isNotEmpty(), raw.toString())
        }
    }

    private fun cases(): List<ImpactNativeReadRejectionDocument> =
        listOf(
            ImpactNativeReadRejectionDocument.STALE_SITE,
            ImpactNativeReadRejectionDocument.HostUnavailable(
                ImpactNativeOperationDocument.FLOW_READ,
                ImpactNativePrerequisiteDocument.PROJECT_DISPOSED,
            ),
            ImpactNativeReadRejectionDocument.Unexpected(
                operation = ImpactNativeOperationDocument.FLOW_READ,
                kind = ImpactNativeUnexpectedKindDocument.LINKAGE,
                exceptionType = "java.lang.NoSuchMethodError",
                adapterFrames = emptyList(),
            ),
            ImpactNativeReadRejectionDocument.RelationRejected(
                ImpactNativeRelationOriginDocument.IMPLEMENTATION_DISPATCH,
                ImpactNativeRelationRejectionDocument.WORKSPACE_INDEX_UNAVAILABLE,
            ),
            ImpactNativeReadRejectionDocument.PortUnconfigured(ImpactNativeOperationDocument.FLOW_READ),
        )
}
