@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package io.github.amichne.kast.api.docs

import io.github.amichne.kast.api.contract.*
import io.github.amichne.kast.api.contract.query.ApplyEditsQuery
import io.github.amichne.kast.api.contract.query.CallHierarchyQuery
import io.github.amichne.kast.api.contract.query.CodeActionsQuery
import io.github.amichne.kast.api.contract.query.CompletionsQuery
import io.github.amichne.kast.api.contract.query.DiagnosticsQuery
import io.github.amichne.kast.api.contract.query.FileOutlineQuery
import io.github.amichne.kast.api.contract.query.ImplementationsQuery
import io.github.amichne.kast.api.contract.query.ImportOptimizeQuery
import io.github.amichne.kast.api.contract.query.ReferencesQuery
import io.github.amichne.kast.api.contract.query.RefreshQuery
import io.github.amichne.kast.api.contract.query.RenameQuery
import io.github.amichne.kast.api.contract.query.SymbolQuery
import io.github.amichne.kast.api.contract.query.TypeHierarchyQuery
import io.github.amichne.kast.api.contract.query.WorkspaceFilesQuery
import io.github.amichne.kast.api.contract.query.WorkspaceSymbolQuery
import io.github.amichne.kast.api.contract.result.ApplyEditsResult
import io.github.amichne.kast.api.contract.result.CallHierarchyResult
import io.github.amichne.kast.api.contract.result.CallHierarchyStats
import io.github.amichne.kast.api.contract.result.CodeAction
import io.github.amichne.kast.api.contract.result.CodeActionsResult
import io.github.amichne.kast.api.contract.result.CompletionItem
import io.github.amichne.kast.api.contract.result.CompletionsResult
import io.github.amichne.kast.api.contract.result.DiagnosticsResult
import io.github.amichne.kast.api.contract.result.FileOutlineResult
import io.github.amichne.kast.api.contract.result.ImplementationsResult
import io.github.amichne.kast.api.contract.result.ImportOptimizeResult
import io.github.amichne.kast.api.contract.result.ReferencesResult
import io.github.amichne.kast.api.contract.result.RefreshResult
import io.github.amichne.kast.api.contract.result.RenameResult
import io.github.amichne.kast.api.contract.result.SymbolResult
import io.github.amichne.kast.api.contract.result.TypeHierarchyNode
import io.github.amichne.kast.api.contract.result.TypeHierarchyResult
import io.github.amichne.kast.api.contract.result.TypeHierarchyStats
import io.github.amichne.kast.api.contract.result.TypeHierarchyTruncation
import io.github.amichne.kast.api.contract.result.WorkspaceFilesResult
import io.github.amichne.kast.api.contract.result.WorkspaceModule
import io.github.amichne.kast.api.contract.result.WorkspaceSymbolResult
import io.github.amichne.kast.api.protocol.*

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.StructureKind
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Ensures every non-optional property on registered schema classes carries
 * a [DocField] annotation with a non-blank description.
 *
 * This prevents new fields from being added to API models without documentation.
 * The serializer list mirrors [OpenApiDocument.registerSchemas] — any
 * class registered there must appear here.
 */
class DocFieldCoverageTest {

