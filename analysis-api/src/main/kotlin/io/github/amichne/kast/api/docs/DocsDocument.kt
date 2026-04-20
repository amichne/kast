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

    /** Exposes schema serializers for testing purposes only. */
    fun schemaSerializersForTesting(): Map<String, KSerializer<*>> = schemaSerializers

    fun renderCapabilities(): String {
        val writer = IndentedWriter()
        writer.line("---")
        writer.line("title: Capabilities")
        writer.line("hide:")
        writer.line("    - navigation")
        writer.line("    - toc")
        writer.line("---")
        writer.line()
        writer.line("# Capabilities")
        writer.line()
        writer.line("Every operation the Kast analysis daemon supports, organized by")
        writer.line("category. Expand any operation to see its input and output schemas.")
        writer.line()

        val ops = OperationDocRegistry.all().toList()
        for (tag in listOf("system", "read", "mutation")) {
            val tagOps = ops.filter { it.tag == tag }
            if (tagOps.isEmpty()) continue
            writer.tab(tagDisplayName(tag)) {
                admonition("abstract", "At a glance") {
                    line(tagSummary(tag, tagOps.size))
                }
                line()
                for (op in tagOps) {
                    capabilitiesOperation(op)
                    line()
                }
            }
        }

        return writer.toString().trimEnd() + "\n"
    }

    fun renderApiReference(): String {
        val writer = IndentedWriter()
        writer.line("---")
        writer.line("title: API reference")
        writer.line("hide:")
        writer.line("    - toc")
        writer.line("---")
        writer.line()
        writer.line("# API reference")
        writer.line()
        writer.line("Complete reference for every JSON-RPC method in the Kast analysis")
        writer.line("daemon, including input/output schemas, examples, and behavioral notes.")
        writer.line()

        val ops = OperationDocRegistry.all().toList()
        for (tag in listOf("system", "read", "mutation")) {
            val tagOps = ops.filter { it.tag == tag }
            if (tagOps.isEmpty()) continue
            writer.tab(tagDisplayName(tag)) {
                admonition("abstract", "At a glance") {
                    line(tagSummary(tag, tagOps.size))
                }
                line()
                for (op in tagOps) {
                    apiReferenceOperation(op)
                    line()
                }
            }
        }

        return writer.toString().trimEnd() + "\n"
    }

    // ── Per-operation renderers ───────────────────────────────────────

    private fun IndentedWriter.capabilitiesOperation(op: OperationDoc) {
        details("info", op.capabilityTitle()) {
            badgeLine(op)
            line()

            if (op.requestSchema != null) {
                line("??? info \"Input: ${op.requestSchema}\"")
                line()
                indented {
                    schemaTable(op.requestSchema, "api-reference.md")
                }
                line()
            }

            line("??? info \"Output: ${op.responseSchema}\"")
            line()
            indented {
                schemaTable(op.responseSchema, "api-reference.md")
            }
        }
    }

    private fun IndentedWriter.apiReferenceOperation(op: OperationDoc) {
        details("example", op.capabilityTitle()) {
            lines(op.description)
            line()
            badgeLine(op)
            line()

            if (op.requestSchema != null) {
                line("#### Input: ${op.requestSchema}")
                line()
                schemaTable(op.requestSchema)
                line()
            }

            line("#### Output: ${op.responseSchema}")
            line()
            schemaTable(op.responseSchema)
            line()

            tabbedExamples(op)

            if (op.behavioralNotes.isNotEmpty()) {
                admonition("note", "Behavioral notes") {
                    for (note in op.behavioralNotes) {
                        line("- $note")
                    }
                }
                line()
            }

            if (op.errorCodes.isNotEmpty()) {
                line("**Error codes:** ${op.errorCodes.joinToString(", ") { "`$it`" }}")
            }
        }
    }

    // ── Badge line ────────────────────────────────────────────────────

    /**
     * Emits a right-aligned row of delineated badges: one for the capability slug (if present)
     * and one for the raw JSON-RPC method name. Uses block-level HTML so no `md_in_html`
     * processing is needed — `<code>` tags render identically to Markdown backtick spans.
     */
    private fun IndentedWriter.badgeLine(op: OperationDoc) {
        line("<div style=\"text-align:right\">")
        line(buildString {
            if (op.capability != null) append("<code>${op.capability}</code>&ensp;")
            append("<code>${op.jsonRpcMethod}</code>")
        })
        line("</div>")
    }

    /** Converts `"FIND_REFERENCES"` → `"Find References"`. Falls back to [OperationDoc.summary] for operations with no capability gating (system ops). */
    private fun OperationDoc.capabilityTitle(): String =
        capability
            ?.split("_")
            ?.joinToString(" ") { it.lowercase().replaceFirstChar { c -> c.uppercase() } }
            ?: summary

    // ── Schema table rendering ────────────────────────────────────────

    // Maps operation-level schema names to their anchor in api-reference.md.
    // Only schemas that appear directly as Input/Output of an operation have anchors.
    private val typeAnchors: Map<String, String> by lazy {
        val result = mutableMapOf<String, String>()
        for (op in OperationDocRegistry.all()) {
            op.requestSchema?.let { result.putIfAbsent(it, "input-${it.lowercase()}") }
            result.putIfAbsent(op.responseSchema, "output-${op.responseSchema.lowercase()}")
        }
        result
    }

    private fun IndentedWriter.schemaTable(schemaName: String, crossRefBase: String? = null) {
        val serializer = schemaSerializers[schemaName]
        if (serializer == null) {
            line("*Schema not found: $schemaName*")
            return
        }
        val descriptor = serializer.descriptor
        if (descriptor.elementsCount == 0) {
            line("*No fields.*")
            return
        }

        line("| Signature | Description |")
        line("|-----------|-------------|")
        repeat(descriptor.elementsCount) { index ->
            val name = descriptor.getElementName(index)
            val elementDescriptor = descriptor.getElementDescriptor(index)
            val typeName = resolveTypeName(elementDescriptor)
            val isOptional = descriptor.isElementOptional(index)
            val docField = descriptor.getElementAnnotations(index)
                .filterIsInstance<DocField>()
                .firstOrNull()
            val description = docField?.description?.ifBlank { "" } ?: ""
            val explicitDefault = docField?.defaultValue?.ifBlank { null }

            val signature: String
            val tooltip: String
            when {
                docField?.serverManaged == true -> {
                    signature = "`#!kotlin $name: $typeName`"
                    tooltip = ""
                }
                explicitDefault != null -> {
                    signature = "`#!kotlin $name: $typeName`"
                    val escapedDefault = explicitDefault.replace("\"", "&quot;")
                    tooltip = " :material-information-outline:{ title=\"Default: $escapedDefault\" }"
                }
                else -> {
                    val displayType = if (isOptional && !typeName.endsWith("?")) "$typeName?" else typeName
                    signature = "`#!kotlin $name: $displayType`"
                    tooltip = ""
                }
            }

            val baseTypeName = simpleName(elementDescriptor.serialName)
            val previewLink = if (crossRefBase != null) {
                typeAnchors[baseTypeName]?.let { anchor -> " [↗]($crossRefBase#$anchor){ data-preview }" }
            } else null

            line("| $signature$tooltip${previewLink ?: ""} | $description |")
        }
    }

    // ── Tabbed examples ───────────────────────────────────────────────

    private fun IndentedWriter.tabbedExamples(op: OperationDoc) {
        val hasCliExample = op.cliExample.isNotBlank()
        val requestJson = readExampleFile("${op.operationId}-request.json")
        val responseJson = readExampleFile("${op.operationId}-response.json")

        if (!hasCliExample && requestJson == null && responseJson == null) return

        if (hasCliExample) {
            tab("CLI example") {
                line("```bash")
                line(op.cliExample)
                line("```")
            }
            line()
        }

        if (requestJson != null) {
            tab("JSON-RPC request") {
                line("```json")
                lines(requestJson)
                line("```")
            }
            line()
        }

        if (responseJson != null) {
            tab("Example response") {
                line("```json")
                lines(responseJson)
                line("```")
            }
            line()
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

    private fun tagSummary(tag: String, count: Int): String = when (tag) {
        "system" -> "$count operations for health checks, runtime status, and capability discovery. No capability gating required."
        "read" -> "$count read-only operations for querying symbols, references, hierarchies, diagnostics, outlines, and completions."
        "mutation" -> "$count operations that modify workspace state: rename, optimize imports, apply edits, and refresh."
        else -> "$count operations."
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
