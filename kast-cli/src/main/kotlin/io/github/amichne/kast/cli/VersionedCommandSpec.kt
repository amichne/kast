package io.github.amichne.kast.cli

import io.github.amichne.kast.cli.tty.currentCliVersion
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path

object VersionedCommandSpec {

    fun renderJson(version: String = currentCliVersion()): String {
        val commands = commands()
        val spec = buildJsonObject {
            put("version", version)
            put("categories", buildJsonObject {
                CommandCategory.entries.forEach { category ->
                    put(category.wireName, buildJsonArray {
                        commands
                            .filter { it.category == category }
                            .forEach { add(JsonPrimitive(it.method)) }
                    })
                }
            })
            put("commands", buildJsonObject {
                commands.forEach { command ->
                    put(command.method, command.toJson())
                }
            })
        }
        return spec.toPrettyString()
    }

    private fun commands(): List<CommandEntry> = listOf(
        CommandEntry(
            method = "health",
            category = CommandCategory.SYSTEM,
            summary = "Basic health check",
            responseType = "HealthResponse",
        ),
        CommandEntry(
            method = "runtime/status",
            category = CommandCategory.SYSTEM,
            summary = "Detailed runtime state including indexing progress",
            responseType = "RuntimeStatusResponse",
        ),
        CommandEntry(
            method = "capabilities",
            category = CommandCategory.SYSTEM,
            summary = "Advertised read and mutation capabilities",
            responseType = "BackendCapabilities",
        ),
        CommandEntry(
            method = "symbol/scaffold",
            category = CommandCategory.SYMBOL,
            summary = "Gather structural generation context for a Kotlin file",
            requestSerializer = io.github.amichne.kast.api.contract.skill.KastScaffoldRequest.serializer(),
            responseType = "KastScaffoldResponse",
            successType = "SCAFFOLD_SUCCESS",
            failureType = "SCAFFOLD_FAILURE",
        ),
        CommandEntry(
            method = "symbol/resolve",
            category = CommandCategory.SYMBOL,
            summary = "Resolve a symbol by name to its declaration",
            requestSerializer = io.github.amichne.kast.api.contract.skill.KastResolveRequest.serializer(),
            responseType = "KastResolveResponse",
            successType = "RESOLVE_SUCCESS",
            failureType = "RESOLVE_FAILURE",
            notes = listOf(
                "The 'symbol' field takes simple names only (e.g. 'key'), never fully-qualified names.",
                "Use 'containingType' for scoping and 'fileHint' for disambiguation.",
            ),
        ),
        CommandEntry(
            method = "symbol/references",
            category = CommandCategory.SYMBOL,
            summary = "Find every usage of a Kotlin symbol",
            requestSerializer = io.github.amichne.kast.api.contract.skill.KastReferencesRequest.serializer(),
            responseType = "KastReferencesResponse",
            successType = "REFERENCES_SUCCESS",
            failureType = "REFERENCES_FAILURE",
            notes = listOf(
                "The 'symbol' field takes simple names only.",
                "Resolve ambiguous names first with 'kind', 'containingType', or 'fileHint'.",
            ),
        ),
        CommandEntry(
            method = "symbol/callers",
            category = CommandCategory.SYMBOL,
            summary = "Expand an incoming or outgoing call hierarchy",
            requestSerializer = io.github.amichne.kast.api.contract.skill.KastCallersRequest.serializer(),
            responseType = "KastCallersResponse",
            successType = "CALLERS_SUCCESS",
            failureType = "CALLERS_FAILURE",
            notes = listOf(
                "The 'symbol' field takes simple names only.",
            ),
        ),
        CommandEntry(
            method = "symbol/rename",
            category = CommandCategory.SYMBOL,
            summary = "Resolve or target a symbol and apply a rename",
            requestSerializer = io.github.amichne.kast.api.contract.skill.KastRenameRequest.serializer(),
            responseType = "KastRenameResponse",
            successType = "RENAME_SUCCESS",
            failureType = "RENAME_FAILURE",
            discriminatedTypes = mapOf(
                "RENAME_BY_SYMBOL_REQUEST" to io.github.amichne.kast.api.contract.skill.KastRenameBySymbolRequest.serializer(),
                "RENAME_BY_OFFSET_REQUEST" to io.github.amichne.kast.api.contract.skill.KastRenameByOffsetRequest.serializer(),
            ),
        ),
        CommandEntry(
            method = "symbol/write-and-validate",
            category = CommandCategory.SYMBOL,
            summary = "Apply generated Kotlin code and validate the result",
            requestSerializer = io.github.amichne.kast.api.contract.skill.KastWriteAndValidateRequest.serializer(),
            responseType = "KastWriteAndValidateResponse",
            successType = "WRITE_AND_VALIDATE_SUCCESS",
            failureType = "WRITE_AND_VALIDATE_FAILURE",
            discriminatedTypes = mapOf(
                "CREATE_FILE_REQUEST" to io.github.amichne.kast.api.contract.skill.KastWriteAndValidateCreateFileRequest.serializer(),
                "INSERT_AT_OFFSET_REQUEST" to io.github.amichne.kast.api.contract.skill.KastWriteAndValidateInsertAtOffsetRequest.serializer(),
                "REPLACE_RANGE_REQUEST" to io.github.amichne.kast.api.contract.skill.KastWriteAndValidateReplaceRangeRequest.serializer(),
            ),
        ),
        CommandEntry(
            method = "raw/resolve",
            category = CommandCategory.RAW,
            summary = "Resolve the symbol at a file position",
            requestSerializer = io.github.amichne.kast.api.contract.query.SymbolQuery.serializer(),
            responseType = "SymbolResult",
        ),
        CommandEntry(
            method = "raw/references",
            category = CommandCategory.RAW,
            summary = "Find all references to the symbol at a file position",
            requestSerializer = io.github.amichne.kast.api.contract.query.ReferencesQuery.serializer(),
            responseType = "ReferencesResult",
        ),
        CommandEntry(
            method = "raw/call-hierarchy",
            category = CommandCategory.RAW,
            summary = "Expand a bounded incoming or outgoing call tree",
            requestSerializer = io.github.amichne.kast.api.contract.query.CallHierarchyQuery.serializer(),
            responseType = "CallHierarchyResult",
        ),
        CommandEntry(
            method = "raw/type-hierarchy",
            category = CommandCategory.RAW,
            summary = "Expand supertypes and subtypes from a resolved symbol",
            requestSerializer = io.github.amichne.kast.api.contract.query.TypeHierarchyQuery.serializer(),
            responseType = "TypeHierarchyResult",
        ),
        CommandEntry(
            method = "raw/semantic-insertion-point",
            category = CommandCategory.RAW,
            summary = "Find the best insertion point for a new declaration",
            requestSerializer = io.github.amichne.kast.api.contract.SemanticInsertionQuery.serializer(),
            responseType = "SemanticInsertionResult",
        ),
        CommandEntry(
            method = "raw/diagnostics",
            category = CommandCategory.RAW,
            summary = "Run Kotlin diagnostics on listed files",
            requestSerializer = io.github.amichne.kast.api.contract.query.DiagnosticsQuery.serializer(),
            responseType = "DiagnosticsResult",
        ),
        CommandEntry(
            method = "raw/rename",
            category = CommandCategory.RAW,
            summary = "Plan a symbol rename by file position",
            requestSerializer = io.github.amichne.kast.api.contract.query.RenameQuery.serializer(),
            responseType = "RenameResult",
        ),
        CommandEntry(
            method = "raw/optimize-imports",
            category = CommandCategory.RAW,
            summary = "Optimize imports for one or more files",
            requestSerializer = io.github.amichne.kast.api.contract.query.ImportOptimizeQuery.serializer(),
            responseType = "ImportOptimizeResult",
        ),
        CommandEntry(
            method = "raw/apply-edits",
            category = CommandCategory.RAW,
            summary = "Apply a prepared edit plan with conflict detection",
            requestSerializer = io.github.amichne.kast.api.contract.query.ApplyEditsQuery.serializer(),
            responseType = "ApplyEditsResult",
        ),
        CommandEntry(
            method = "raw/workspace-refresh",
            category = CommandCategory.RAW,
            summary = "Force a targeted or full workspace state refresh",
            requestSerializer = io.github.amichne.kast.api.contract.query.RefreshQuery.serializer(),
            responseType = "RefreshResult",
        ),
        CommandEntry(
            method = "raw/file-outline",
            category = CommandCategory.RAW,
            summary = "Get a hierarchical symbol outline for a file",
            requestSerializer = io.github.amichne.kast.api.contract.query.FileOutlineQuery.serializer(),
            responseType = "FileOutlineResult",
        ),
        CommandEntry(
            method = "raw/workspace-symbol",
            category = CommandCategory.RAW,
            summary = "Search the workspace for symbols by name pattern",
            requestSerializer = io.github.amichne.kast.api.contract.query.WorkspaceSymbolQuery.serializer(),
            responseType = "WorkspaceSymbolResult",
        ),
        CommandEntry(
            method = "raw/workspace-search",
            category = CommandCategory.RAW,
            summary = "Search workspace file contents by text or regex",
            requestSerializer = io.github.amichne.kast.api.contract.query.WorkspaceSearchQuery.serializer(),
            responseType = "WorkspaceSearchResult",
        ),
        CommandEntry(
            method = "raw/workspace-files",
            category = CommandCategory.RAW,
            summary = "List workspace modules and optional file paths",
            requestSerializer = io.github.amichne.kast.api.contract.query.WorkspaceFilesQuery.serializer(),
            responseType = "WorkspaceFilesResult",
        ),
        CommandEntry(
            method = "raw/implementations",
            category = CommandCategory.RAW,
            summary = "Find concrete implementations and subclasses for a declaration",
            requestSerializer = io.github.amichne.kast.api.contract.query.ImplementationsQuery.serializer(),
            responseType = "ImplementationsResult",
        ),
        CommandEntry(
            method = "raw/code-actions",
            category = CommandCategory.RAW,
            summary = "Return available code actions at a file position",
            requestSerializer = io.github.amichne.kast.api.contract.query.CodeActionsQuery.serializer(),
            responseType = "CodeActionsResult",
        ),
        CommandEntry(
            method = "raw/completions",
            category = CommandCategory.RAW,
            summary = "Return completion candidates available at a file position",
            requestSerializer = io.github.amichne.kast.api.contract.query.CompletionsQuery.serializer(),
            responseType = "CompletionsResult",
        ),
        CommandEntry(
            method = "database/metrics",
            category = CommandCategory.DATABASE,
            summary = "Query indexed source metrics",
            requestSerializer = io.github.amichne.kast.api.contract.skill.KastMetricsRequest.serializer(),
            responseType = "KastMetricsResponse",
            successType = "METRICS_SUCCESS",
            failureType = "METRICS_FAILURE",
            dataSource = "sqlite",
        ),
    )

