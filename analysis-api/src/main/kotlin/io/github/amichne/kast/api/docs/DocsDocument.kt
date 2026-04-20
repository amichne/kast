@file:OptIn(ExperimentalSerializationApi::class)

package io.github.amichne.kast.api.docs

import io.github.amichne.kast.api.contract.*
import io.github.amichne.kast.api.protocol.*

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.StructureKind
import java.nio.file.Files
import java.nio.file.Path

/**
 * Generates Markdown documentation pages for the Kast analysis daemon API.
 *
 * Walks the same operation and schema registries as [OpenApiDocument],
 * reads [@DocField][DocField] annotations from serialization descriptors,
 * and pulls editorial prose from [OperationDocRegistry].
 *
 * Produces two tiers:
 * - **Capabilities** (`renderCapabilities`): overview with collapsed schema tables
 * - **API reference** (`renderApiReference`): expanded detail with examples and notes
 *
 * Generated pages are checked in and validated by `AnalysisDocsDocumentTest`.
 */
object DocsDocument {

    // ── Schema name → serializer mapping ──────────────────────────────

    private val schemaSerializers: Map<String, KSerializer<*>> = mapOf(
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
        // FileOperation sealed hierarchy
        "FileOperation" to FileOperation.serializer(),
        "FileOperation.CreateFile" to FileOperation.CreateFile.serializer(),
        "FileOperation.DeleteFile" to FileOperation.DeleteFile.serializer(),
    )

    // ── Public render methods ─────────────────────────────────────────

    fun renderCapabilities(): String = buildString {
        appendLine("---")
        appendLine("title: Capabilities")
        appendLine("---")
        appendLine()
        appendLine("# Capabilities")
        appendLine()
        appendLine("Every operation the Kast analysis daemon supports, organized by")
        appendLine("category. Expand any operation to see its input and output schemas.")
        appendLine()

        val ops = OperationDocRegistry.all().toList()
        for (tag in listOf("system", "read", "mutation")) {
            val tagOps = ops.filter { it.tag == tag }
            if (tagOps.isEmpty()) continue
            appendLine("## ${tagDisplayName(tag)}")
            appendLine()
            for (op in tagOps) {
                appendCapabilitiesOperation(op)
            }
        }
    }.trimEnd() + "\n"

    fun renderApiReference(): String = buildString {
        appendLine("---")
        appendLine("title: API reference")
        appendLine("---")
        appendLine()
        appendLine("# API reference")
        appendLine()
        appendLine("Complete reference for every JSON-RPC method in the Kast analysis")
        appendLine("daemon, including input/output schemas, examples, and behavioral notes.")
        appendLine()

        val ops = OperationDocRegistry.all().toList()
        for (tag in listOf("system", "read", "mutation")) {
            val tagOps = ops.filter { it.tag == tag }
            if (tagOps.isEmpty()) continue
            appendLine("## ${tagDisplayName(tag)}")
            appendLine()
            for (op in tagOps) {
                appendApiReferenceOperation(op)
            }
        }
    }.trimEnd() + "\n"

    // ── Per-operation renderers ───────────────────────────────────────

    private fun StringBuilder.appendCapabilitiesOperation(op: OperationDoc) {
        appendLine("### ${op.jsonRpcMethod}")
        appendLine()
        appendLine(op.summary + ".")
        appendLine()
        appendBadgeLine(op)
        appendLine()

        // Collapsed input schema
        if (op.requestSchema != null) {
            appendLine("??? info \"Input: ${op.requestSchema}\"")
            appendLine()
            appendSchemaTable(op.requestSchema, indent = "    ")
            appendLine()
        }

        // Collapsed output schema
        appendLine("??? info \"Output: ${op.responseSchema}\"")
        appendLine()
        appendSchemaTable(op.responseSchema, indent = "    ")
        appendLine()
    }

    private fun StringBuilder.appendApiReferenceOperation(op: OperationDoc) {
        appendLine("### ${op.jsonRpcMethod}")
        appendLine()
        appendLine(op.description)
        appendLine()
        appendBadgeLine(op)
        appendLine()

        // Expanded input schema
        if (op.requestSchema != null) {
            appendLine("#### Input: ${op.requestSchema}")
            appendLine()
            appendSchemaTable(op.requestSchema, indent = "")
            appendLine()
        }

        // Expanded output schema
        appendLine("#### Output: ${op.responseSchema}")
        appendLine()
        appendSchemaTable(op.responseSchema, indent = "")
        appendLine()

        // Tabbed examples
        appendTabbedExamples(op)

        // Behavioral notes
        if (op.behavioralNotes.isNotEmpty()) {
            appendLine("!!! note \"Behavioral notes\"")
            appendLine()
            for (note in op.behavioralNotes) {
                appendLine("    - $note")
            }
            appendLine()
        }

        // Error codes
        if (op.errorCodes.isNotEmpty()) {
            appendLine("**Error codes:** ${op.errorCodes.joinToString(", ") { "`$it`" }}")
            appendLine()
        }
    }

    // ── Badge line ────────────────────────────────────────────────────

    private fun StringBuilder.appendBadgeLine(op: OperationDoc) {
        val parts = mutableListOf<String>()
        if (op.capability != null) {
            parts += "**Capability:** `${op.capability}`"
        }
        parts += "**Category:** ${op.tag}"
        parts += "**JSON-RPC method:** `${op.jsonRpcMethod}`"
        appendLine(parts.joinToString(" | "))
    }

