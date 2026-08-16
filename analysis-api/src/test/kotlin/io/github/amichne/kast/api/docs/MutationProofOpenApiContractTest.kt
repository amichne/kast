package io.github.amichne.kast.api.docs

import com.networknt.schema.InputFormat
import com.networknt.schema.SchemaRegistry as JsonSchemaRegistry
import com.networknt.schema.SpecificationVersion
import io.github.amichne.kast.api.docs.internal.SchemaRegistry as OpenApiSchemaRegistry
import io.github.amichne.kast.api.docs.internal.registerOpenApiSchemas
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MutationProofOpenApiContractTest {
    @Test
    fun `exact mutation proof fields retain complete discriminated schemas`() {
        val yaml = OpenApiDocument.renderYaml()

        assertPropertyRef(yaml, "ExactRenameProof", "evidence", "RelationshipResultEvidence.Complete")
        assertPropertyRef(yaml, "ExactReplacementProof", "evidence", "ReplacementOutboundEvidence.Complete")
        assertPropertyRef(
            yaml,
            "MutationPostconditionEvidence.Rename",
            "evidence",
            "RelationshipResultEvidence.Complete",
        )
        assertPropertyRef(
            yaml,
            "MutationPostconditionEvidence.Replacement",
            "outboundEvidence",
            "ReplacementOutboundEvidence.Complete",
        )
        assertPropertyRef(yaml, "ReplacementOutboundEvidence.Complete", "cardinality", "EXACT")
        assertRequiredProperties(
            yaml,
            "ExactReplacementProof",
            "compilerContext",
            "proposedBodyHash",
            "proposedBodyLength",
            "proposedBodySlice",
        )
        assertRequiredProperties(yaml, "ReplacementCompilerContext", "files", "modelGeneration")
        assertFunctionOnlyIdentity(yaml, "ReplacementPlanQuery", "target")
        assertFunctionOnlyIdentity(yaml, "ExactReplacementProof", "target")
        assertFunctionOnlyIdentity(
            yaml,
            "MutationPostconditionEvidence.Replacement",
            "resultingTarget",
        )
        assertPropertyRef(
            yaml,
            "ExactReplacementProof",
            "oldSignature",
            "ReplacementDeclarationSignature.Function",
        )
        assertPropertyRef(
            yaml,
            "ExactReplacementProof",
            "proposedSignature",
            "ReplacementDeclarationSignature.Function",
        )
        assertPropertyRef(
            yaml,
            "MutationPostconditionEvidence.Replacement",
            "signature",
            "ReplacementDeclarationSignature.Function",
        )
        assertFalse(
            yaml.contains("function or property replacement", ignoreCase = true),
            "replacement operation docs must not advertise property authority",
        )

        assertVariant(yaml, "RelationshipResultEvidence.Complete", "COMPLETE")
        assertVariant(yaml, "ReplacementOutboundEvidence.Complete", "complete")
        assertVariant(yaml, "EXACT", "EXACT")
    }

    @Test
    fun `generated replacement proof signatures validate against their OpenAPI union`() {
        val example = Json.parseToJsonElement(
            Files.readString(repoRoot().resolve("cli-rs/protocol/examples/planReplacement-response.json")),
        ).jsonObject
        val proof = example.getValue("result").jsonObject.getValue("proof").jsonObject
        val openApiSchemas = OpenApiSchemaRegistry().also(::registerOpenApiSchemas).schemas
        val schemaDocument = JsonObject(
            linkedMapOf(
                "\$schema" to JsonPrimitive("https://json-schema.org/draft/2020-12/schema"),
                "\$ref" to JsonPrimitive("#/components/schemas/ReplacementDeclarationSignature"),
                "components" to JsonObject(
                    mapOf("schemas" to openApiSchemas.toJsonElement()),
                ),
            ),
        )
        val schema = JsonSchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
            .getSchema(schemaDocument.toString(), InputFormat.JSON)

        val errors = listOf("oldSignature", "proposedSignature").flatMap { field ->
            schema.validate(proof.getValue(field).toString(), InputFormat.JSON)
                .map { error -> "$field: $error" }
        }

        assertTrue(errors.isEmpty(), errors.joinToString("\n"))
    }

    private fun assertPropertyRef(
        yaml: String,
        component: String,
        property: String,
        expectedTarget: String,
    ) {
        val schema = yaml.componentSchema(component)
        val propertyTail = schema.substringAfter("        $property:", missingDelimiterValue = "")
        val nextProperty = Regex("\n {8}[A-Za-z0-9_.]+:").find(propertyTail)?.range?.first
        val propertySection = nextProperty?.let { propertyTail.substring(0, it) } ?: propertyTail
        assertTrue(
            propertySection.contains("#/components/schemas/$expectedTarget"),
            "$component.$property must reference $expectedTarget:\n$schema",
        )
    }

    private fun assertVariant(
        yaml: String,
        component: String,
        type: String
    ) {
        val schema = yaml.componentSchema(component)
        assertTrue(schema.contains("        type:"), "$component must define the type property")
        assertTrue(schema.contains("          const: $type"), "$component must fix type to $type")
        assertTrue(schema.contains("        - type"), "$component must require the type property")
    }

    private fun assertFunctionOnlyIdentity(
        yaml: String,
        component: String,
        property: String
    ) {
        val schema = yaml.componentSchema(component)
        val propertyTail = schema.substringAfter("        $property:", missingDelimiterValue = "")
        val nextProperty = Regex("\n {8}[A-Za-z0-9_.]+:").find(propertyTail)?.range?.first
        val propertySection = nextProperty?.let { propertyTail.substring(0, it) } ?: propertyTail
        assertTrue(
            propertySection.contains("const: FUNCTION"),
            "$component.$property must constrain the shared identity to FUNCTION:\n$schema",
        )
        assertFalse(
            propertySection.contains("const: PROPERTY"),
            "$component.$property must not admit property authority:\n$schema",
        )
    }

    private fun assertRequiredProperties(
        yaml: String,
        component: String,
        vararg properties: String
    ) {
        val schema = yaml.componentSchema(component)
        val required = schema.substringAfter("      required:", missingDelimiterValue = "")
        properties.forEach { property ->
            assertTrue(
                required.contains("        - $property"),
                "$component must require $property:\n$schema",
            )
        }
    }

    private fun String.componentSchema(name: String): String {
        val start = "\n    $name:"
        val afterStart = substringAfter(start, missingDelimiterValue = "")
        require(afterStart.isNotEmpty()) { "OpenAPI component $name was not found" }
        val nextComponent = Regex("\n {4}[A-Za-z0-9_.]+:").find(afterStart)?.range?.first
        return nextComponent?.let { index -> afterStart.substring(0, index) } ?: afterStart
    }

    private fun repoRoot(): Path =
        generateSequence(Path.of("").toAbsolutePath()) { it.parent }
            .first { Files.isDirectory(it.resolve("cli-rs/protocol/examples")) }

    private fun Any?.toJsonElement(): JsonElement = when (this) {
        null -> JsonNull
        is Boolean -> JsonPrimitive(this)
        is Number -> JsonPrimitive(this)
        is String -> JsonPrimitive(this)
        is Map<*, *> -> JsonObject(entries.associate { (key, value) -> key.toString() to value.toJsonElement() })
        is Iterable<*> -> JsonArray(map { it.toJsonElement() })
        else -> error("Unsupported OpenAPI schema value: ${this::class.qualifiedName}")
    }
}