    private data class CommandEntry(
        val method: String,
        val category: CommandCategory,
        val summary: String,
        val requestSerializer: KSerializer<*>? = null,
        val responseType: String,
        val successType: String? = null,
        val failureType: String? = null,
        val discriminatedTypes: Map<String, KSerializer<*>>? = null,
        val notes: List<String>? = null,
        val dataSource: String = "backend",
    ) {
        @OptIn(ExperimentalSerializationApi::class)
        fun toJson(): JsonElement = buildJsonObject {
            put("method", method)
            put("category", category.wireName)
            put("summary", summary)
            put("dataSource", dataSource)
            put("request", requestSerializer?.let { descriptorToSchema(it.descriptor) } ?: emptyRequestSchema())
            put("responseType", responseType)
            successType?.let { put("successType", it) }
            failureType?.let { put("failureType", it) }
            if (discriminatedTypes != null) {
                put("variants", buildJsonObject {
                    discriminatedTypes.forEach { (discriminator, serializer) ->
                        put(discriminator, descriptorToSchema(serializer.descriptor))
                    }
                })
            }
            if (!notes.isNullOrEmpty()) {
                put("notes", buildJsonArray { notes.forEach { add(JsonPrimitive(it)) } })
            }
        }
    }

    private enum class CommandCategory(val wireName: String) {
        SYSTEM("system"),
        SYMBOL("symbol"),
        RAW("raw"),
        DATABASE("database"),
    }
}

