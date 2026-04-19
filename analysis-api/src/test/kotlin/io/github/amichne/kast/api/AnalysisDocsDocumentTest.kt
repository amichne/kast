package io.github.amichne.kast.api

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class AnalysisDocsDocumentTest {

    @Test
    fun `checked in capabilities markdown matches generated document`() {
        val expected = repoRoot().resolve("docs/capabilities.md").toFile().readText()
        val generated = AnalysisDocsDocument.renderCapabilities()
        assertEquals(expected, generated, "docs/capabilities.md has drifted from the generator — run ./gradlew :analysis-api:generateDocPages")
    }

    @Test
    fun `checked in api-reference markdown matches generated document`() {
        val expected = repoRoot().resolve("docs/api-reference.md").toFile().readText()
        val generated = AnalysisDocsDocument.renderApiReference()
        assertEquals(expected, generated, "docs/api-reference.md has drifted from the generator — run ./gradlew :analysis-api:generateDocPages")
    }

    @Test
    fun `generated capabilities page contains a section for every JSON-RPC method`() {
        val markdown = AnalysisDocsDocument.renderCapabilities()
        val expectedMethods = OperationDocRegistry.all().map { it.jsonRpcMethod }
        expectedMethods.forEach { method ->
            assertTrue(markdown.contains("### $method"), "Missing section for $method in capabilities.md")
        }
    }

    @Test
    fun `generated api-reference page contains a section for every JSON-RPC method`() {
        val markdown = AnalysisDocsDocument.renderApiReference()
        val expectedMethods = OperationDocRegistry.all().map { it.jsonRpcMethod }
        expectedMethods.forEach { method ->
            assertTrue(markdown.contains("### $method"), "Missing section for $method in api-reference.md")
        }
    }

    @Test
    fun `every schema field in generated markdown exists in the OpenAPI spec`() {
        val yaml = AnalysisOpenApiDocument.renderYaml()
        val markdown = AnalysisDocsDocument.renderApiReference()

        // Extract field names from markdown tables (lines starting with "| `fieldName`")
        val fieldPattern = Regex("""\| `(\w+)` \|""")
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
        val yaml = AnalysisOpenApiDocument.renderYaml()
        val operationIdRegex = Regex("""operationId:\s*(\w+)""")
        val specIds = operationIdRegex.findAll(yaml).map { it.groupValues[1] }.toSet()
        val registryIds = OperationDocRegistry.operationIds()
        assertEquals(specIds, registryIds, "OperationDocRegistry does not match OpenAPI spec operations")
    }

    private fun repoRoot(): Path =
        generateSequence(Path.of("").toAbsolutePath()) { it.parent }
            .first { Files.isDirectory(it.resolve("docs")) }
}
