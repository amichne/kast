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
            assertTrue(markdown.contains("### $method"), "Missing section for $method in capabilities.md")
        }
    }

    @Test
    fun `generated api-reference page contains a section for every JSON-RPC method`() {
        val markdown = DocsDocument.renderApiReference()
        val expectedMethods = OperationDocRegistry.all().map { it.jsonRpcMethod }
        expectedMethods.forEach { method ->
            assertTrue(markdown.contains("### $method"), "Missing section for $method in api-reference.md")
        }
    }

    @Test
    fun `every schema field in generated markdown exists in the OpenAPI spec`() {
        val yaml = OpenApiDocument.renderYaml()
        val markdown = DocsDocument.renderApiReference()

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
        val yaml = OpenApiDocument.renderYaml()
        val operationIdRegex = Regex("""operationId:\s*(\w+)""")
        val specIds = operationIdRegex.findAll(yaml).map { it.groupValues[1] }.toSet()
        val registryIds = OperationDocRegistry.operationIds()
        assertEquals(specIds, registryIds, "OperationDocRegistry does not match OpenAPI spec operations")
    }

    @Test
    fun `every operation in api-reference includes ordered examples tabs`() {
        val markdown = DocsDocument.renderApiReference()
        val expectedMethods = OperationDocRegistry.all().map { it.jsonRpcMethod }

        expectedMethods.forEach { method ->
            val sectionMatch = Regex("""(?ms)^### ${Regex.escape(method)}\n(.*?)(?=^### |\z)""").find(markdown)
            assertTrue(sectionMatch != null, "Missing section for $method in api-reference.md")

            val section = sectionMatch!!.groupValues[1]
            val examplesHeadingIndex = section.indexOf("#### Examples")
            assertTrue(examplesHeadingIndex >= 0, "Missing #### Examples section for $method")

            val examplesSection = section.substring(examplesHeadingIndex)
            val cliTabIndex = examplesSection.indexOf("=== \"CLI example\"")
            val requestTabIndex = examplesSection.indexOf("=== \"JSON-RPC request\"")
            val responseTabIndex = examplesSection.indexOf("=== \"Example response\"")

            assertTrue(cliTabIndex >= 0, "Missing CLI example tab for $method")
            assertTrue(requestTabIndex > cliTabIndex, "JSON-RPC request tab must come after CLI example for $method")
            assertTrue(responseTabIndex > requestTabIndex, "Example response tab must come after JSON-RPC request for $method")
        }
    }

    private fun repoRoot(): Path =
        generateSequence(Path.of("").toAbsolutePath()) { it.parent }
            .first { Files.isDirectory(it.resolve("docs")) }
}