private fun emptyRequestSchema(): JsonObject = buildJsonObject {
    put("fields", buildJsonObject {})
}

@OptIn(ExperimentalSerializationApi::class)
private fun descriptorToSchema(descriptor: SerialDescriptor): JsonObject = buildJsonObject {
    val required = buildJsonArray {
        repeat(descriptor.elementsCount) { index ->
            if (!descriptor.isElementOptional(index)) {
                add(JsonPrimitive(descriptor.getElementName(index)))
            }
        }
    }
    put("fields", buildJsonObject {
        repeat(descriptor.elementsCount) { index ->
            val fieldName = descriptor.getElementName(index)
            val fieldDescriptor = descriptor.getElementDescriptor(index)
            put(fieldName, fieldSchema(fieldDescriptor, descriptor.isElementOptional(index)))
        }
    })
    if (required.isNotEmpty()) {
        put("required", required)
    }
}

@OptIn(ExperimentalSerializationApi::class)
private fun fieldSchema(descriptor: SerialDescriptor, optional: Boolean): JsonObject = buildJsonObject {
    put("type", JsonPrimitive(typeNameFor(descriptor)))
    if (optional) {
        put("optional", JsonPrimitive(true))
    }
    if (descriptor.isNullable) {
        put("nullable", JsonPrimitive(true))
    }
    if (descriptor.kind == SerialKind.ENUM) {
        put("enum", buildJsonArray {
            repeat(descriptor.elementsCount) { i ->
                add(JsonPrimitive(descriptor.getElementName(i)))
            }
        })
    }
    if (descriptor.kind == StructureKind.LIST) {
        put("items", JsonPrimitive(typeNameFor(descriptor.getElementDescriptor(0))))
    }
}

