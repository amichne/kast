package io.github.amichne.kast.api

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class AnalysisOpenApiDocumentTest {

    @Test
    fun `checked in openapi yaml matches generated document`() {
        val expected = repoRoot().resolve("docs/openapi.yaml").toFile().readText()
        val generated = AnalysisOpenApiDocument.renderYaml()
        assertEquals(expected.trimEnd(), generated.trimEnd())
    }

    @Test
    fun `spec contains a path for every AnalysisBackend JSON-RPC method`() {
        val yaml = AnalysisOpenApiDocument.renderYaml()
        val expectedMethods = listOf(
            "health",
            "runtime/status",
            "capabilities",
            "symbol/resolve",
            "references",
            "call-hierarchy",
            "type-hierarchy",
            "semantic-insertion-point",
            "diagnostics",
            "file-outline",
            "workspace-symbol",
            "workspace/files",
            "rename",
            "imports/optimize",
            "edits/apply",
            "workspace/refresh",
        )
        expectedMethods.forEach { method ->
            assertTrue(
                yaml.contains("x-jsonrpc-method: $method"),
                "Missing JSON-RPC method in spec: $method",
            )
        }
    }

    @Test
    fun `spec is valid OpenAPI 3_1 structure`() {
        val yaml = AnalysisOpenApiDocument.renderYaml()
        assertTrue(yaml.startsWith("openapi: 3.1.0"))
        assertTrue(yaml.contains("paths:"))
        assertTrue(yaml.contains("components:"))
        assertTrue(yaml.contains("schemas:"))
    }

    @Test
    fun `read and mutation operations include capability extensions`() {
        val yaml = AnalysisOpenApiDocument.renderYaml()
        val capabilities = listOf(
            "RESOLVE_SYMBOL",
            "FIND_REFERENCES",
            "CALL_HIERARCHY",
            "TYPE_HIERARCHY",
            "SEMANTIC_INSERTION_POINT",
            "DIAGNOSTICS",
            "FILE_OUTLINE",
            "WORKSPACE_SYMBOL_SEARCH",
            "WORKSPACE_FILES",
            "RENAME",
            "OPTIMIZE_IMPORTS",
            "APPLY_EDITS",
            "REFRESH_WORKSPACE",
        )
        capabilities.forEach { capability ->
            assertTrue(
                yaml.contains("x-kast-required-capability: $capability"),
                "Missing capability extension: $capability",
            )
        }
    }

    @Test
    fun `system operations have no capability requirement`() {
        val yaml = AnalysisOpenApiDocument.renderYaml()
        val lines = yaml.lines()

        // Find lines with system tag and verify no capability extension follows before next path
        val systemPaths = listOf("/rpc/health", "/rpc/runtime-status", "/rpc/capabilities")
        systemPaths.forEach { path ->
            val pathIndex = lines.indexOfFirst { it.contains("\"$path\"") || it.contains("$path:") }
            assertTrue(pathIndex >= 0, "System path $path not found")

            // Scan from pathIndex to the next path entry or end; no capability should be present
            val nextPathIndex = lines.drop(pathIndex + 1)
                .indexOfFirst { it.trimStart().startsWith("\"/rpc/") }
                .let { if (it == -1) lines.size else pathIndex + 1 + it }

            val sectionLines = lines.subList(pathIndex, nextPathIndex)
            assertTrue(
                sectionLines.none { it.contains("x-kast-required-capability") },
                "System path $path should not have a capability requirement",
            )
        }
    }

    @Test
    fun `all schema refs resolve to defined components`() {
        val yaml = AnalysisOpenApiDocument.renderYaml()
        val refRegex = Regex("""#/components/schemas/([A-Za-z0-9_.]+)""")
        val schemaDefRegex = Regex("""^ {4}([A-Za-z0-9_.]+):$""", RegexOption.MULTILINE)

        val refs = refRegex.findAll(yaml).map { it.groupValues[1] }.toSet()
        val defs = schemaDefRegex.findAll(yaml).map { it.groupValues[1] }.toSet()

        val unresolved = refs - defs
        assertTrue(unresolved.isEmpty(), "Unresolved schema refs: $unresolved")
    }

    private fun repoRoot(): Path =
        generateSequence(Path.of("").toAbsolutePath()) { current -> current.parent }
            .first { candidate -> Files.isDirectory(candidate.resolve("docs")) }
}
