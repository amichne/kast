package io.github.amichne.kast.api.docs

import io.github.amichne.kast.api.contract.query.WorkspaceFilesPublicContinuationIdentity
import io.github.amichne.kast.api.contract.result.WorkspaceFilesPublicContinuationState
import io.github.amichne.kast.api.validation.WorkspaceFilesPublicPageToken
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class AnalysisOpenApiDocumentTest {

    @Test
    fun `checked in openapi yaml matches generated document`() {
        val expected = repoRoot().resolve("cli-rs/protocol/openapi.yaml").toFile().readText()
        val generated = OpenApiDocument.renderYaml()
        assertEquals(expected.trimEnd(), generated.trimEnd())
    }

    @Test
    fun `spec contains a path for every AnalysisBackend JSON-RPC method`() {
        val yaml = OpenApiDocument.renderYaml()
        val expectedMethods = listOf(
            "health",
            "runtime/status",
            "runtime/shutdown",
            "runtime/restart",
            "capabilities",
            "raw/resolve",
            "raw/references",
            "raw/call-hierarchy",
            "raw/type-hierarchy",
            "raw/semantic-insertion-point",
            "raw/diagnostics",
            "raw/file-outline",
            "raw/workspace-symbol",
            "raw/workspace-files",
            "raw/workspace-files-continuation",
            "raw/implementations",
            "raw/code-actions",
            "raw/completions",
            "raw/rename",
            "raw/optimize-imports",
            "raw/apply-edits",
            "raw/workspace-refresh",
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
        val yaml = OpenApiDocument.renderYaml()
        assertTrue(yaml.startsWith("openapi: 3.1.0"))
        assertTrue(yaml.contains("paths:"))
        assertTrue(yaml.contains("components:"))
        assertTrue(yaml.contains("schemas:"))
    }

    @Test
    fun `read and mutation operations include capability extensions`() {
        val yaml = OpenApiDocument.renderYaml()
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
            "IMPLEMENTATIONS",
            "CODE_ACTIONS",
            "COMPLETIONS",
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
        val yaml = OpenApiDocument.renderYaml()
        val lines = yaml.lines()

        // Find lines with system tag and verify no capability extension follows before next path
        val systemPaths = listOf(
            "/rpc/health",
            "/rpc/runtime-status",
            "/rpc/runtime-shutdown",
            "/rpc/runtime-restart",
            "/rpc/capabilities",
        )
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
    fun `workspace file continuation is internal and not capability gated`() {
        val yaml = OpenApiDocument.renderYaml()
        val lines = yaml.lines()
        val path = "/rpc/raw/workspace-files-continuation"
        val pathIndex = lines.indexOfFirst { it.contains(path) }

        assertTrue(pathIndex >= 0, "Internal continuation path $path not found")

        val nextPathIndex = lines.drop(pathIndex + 1)
            .indexOfFirst { it.trimStart().startsWith("\"/rpc/") }
            .let { if (it == -1) lines.size else pathIndex + 1 + it }
        val sectionLines = lines.subList(pathIndex, nextPathIndex)

        assertTrue(
            sectionLines.none { it.contains("x-kast-required-capability") },
            "Internal workspace-file continuation must not advertise a backend capability",
        )
        assertTrue(sectionLines.any { it.contains("WorkspaceFilesContinuationQuery") })
        assertTrue(sectionLines.any { it.contains("WorkspaceFilesContinuationResult") })
    }

    @Test
    fun `workspace file continuation schemas preserve disjoint wire variants`() {
        val yaml = OpenApiDocument.renderYaml()
        val request = yaml.componentSchema("WorkspaceFilesContinuationQuery")
        val issue = yaml.componentSchema("WorkspaceFilesContinuationQuery.Issue")
        val consume = yaml.componentSchema("WorkspaceFilesContinuationQuery.Consume")
        val result = yaml.componentSchema("WorkspaceFilesContinuationResult")
        val issued = yaml.componentSchema("WorkspaceFilesContinuationResult.Issued")
        val consumed = yaml.componentSchema("WorkspaceFilesContinuationResult.Consumed")

        assertTrue(request.contains("oneOf:"))
        assertTrue(request.contains("propertyName: action"))
        assertTrue(request.contains("ISSUE: \"#/components/schemas/WorkspaceFilesContinuationQuery.Issue\""))
        assertTrue(request.contains("CONSUME: \"#/components/schemas/WorkspaceFilesContinuationQuery.Consume\""))
        assertTrue(issue.contains("const: ISSUE"))
        assertTrue(issue.contains("- state"))
        assertFalse(issue.contains("pageToken:"))
        assertTrue(consume.contains("const: CONSUME"))
        assertTrue(consume.contains("- pageToken"))
        assertFalse(consume.contains("state:"))

        assertTrue(result.contains("oneOf:"))
        assertTrue(result.contains("propertyName: type"))
        assertTrue(result.contains("ISSUED: \"#/components/schemas/WorkspaceFilesContinuationResult.Issued\""))
        assertTrue(result.contains("CONSUMED: \"#/components/schemas/WorkspaceFilesContinuationResult.Consumed\""))
        assertTrue(issued.contains("const: ISSUED"))
        assertTrue(issued.contains("- pageToken"))
        assertTrue(consumed.contains("const: CONSUMED"))
        assertTrue(consumed.contains("- state"))
    }

    @Test
    fun `runtime compatibility schemas preserve typed revisions and disjoint outcomes`() {
        val yaml = OpenApiDocument.renderYaml()
        val facts = yaml.componentSchema("RuntimeCompatibilityFacts")
        val capability = yaml.componentSchema("RuntimeCapability")
        val requirement = yaml.componentSchema("RuntimeCompatibilityUpdateRequirement")
        val unsupportedProtocol = yaml.componentSchema(
            "RuntimeCompatibilityUpdateRequirement.UnsupportedProtocolRevision",
        )
        val outcome = yaml.componentSchema("RuntimeCompatibilityOutcome")

        assertTrue(facts.contains("ProtocolRevision"))
        assertTrue(facts.contains("WorkspaceMetadataRevision"))
        assertTrue(facts.contains("RuntimeIdentity"))
        assertTrue(facts.contains("uniqueItems: true"))
        assertTrue(yaml.componentSchema("ProtocolRevision").contains("minimum: 1"))
        assertTrue(yaml.componentSchema("WorkspaceMetadataRevision").contains("minimum: 1"))
        assertTrue(yaml.componentSchema("PluginImplementationVersion").contains("minLength: 1"))
        assertTrue(yaml.componentSchema("PluginImplementationVersion").contains("pattern: ^\\S+${'$'}"))

        assertTrue(capability.contains("oneOf:"))
        assertTrue(capability.contains("propertyName: type"))
        assertTrue(capability.contains("READ: \"#/components/schemas/RuntimeCapability.Read\""))
        assertTrue(capability.contains("MUTATION: \"#/components/schemas/RuntimeCapability.Mutation\""))

        assertTrue(requirement.contains("oneOf:"))
        assertTrue(requirement.contains("UNSUPPORTED_PROTOCOL_REVISION"))
        assertTrue(requirement.contains("UNSUPPORTED_WORKSPACE_METADATA_REVISION"))
        assertTrue(requirement.contains("MISSING_REQUIRED_CAPABILITY"))
        assertTrue(unsupportedProtocol.contains("minItems: 1"))

        assertTrue(outcome.contains("oneOf:"))
        assertTrue(outcome.contains("COMPATIBLE: \"#/components/schemas/RuntimeCompatibilityOutcome.Compatible\""))
        assertTrue(outcome.contains("UPDATE_REQUIRED"))
        assertTrue(outcome.contains("MISSING_CAPABILITY"))
    }

    @Test
    fun `workspace file continuation inline schemas match scalar wire values and constraints`() {
        val identity = WorkspaceFilesPublicContinuationIdentity(
            workspaceRoot = WorkspaceFilesPublicContinuationIdentity.WorkspaceRoot.parse("/workspace"),
            backendName = WorkspaceFilesPublicContinuationIdentity.BackendName.parse("idea"),
            normalizedQuery = WorkspaceFilesPublicContinuationIdentity.NormalizedQuery.parse("kind=source"),
            projection = WorkspaceFilesPublicContinuationIdentity.Projection.parse("compact:path"),
            limit = WorkspaceFilesPublicContinuationIdentity.Limit.of(20),
        )
        val state = WorkspaceFilesPublicContinuationState(
            identity = identity,
            compositionStampDigest =
                WorkspaceFilesPublicContinuationState.CompositionStampDigest.parse("a".repeat(64)),
            lastRelativePath = WorkspaceFilesPublicContinuationState.LastRelativePath.parse("src/App.kt"),
            cumulativeReturnedCount = WorkspaceFilesPublicContinuationState.CumulativeReturnedCount.of(20),
        )
        val identityWire = Json.encodeToJsonElement(
            WorkspaceFilesPublicContinuationIdentity.serializer(),
            identity,
        ).jsonObject
        val stateWire = Json.encodeToJsonElement(
            WorkspaceFilesPublicContinuationState.serializer(),
            state,
        ).jsonObject
        val tokenWire = Json.encodeToJsonElement(
            WorkspaceFilesPublicPageToken.serializer(),
            WorkspaceFilesPublicPageToken.parse("00000000-0000-4000-8000-000000000001"),
        )

        identityWire.values.forEach { value -> assertTrue(value is JsonPrimitive) }
        stateWire.scalar("compositionStampDigest")
        stateWire.scalar("lastRelativePath")
        stateWire.scalar("cumulativeReturnedCount")
        assertTrue(tokenWire is JsonPrimitive)

        val yaml = OpenApiDocument.renderYaml()
        assertScalarSchema(yaml, "WorkspaceRoot", "string")
        assertScalarSchema(yaml, "BackendName", "string")
        assertScalarSchema(yaml, "NormalizedQuery", "string")
        assertScalarSchema(yaml, "Projection", "string")
        assertScalarSchema(yaml, "Limit", "integer", "minimum: 1", "maximum: 200")
        assertScalarSchema(
            yaml,
            "CompositionStampDigest",
            "string",
            "pattern: \"^[0-9a-f]{64}\$\"",
        )
        assertScalarSchema(yaml, "LastRelativePath", "string", "minLength: 1", "pattern:")
        val pathPatternLine = yaml.componentSchema("LastRelativePath").lineSequence()
            .single { line -> "pattern:" in line }
        assertEquals(
            6,
            pathPatternLine.substringBefore("u0000").takeLastWhile { character -> character == '\\' }.length,
            "YAML must preserve the regex escapes for a literal backslash and the control-character range",
        )
        assertScalarSchema(yaml, "CumulativeReturnedCount", "integer", "minimum: 0")
        assertScalarSchema(yaml, "WorkspaceFilesPublicPageToken", "string", "format: uuid", "pattern:")
    }

    @Test
    fun `all schema refs resolve to defined components`() {
        val yaml = OpenApiDocument.renderYaml()
        val refRegex = Regex("""#/components/schemas/([A-Za-z0-9_.]+)""")
        val schemaDefRegex = Regex("""^ {4}([A-Za-z0-9_.]+):$""", RegexOption.MULTILINE)

        val refs = refRegex.findAll(yaml).map { it.groupValues[1] }.toSet()
        val defs = schemaDefRegex.findAll(yaml).map { it.groupValues[1] }.toSet()

        val unresolved = refs - defs
        assertTrue(unresolved.isEmpty(), "Unresolved schema refs: $unresolved")
    }

    @Test
    fun `result cardinality schema preserves exact and known minimum wire variants`() {
        val yaml = OpenApiDocument.renderYaml()

        assertTrue(yaml.contains("ResultCardinality:\n      oneOf:"))
        assertTrue(yaml.contains("const: EXACT"))
        assertTrue(yaml.contains("const: KNOWN_MINIMUM"))
        assertTrue(yaml.contains("knownMinimumCount:"))
        assertTrue(yaml.contains("totalCount:"))
    }

    @Test
    fun `diagnostics schema admits only exact cardinality`() {
        val yaml = OpenApiDocument.renderYaml()
        val diagnosticsSchema = yaml.substringAfter("    DiagnosticsResult:").substringBefore("    Diagnostic:")

        assertTrue(diagnosticsSchema.contains("cardinality:\n          \"\$ref\": \"#/components/schemas/EXACT\""))
    }

    private fun repoRoot(): Path =
        generateSequence(Path.of("").toAbsolutePath()) { current -> current.parent }
            .first { candidate -> Files.isDirectory(candidate.resolve("docs")) }

    private fun String.componentSchema(name: String): String {
        val start = "    $name:"
        val afterStart = substringAfter(start, missingDelimiterValue = "")
        require(afterStart.isNotEmpty()) { "OpenAPI component $name was not found" }
        val nextComponent = Regex("\\n {4}[A-Za-z0-9_.]+:").find(afterStart)?.range?.first
        return nextComponent?.let { index -> afterStart.substring(0, index) } ?: afterStart
    }

    private fun JsonObject.scalar(name: String) {
        assertTrue(getValue(name) is JsonPrimitive, "$name must serialize as a scalar")
    }

    private fun assertScalarSchema(
        yaml: String,
        name: String,
        type: String,
        vararg expectedConstraints: String,
    ) {
        val schema = yaml.componentSchema(name)
        assertTrue(schema.contains("type: $type"), "$name must be a $type schema")
        assertFalse(schema.contains("properties:"), "$name must not expose an inline value wrapper")
        expectedConstraints.forEach { constraint ->
            assertTrue(schema.contains(constraint), "$name must include $constraint")
        }
    }
}