@OptIn(ExperimentalSerializationApi::class)
private fun typeNameFor(descriptor: SerialDescriptor): String = when (descriptor.kind) {
    PrimitiveKind.BOOLEAN -> "boolean"
    PrimitiveKind.BYTE, PrimitiveKind.SHORT, PrimitiveKind.INT, PrimitiveKind.LONG -> "integer"
    PrimitiveKind.FLOAT, PrimitiveKind.DOUBLE -> "number"
    PrimitiveKind.CHAR, PrimitiveKind.STRING -> "string"
    StructureKind.LIST -> "array"
    StructureKind.MAP -> "object"
    SerialKind.ENUM -> "string"
    else -> "object"
}

private fun JsonElement.toPrettyString(indent: Int = 0): String {
    val pad = "  ".repeat(indent)
    val pad1 = "  ".repeat(indent + 1)
    return when (this) {
        is JsonObject -> {
            if (isEmpty()) "{}"
            else entries.joinToString(",\n", "{\n", "\n$pad}") { (k, v) ->
                "$pad1\"$k\": ${v.toPrettyString(indent + 1)}"
            }
        }
        is JsonArray -> {
            if (isEmpty()) "[]"
            else joinToString(",\n", "[\n", "\n$pad]") { elem ->
                "$pad1${elem.toPrettyString(indent + 1)}"
            }
        }
        is JsonNull -> "null"
        is JsonPrimitive -> toString()
    }
}

fun main(args: Array<String>) {
    val version = args.getOrNull(0) ?: currentCliVersion()
    val target = args.getOrNull(1)?.let(Path::of)
        ?: Path.of(".agents/skills/kast/references/commands.json")
    Files.createDirectories(target.parent)
    Files.writeString(target, VersionedCommandSpec.renderJson(version) + "\n")
}
