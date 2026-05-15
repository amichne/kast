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
        val spec = buildJsonObject {
            put("version", version)
            put("commands", buildJsonObject {
                commands().forEach { command ->
                    put(command.name, command.toJson())
                }
            })
        }
        return spec.toPrettyString()
    }

    private fun commands(): List<CommandEntry> = listOf(
        CommandEntry(
            name = "workspace-files",
            summary = "List workspace modules and optional file paths",
            requestSerializer = io.github.amichne.kast.api.contract.skill.KastWorkspaceFilesRequest.serializer(),
            successType = "WORKSPACE_FILES_SUCCESS",
            failureType = "WORKSPACE_FILES_FAILURE",
        ),
        CommandEntry(
            name = "workspace-search",
            summary = "Search Kotlin workspace file contents by text or regex",
            requestSerializer = io.github.amichne.kast.api.contract.skill.KastWorkspaceSearchRequest.serializer(),
            successType = "WORKSPACE_SEARCH_SUCCESS",
            failureType = "WORKSPACE_SEARCH_FAILURE",
        ),
        CommandEntry(
            name = "discover-symbol",
            summary = "Rank likely declarations for an ambiguous symbol name",
            requestSerializer = io.github.amichne.kast.api.contract.skill.KastSymbolDiscoveryRequest.serializer(),
            successType = "DISCOVER_SYMBOL_SUCCESS",
            failureType = "DISCOVER_SYMBOL_FAILURE",
            notes = listOf(
                "Use this before \"resolve\" when a simple name may match multiple declarations.",
                "Discovery stays bounded to a single workspace-symbol search and ranks candidates with optional file, line, and code-snippet context.",
            ),
        ),
        CommandEntry(
            name = "scaffold",
            summary = "Gather structural generation context for a Kotlin file",
            requestSerializer = io.github.amichne.kast.api.contract.skill.KastScaffoldRequest.serializer(),
            successType = "SCAFFOLD_SUCCESS",
            failureType = "SCAFFOLD_FAILURE",
        ),
        CommandEntry(
            name = "resolve",
            summary = "Resolve a symbol by name to its declaration",
            requestSerializer = io.github.amichne.kast.api.contract.skill.KastResolveRequest.serializer(),
            successType = "RESOLVE_SUCCESS",
            failureType = "RESOLVE_FAILURE",
            notes = listOf(
                "The 'symbol' field takes simple names only (e.g. 'key'), never fully-qualified names.",
                "Use 'containingType' for scoping and 'fileHint' for disambiguation.",
            ),
        ),
        CommandEntry(
            name = "references",
            summary = "Find every usage of a Kotlin symbol",
            requestSerializer = io.github.amichne.kast.api.contract.skill.KastReferencesRequest.serializer(),
            successType = "REFERENCES_SUCCESS",
            failureType = "REFERENCES_FAILURE",
            notes = listOf(
                "The 'symbol' field takes simple names only.",
                "Resolve ambiguous names first with 'kind', 'containingType', or 'fileHint'.",
            ),
        ),
        CommandEntry(
            name = "callers",
            summary = "Expand an incoming or outgoing call hierarchy",
            requestSerializer = io.github.amichne.kast.api.contract.skill.KastCallersRequest.serializer(),
            successType = "CALLERS_SUCCESS",
            failureType = "CALLERS_FAILURE",
            notes = listOf(
                "The 'symbol' field takes simple names only.",
            ),
        ),
        CommandEntry(
            name = "diagnostics",
            summary = "Run Kotlin diagnostics on listed files",
            requestSerializer = io.github.amichne.kast.api.contract.skill.KastDiagnosticsRequest.serializer(),
            successType = "DIAGNOSTICS_SUCCESS",
            failureType = "DIAGNOSTICS_FAILURE",
        ),
        CommandEntry(
            name = "rename",
            summary = "Resolve or target a symbol and apply a rename",
            requestSerializer = io.github.amichne.kast.api.contract.skill.KastRenameRequest.serializer(),
            successType = "RENAME_SUCCESS",
            failureType = "RENAME_FAILURE",
            discriminatedTypes = mapOf(
                "RENAME_BY_SYMBOL_REQUEST" to io.github.amichne.kast.api.contract.skill.KastRenameBySymbolRequest.serializer(),
                "RENAME_BY_OFFSET_REQUEST" to io.github.amichne.kast.api.contract.skill.KastRenameByOffsetRequest.serializer(),
            ),
        ),
        CommandEntry(
            name = "write-and-validate",
            summary = "Apply generated Kotlin code and validate the result",
            requestSerializer = io.github.amichne.kast.api.contract.skill.KastWriteAndValidateRequest.serializer(),
            successType = "WRITE_AND_VALIDATE_SUCCESS",
            failureType = "WRITE_AND_VALIDATE_FAILURE",
            discriminatedTypes = mapOf(
                "CREATE_FILE_REQUEST" to io.github.amichne.kast.api.contract.skill.KastWriteAndValidateCreateFileRequest.serializer(),
                "INSERT_AT_OFFSET_REQUEST" to io.github.amichne.kast.api.contract.skill.KastWriteAndValidateInsertAtOffsetRequest.serializer(),
                "REPLACE_RANGE_REQUEST" to io.github.amichne.kast.api.contract.skill.KastWriteAndValidateReplaceRangeRequest.serializer(),
            ),
        ),
        CommandEntry(
            name = "metrics",
            summary = "Query indexed source metrics",
            requestSerializer = io.github.amichne.kast.api.contract.skill.KastMetricsRequest.serializer(),
            successType = "METRICS_SUCCESS",
            failureType = "METRICS_FAILURE",
        ),
    )

    private data class CommandEntry(
        val name: String,
        val summary: String,
        val requestSerializer: KSerializer<*>,
        val successType: String,
        val failureType: String,
        val discriminatedTypes: Map<String, KSerializer<*>>? = null,
        val notes: List<String>? = null,
    ) {
        @OptIn(ExperimentalSerializationApi::class)
        fun toJson(): JsonElement = buildJsonObject {
            put("summary", summary)
            put("request", descriptorToSchema(requestSerializer.descriptor))
            put("successType", successType)
            put("failureType", failureType)
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
