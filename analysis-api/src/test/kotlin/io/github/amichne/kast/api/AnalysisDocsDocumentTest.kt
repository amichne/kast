package io.github.amichne.kast.api.docs

import io.github.amichne.kast.api.contract.*
import io.github.amichne.kast.api.protocol.*

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class AnalysisDocsDocumentTest {

    @Test
    fun `checked in capabilities markdown matches generated document`() {
        val expected = repoRoot().resolve("docs/reference/capabilities.md").toFile().readText()
        val generated = DocsDocument.renderCapabilities()
        assertEquals(expected, generated, "docs/reference/capabilities.md has drifted from the generator — run ./gradlew :analysis-api:generateDocPages")
    }

    @Test
    fun `checked in api-reference markdown matches generated document`() {
        val expected = repoRoot().resolve("docs/reference/api-reference.md").toFile().readText()
        val generated = DocsDocument.renderApiReference()
        assertEquals(expected, generated, "docs/reference/api-reference.md has drifted from the generator — run ./gradlew :analysis-api:generateDocPages")
    }

    @Test
    fun `generated capabilities page contains a section for every JSON-RPC method`() {
        val markdown = DocsDocument.renderCapabilities()
        val expectedMethods = OperationDocRegistry.all().map { it.jsonRpcMethod }
        expectedMethods.forEach { method ->
            assertTrue(markdown.contains("\"$method —"), "Missing section for $method in capabilities.md")
        }
    }

    @Test
    fun `generated api-reference page contains a section for every JSON-RPC method`() {
        val markdown = DocsDocument.renderApiReference()
        val expectedMethods = OperationDocRegistry.all().map { it.jsonRpcMethod }
        expectedMethods.forEach { method ->
            assertTrue(markdown.contains("\"$method —"), "Missing section for $method in api-reference.md")
        }
    }

    @Test
    fun `every schema field in generated markdown exists in the OpenAPI spec`() {
        val yaml = OpenApiDocument.renderYaml()
        val markdown = DocsDocument.renderApiReference()

        // Extract field names from markdown tables (signature column: `#!kotlin fieldName: Type`)
        val fieldPattern = Regex("""\| `#!kotlin (\w+):""")
        val markdownFields = fieldPattern.findAll(markdown).map { it.groupValues[1] }.toSet()

        // Extract property names from OpenAPI YAML
        val yamlPropertyPattern = Regex("""^\s{8}(\w+):""", RegexOption.MULTILINE)
        val yamlFields = yamlPropertyPattern.findAll(yaml).map { it.groupValues[1] }.toSet()
            .minus(setOf("type", "description", "enum", "properties", "additionalProperties", "required", "anyOf"))

        // Every markdown field should appear in the OpenAPI spec
        val missing = markdownFields - yamlFields
        assertTrue(missing.isEmpty(), "Fields in generated markdown but not in OpenAPI spec: $missing")
    }

    @Test
    fun `OperationDocRegistry covers all OpenAPI operations`() {
        val yaml = OpenApiDocument.renderYaml()
        val operationIdRegex = Regex("""operationId:\s*(\w+)""")
        val specIds = operationIdRegex.findAll(yaml).map { it.groupValues[1] }.toSet()
        val registryIds = OperationDocRegistry.operationIds()
        assertEquals(specIds, registryIds, "OperationDocRegistry does not match OpenAPI spec operations")
    }

    /**
     * Guards against annotating server-output fields with [DocField.defaultValue] when
     * [DocField.serverManaged] should be used instead. A field is considered server-managed
     * when it is always populated by the server and never supplied by callers.
     *
     * The canonical example is [schemaVersion] on every result type.
     */
    @Test
    fun `schemaVersion fields on all registered schemas are marked serverManaged`() {
        val violations = mutableListOf<String>()
        for ((schemaName, serializer) in DocsDocument.schemaSerializersForTesting()) {
            val descriptor = serializer.descriptor
            repeat(descriptor.elementsCount) { index ->
                if (descriptor.getElementName(index) == "schemaVersion") {
                    val docField = descriptor.getElementAnnotations(index)
                        .filterIsInstance<DocField>()
                        .firstOrNull()
                    if (docField?.serverManaged != true) {
                        violations.add("$schemaName.schemaVersion is missing @DocField(serverManaged = true)")
                    }
                }
            }
        }
        assertTrue(violations.isEmpty(), "Server-managed fields must use @DocField(serverManaged = true):\n${violations.joinToString("\n")}")
    }

    /**
     * Guards against using bare Kotlin constant names (e.g. SCHEMA_VERSION) as [DocField.defaultValue].
     * Such values render as meaningless tooltips in the generated docs. Use [DocField.serverManaged]
     * for server-populated fields, or provide a concrete literal value for user-configurable defaults.
     *
     * Enum-typed fields are exempted — their defaults are valid all-caps enum member names (e.g. BOTH, SUPERTYPES).
     */
    @Test
    fun `no DocField defaultValue looks like a bare constant name`() {
        val allCapsConstant = Regex("""^[A-Z][A-Z_0-9]{3,}${'$'}""")
        val violations = mutableListOf<String>()
        for ((schemaName, serializer) in DocsDocument.schemaSerializersForTesting()) {
            val descriptor = serializer.descriptor
            repeat(descriptor.elementsCount) { index ->
                val elementDescriptor = descriptor.getElementDescriptor(index)
                // Enum defaults are legitimately all-caps (e.g. BOTH, SUPERTYPES)
                if (elementDescriptor.kind == kotlinx.serialization.descriptors.SerialKind.ENUM) continue
                val docField = descriptor.getElementAnnotations(index)
                    .filterIsInstance<DocField>()
                    .firstOrNull() ?: continue
                val dv = docField.defaultValue
                if (dv.isNotBlank() && allCapsConstant.matches(dv)) {
                    violations.add("$schemaName.${descriptor.getElementName(index)}: defaultValue=\"$dv\" looks like a constant — use a literal value or serverManaged = true")
                }
            }
        }
        assertTrue(violations.isEmpty(), "Bare constant names in @DocField.defaultValue:\n${violations.joinToString("\n")}")
    }

    private fun repoRoot(): Path =
        generateSequence(Path.of("").toAbsolutePath()) { it.parent }
            .first { Files.isDirectory(it.resolve("docs")) }
}