    // ── Schema table rendering ────────────────────────────────────────

    private fun StringBuilder.appendSchemaTable(schemaName: String, indent: String) {
        val serializer = schemaSerializers[schemaName]
        if (serializer == null) {
            appendLine("${indent}*Schema not found: $schemaName*")
            return
        }
        val descriptor = serializer.descriptor
        if (descriptor.elementsCount == 0) {
            appendLine("${indent}*No fields.*")
            return
        }

        appendLine("${indent}| Field | Type | Required | Description |")
        appendLine("${indent}|-------|------|----------|-------------|")
        repeat(descriptor.elementsCount) { index ->
            val name = descriptor.getElementName(index)
            val elementDescriptor = descriptor.getElementDescriptor(index)
            val typeName = resolveTypeName(elementDescriptor)
            val required = if (!descriptor.isElementOptional(index)) "✓" else ""
            val docField = descriptor.getElementAnnotations(index)
                .filterIsInstance<DocField>()
                .firstOrNull()
            val description = docField?.description?.ifBlank { "" } ?: ""
            appendLine("${indent}| `$name` | `$typeName` | $required | $description |")
        }
    }

    // ── Tabbed examples ───────────────────────────────────────────────

    private fun StringBuilder.appendTabbedExamples(op: OperationDoc) {
        val hasCliExample = op.cliExample.isNotBlank()
        val fixtureId = op.exampleFixtureId.ifBlank { op.operationId }
        val requestJson = readExampleFile("$fixtureId-request.json")
        val responseJson = readExampleFile("$fixtureId-response.json")

        if (!hasCliExample && requestJson == null && responseJson == null) return

        appendLine("#### Examples")
        appendLine()

        if (hasCliExample) {
            appendLine("=== \"CLI example\"")
            appendLine()
            appendLine("    ```bash")
            appendLine("    ${op.cliExample}")
            appendLine("    ```")
            appendLine()
        }

        if (requestJson != null) {
            appendLine("=== \"JSON-RPC request\"")
            appendLine()
            appendLine("    ```json")
            for (line in requestJson.lines()) {
                appendLine("    $line")
            }
            appendLine("    ```")
            appendLine()
        }

        if (responseJson != null) {
            appendLine("=== \"Example response\"")
            appendLine()
            appendLine("    ```json")
            for (line in responseJson.lines()) {
                appendLine("    $line")
            }
            appendLine("    ```")
            appendLine()
        }
    }

    // ── Type name resolution ──────────────────────────────────────────

    private fun resolveTypeName(descriptor: SerialDescriptor): String {
        val base = when (descriptor.kind) {
            PrimitiveKind.STRING -> "String"
            PrimitiveKind.INT -> "Int"
            PrimitiveKind.LONG -> "Long"
            PrimitiveKind.BOOLEAN -> "Boolean"
            PrimitiveKind.DOUBLE -> "Double"
            PrimitiveKind.FLOAT -> "Float"
            PrimitiveKind.BYTE -> "Byte"
            PrimitiveKind.SHORT -> "Short"
            PrimitiveKind.CHAR -> "Char"
            StructureKind.LIST -> {
                val elementType = resolveTypeName(descriptor.getElementDescriptor(0))
                "List<$elementType>"
            }
            StructureKind.MAP -> {
                val keyType = resolveTypeName(descriptor.getElementDescriptor(0))
                val valueType = resolveTypeName(descriptor.getElementDescriptor(1))
                "Map<$keyType, $valueType>"
            }
            SerialKind.ENUM -> simpleName(descriptor.serialName)
            else -> simpleName(descriptor.serialName)
        }
        return if (descriptor.isNullable) "$base?" else base
    }

    private fun simpleName(serialName: String): String =
        serialName.removeSuffix("?").substringAfterLast('.')

    // ── Helpers ───────────────────────────────────────────────────────

    private fun tagDisplayName(tag: String): String = when (tag) {
        "system" -> "System operations"
        "read" -> "Read operations"
        "mutation" -> "Mutation operations"
        else -> tag.replaceFirstChar { it.uppercase() }
    }

    private var examplesDir: Path? = null

    private fun readExampleFile(filename: String): String? {
        val dir = examplesDir ?: findExamplesDir().also { examplesDir = it }
        val file = dir.resolve(filename)
        return if (Files.exists(file)) file.toFile().readText().trimEnd() else null
    }

    private fun findExamplesDir(): Path =
        generateSequence(Path.of("").toAbsolutePath()) { it.parent }
            .map { it.resolve("docs/examples") }
            .first { Files.isDirectory(it) }
}

fun main(args: Array<String>) {
    val outputDir = if (args.isNotEmpty()) {
        Path.of(args[0])
    } else {
        generateSequence(Path.of("").toAbsolutePath()) { it.parent }
            .first { Files.isDirectory(it.resolve("docs")) }
            .resolve("docs/reference")
    }
    Files.createDirectories(outputDir)
    outputDir.resolve("capabilities.md").toFile().writeText(DocsDocument.renderCapabilities())
    outputDir.resolve("api-reference.md").toFile().writeText(DocsDocument.renderApiReference())
    println("Generated capabilities.md and api-reference.md in $outputDir")
}