    /**
     * All serializers registered in [OpenApiDocument.registerSchemas].
     * Enums are excluded because they don't have documentable properties.
     */
    private val registeredSerializers: List<Pair<String, KSerializer<*>>> = listOf(
        // JSON-RPC error envelope
        "ApiErrorResponse" to ApiErrorResponse.serializer(),

        // System responses
        "HealthResponse" to HealthResponse.serializer(),
        "RuntimeStatusResponse" to RuntimeStatusResponse.serializer(),
        "BackendCapabilities" to BackendCapabilities.serializer(),

        // Shared types
        "FilePosition" to FilePosition.serializer(),
        "Location" to Location.serializer(),
        "Symbol" to Symbol.serializer(),
        "ParameterInfo" to ParameterInfo.serializer(),
        "PageInfo" to PageInfo.serializer(),
        "SearchScope" to SearchScope.serializer(),
        "DeclarationScope" to DeclarationScope.serializer(),
        "ServerLimits" to ServerLimits.serializer(),
        "TextEdit" to TextEdit.serializer(),
        "FileHash" to FileHash.serializer(),
        "OutlineSymbol" to OutlineSymbol.serializer(),
        "WorkspaceModule" to WorkspaceModule.serializer(),

        // Read queries & results
        "SymbolQuery" to SymbolQuery.serializer(),
        "SymbolResult" to SymbolResult.serializer(),
        "ReferencesQuery" to ReferencesQuery.serializer(),
        "ReferencesResult" to ReferencesResult.serializer(),
        "CallHierarchyQuery" to CallHierarchyQuery.serializer(),
        "CallHierarchyResult" to CallHierarchyResult.serializer(),
        "CallHierarchyStats" to CallHierarchyStats.serializer(),
        "CallNode" to CallNode.serializer(),
        "CallNodeTruncation" to CallNodeTruncation.serializer(),
        "TypeHierarchyQuery" to TypeHierarchyQuery.serializer(),
        "TypeHierarchyResult" to TypeHierarchyResult.serializer(),
        "TypeHierarchyNode" to TypeHierarchyNode.serializer(),
        "TypeHierarchyStats" to TypeHierarchyStats.serializer(),
        "TypeHierarchyTruncation" to TypeHierarchyTruncation.serializer(),
        "SemanticInsertionQuery" to SemanticInsertionQuery.serializer(),
        "SemanticInsertionResult" to SemanticInsertionResult.serializer(),
        "DiagnosticsQuery" to DiagnosticsQuery.serializer(),
        "DiagnosticsResult" to DiagnosticsResult.serializer(),
        "Diagnostic" to Diagnostic.serializer(),
        "FileOutlineQuery" to FileOutlineQuery.serializer(),
        "FileOutlineResult" to FileOutlineResult.serializer(),
        "WorkspaceSymbolQuery" to WorkspaceSymbolQuery.serializer(),
        "WorkspaceSymbolResult" to WorkspaceSymbolResult.serializer(),
        "WorkspaceFilesQuery" to WorkspaceFilesQuery.serializer(),
        "WorkspaceFilesResult" to WorkspaceFilesResult.serializer(),
        "ImplementationsQuery" to ImplementationsQuery.serializer(),
        "ImplementationsResult" to ImplementationsResult.serializer(),
        "CodeActionsQuery" to CodeActionsQuery.serializer(),
        "CodeActionsResult" to CodeActionsResult.serializer(),
        "CodeAction" to CodeAction.serializer(),
        "CompletionsQuery" to CompletionsQuery.serializer(),
        "CompletionsResult" to CompletionsResult.serializer(),
        "CompletionItem" to CompletionItem.serializer(),

        // Mutation queries & results
        "RenameQuery" to RenameQuery.serializer(),
        "RenameResult" to RenameResult.serializer(),
        "ImportOptimizeQuery" to ImportOptimizeQuery.serializer(),
        "ImportOptimizeResult" to ImportOptimizeResult.serializer(),
        "ApplyEditsQuery" to ApplyEditsQuery.serializer(),
        "ApplyEditsResult" to ApplyEditsResult.serializer(),
        "RefreshQuery" to RefreshQuery.serializer(),
        "RefreshResult" to RefreshResult.serializer(),

        // FileOperation sealed hierarchy subtypes
        "FileOperation.CreateFile" to FileOperation.CreateFile.serializer(),
        "FileOperation.DeleteFile" to FileOperation.DeleteFile.serializer(),
    )

    @Test
    fun `every registered schema property has a DocField annotation with non-blank description`() {
        val violations = mutableListOf<String>()

        for ((name, serializer) in registeredSerializers) {
            val descriptor = serializer.descriptor
            if (descriptor.kind != StructureKind.CLASS && descriptor.kind != StructureKind.OBJECT) continue

            repeat(descriptor.elementsCount) { index ->
                val fieldName = descriptor.getElementName(index)
                val annotations = descriptor.getElementAnnotations(index)
                val docField = annotations.filterIsInstance<DocField>().firstOrNull()
                if (docField == null) {
                    violations += "$name.$fieldName: missing @DocField annotation"
                } else if (docField.description.isBlank()) {
                    violations += "$name.$fieldName: @DocField has blank description"
                }
            }
        }

        assertTrue(violations.isEmpty()) {
            "Found ${violations.size} undocumented properties:\n${violations.joinToString("\n") { "  • $it" }}"
        }
    }

    @Test
    fun `registered serializers list matches OpenApiDocument schema count`() {
        // Verify this test covers the same schemas as the OpenAPI generator.
        // The OpenAPI generator registers enums inline so they don't need DocField.
        // Count only CLASS/OBJECT descriptors from the OpenAPI spec.
        val yaml = OpenApiDocument.renderYaml()
        val schemaDefRegex = Regex("""^ {4}([A-Za-z0-9_.]+):$""", RegexOption.MULTILINE)
        val allSchemaNames = schemaDefRegex.findAll(yaml).map { it.groupValues[1] }.toSet()

        // Filter to only schemas that have properties (i.e., object schemas, not enums)
        val objectSchemas = allSchemaNames.filter { name ->
            val schemaSection = extractSchemaSection(yaml, name)
            schemaSection.contains("\"type\": \"object\"") || schemaSection.contains("type: object")
        }.toSet()

        val testSchemaNames = registeredSerializers.map { it.first }.toSet()

        // Exclude wire-level JSON-RPC envelope types (not public API models)
        // and the top-level FileOperation (sealed interface; subtypes are tested individually)
        val wireTypes = setOf(
            "JsonRpcErrorObject", "JsonRpcErrorResponse",
            "JsonRpcRequest", "JsonRpcSuccessResponse",
            "FileOperation",
        )
        val expected = objectSchemas - wireTypes

        val missing = expected - testSchemaNames
        assertTrue(missing.isEmpty()) {
            "Schemas in OpenAPI spec but not in DocFieldCoverageTest: $missing"
        }
    }

    private fun extractSchemaSection(yaml: String, schemaName: String): String {
        val lines = yaml.lines()
        val startIdx = lines.indexOfFirst { it.trimStart().startsWith("$schemaName:") && it.startsWith("    ") }
        if (startIdx == -1) return ""
        val endIdx = lines.drop(startIdx + 1).indexOfFirst {
            it.isNotBlank() && !it.startsWith("      ") && !it.startsWith("        ")
        }.let { if (it == -1) lines.size else startIdx + 1 + it }
        return lines.subList(startIdx, endIdx).joinToString("\n")
    }
}
