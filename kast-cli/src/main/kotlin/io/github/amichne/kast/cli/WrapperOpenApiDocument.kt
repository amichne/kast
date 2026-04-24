package io.github.amichne.kast.cli

import io.github.amichne.kast.api.protocol.ApiErrorResponse
import io.github.amichne.kast.api.wrapper.KastCallersFailureResponse
import io.github.amichne.kast.api.wrapper.KastCallersRequest
import io.github.amichne.kast.api.wrapper.KastCallersResponse
import io.github.amichne.kast.api.wrapper.KastCallersSuccessResponse
import io.github.amichne.kast.api.wrapper.KastDiagnosticsFailureResponse
import io.github.amichne.kast.api.wrapper.KastDiagnosticsRequest
import io.github.amichne.kast.api.wrapper.KastDiagnosticsResponse
import io.github.amichne.kast.api.wrapper.KastDiagnosticsSuccessResponse
import io.github.amichne.kast.api.wrapper.KastReferencesFailureResponse
import io.github.amichne.kast.api.wrapper.KastReferencesRequest
import io.github.amichne.kast.api.wrapper.KastReferencesResponse
import io.github.amichne.kast.api.wrapper.KastReferencesSuccessResponse
import io.github.amichne.kast.api.wrapper.KastRenameByOffsetQuery
import io.github.amichne.kast.api.wrapper.KastRenameByOffsetRequest
import io.github.amichne.kast.api.wrapper.KastRenameBySymbolQuery
import io.github.amichne.kast.api.wrapper.KastRenameBySymbolRequest
import io.github.amichne.kast.api.wrapper.KastRenameFailureResponse
import io.github.amichne.kast.api.wrapper.KastRenameRequest
import io.github.amichne.kast.api.wrapper.KastRenameResponse
import io.github.amichne.kast.api.wrapper.KastRenameSuccessResponse
import io.github.amichne.kast.api.wrapper.KastResolveFailureResponse
import io.github.amichne.kast.api.wrapper.KastResolveRequest
import io.github.amichne.kast.api.wrapper.KastResolveResponse
import io.github.amichne.kast.api.wrapper.KastResolveSuccessResponse
import io.github.amichne.kast.api.wrapper.KastScaffoldFailureResponse
import io.github.amichne.kast.api.wrapper.KastScaffoldRequest
import io.github.amichne.kast.api.wrapper.KastScaffoldResponse
import io.github.amichne.kast.api.wrapper.KastScaffoldSuccessResponse
import io.github.amichne.kast.api.wrapper.KastWorkspaceFilesFailureResponse
import io.github.amichne.kast.api.wrapper.KastWorkspaceFilesRequest
import io.github.amichne.kast.api.wrapper.KastWorkspaceFilesResponse
import io.github.amichne.kast.api.wrapper.KastWorkspaceFilesSuccessResponse
import io.github.amichne.kast.api.wrapper.KastWriteAndValidateCreateFileQuery
import io.github.amichne.kast.api.wrapper.KastWriteAndValidateCreateFileRequest
import io.github.amichne.kast.api.wrapper.KastWriteAndValidateFailureResponse
import io.github.amichne.kast.api.wrapper.KastWriteAndValidateInsertAtOffsetQuery
import io.github.amichne.kast.api.wrapper.KastWriteAndValidateInsertAtOffsetRequest
import io.github.amichne.kast.api.wrapper.KastWriteAndValidateReplaceRangeQuery
import io.github.amichne.kast.api.wrapper.KastWriteAndValidateReplaceRangeRequest
import io.github.amichne.kast.api.wrapper.KastWriteAndValidateRequest
import io.github.amichne.kast.api.wrapper.KastWriteAndValidateResponse
import io.github.amichne.kast.api.wrapper.KastWriteAndValidateSuccessResponse
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PolymorphicKind
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.StructureKind
import java.nio.file.Files
import java.nio.file.Path
import java.util.LinkedHashMap

object WrapperOpenApiDocument {
    fun renderYaml(): String {
        val registry = SchemaRegistry()
        registerSchemas(registry)
        return buildString {
            appendLine("openapi: 3.1.0")
            appendLine("info:")
            appendLine("  title: kast skill contracts")
            appendLine("  version: 1.1.0")
            appendLine("  description: >")
            appendLine("    Generated from the native kast skill request and response serializers.")
            appendLine("servers:")
            appendLine("  - url: kast://skill")
            appendLine("    description: Native kast skill command surface")
            appendLine("paths:")
            append(renderYaml(writePaths(), 2))
            appendLine("components:")
            appendLine("  schemas:")
            append(renderYaml(registry.schemas, 4))
        }
    }

    private fun registerSchemas(registry: SchemaRegistry) {
        registry.register("ApiErrorResponse", ApiErrorResponse.serializer())

        registry.register("KastResolveRequest", KastResolveRequest.serializer())
        registry.register("KastReferencesRequest", KastReferencesRequest.serializer())
        registry.register("KastCallersRequest", KastCallersRequest.serializer())
        registry.register("KastDiagnosticsRequest", KastDiagnosticsRequest.serializer())
        registry.register("KastRenameBySymbolRequest", KastRenameBySymbolRequest.serializer())
        registry.register("KastRenameByOffsetRequest", KastRenameByOffsetRequest.serializer())
        registry.register("KastRenameRequest", KastRenameRequest.serializer())
        registry.register("KastScaffoldRequest", KastScaffoldRequest.serializer())
        registry.register("KastWorkspaceFilesRequest", KastWorkspaceFilesRequest.serializer())
        registry.register("KastWriteAndValidateCreateFileRequest", KastWriteAndValidateCreateFileRequest.serializer())
        registry.register("KastWriteAndValidateInsertAtOffsetRequest", KastWriteAndValidateInsertAtOffsetRequest.serializer())
        registry.register("KastWriteAndValidateReplaceRangeRequest", KastWriteAndValidateReplaceRangeRequest.serializer())
        registry.register("KastWriteAndValidateRequest", KastWriteAndValidateRequest.serializer())

        registry.register("KastRenameBySymbolQuery", KastRenameBySymbolQuery.serializer())
        registry.register("KastRenameByOffsetQuery", KastRenameByOffsetQuery.serializer())
        registry.register("KastWriteAndValidateCreateFileQuery", KastWriteAndValidateCreateFileQuery.serializer())
        registry.register("KastWriteAndValidateInsertAtOffsetQuery", KastWriteAndValidateInsertAtOffsetQuery.serializer())
        registry.register("KastWriteAndValidateReplaceRangeQuery", KastWriteAndValidateReplaceRangeQuery.serializer())

        registry.register("KastResolveSuccessResponse", KastResolveSuccessResponse.serializer())
        registry.register("KastResolveFailureResponse", KastResolveFailureResponse.serializer())
        registry.register("KastResolveResponse", KastResolveResponse.serializer())
        registry.register("KastReferencesSuccessResponse", KastReferencesSuccessResponse.serializer())
        registry.register("KastReferencesFailureResponse", KastReferencesFailureResponse.serializer())
        registry.register("KastReferencesResponse", KastReferencesResponse.serializer())
        registry.register("KastCallersSuccessResponse", KastCallersSuccessResponse.serializer())
        registry.register("KastCallersFailureResponse", KastCallersFailureResponse.serializer())
        registry.register("KastCallersResponse", KastCallersResponse.serializer())
        registry.register("KastDiagnosticsSuccessResponse", KastDiagnosticsSuccessResponse.serializer())
        registry.register("KastDiagnosticsFailureResponse", KastDiagnosticsFailureResponse.serializer())
        registry.register("KastDiagnosticsResponse", KastDiagnosticsResponse.serializer())
        registry.register("KastRenameSuccessResponse", KastRenameSuccessResponse.serializer())
        registry.register("KastRenameFailureResponse", KastRenameFailureResponse.serializer())
        registry.register("KastRenameResponse", KastRenameResponse.serializer())
        registry.register("KastScaffoldSuccessResponse", KastScaffoldSuccessResponse.serializer())
        registry.register("KastScaffoldFailureResponse", KastScaffoldFailureResponse.serializer())
        registry.register("KastScaffoldResponse", KastScaffoldResponse.serializer())
        registry.register("KastWorkspaceFilesSuccessResponse", KastWorkspaceFilesSuccessResponse.serializer())
        registry.register("KastWorkspaceFilesFailureResponse", KastWorkspaceFilesFailureResponse.serializer())
        registry.register("KastWorkspaceFilesResponse", KastWorkspaceFilesResponse.serializer())
        registry.register("KastWriteAndValidateSuccessResponse", KastWriteAndValidateSuccessResponse.serializer())
        registry.register("KastWriteAndValidateFailureResponse", KastWriteAndValidateFailureResponse.serializer())
        registry.register("KastWriteAndValidateResponse", KastWriteAndValidateResponse.serializer())
    }

    private fun writePaths(): Map<String, Any?> = linkedMapOf(
        "/skill/resolve" to pathItem(
            operationId = "kastSkillResolve",
            summary = "Resolve a symbol by name",
            command = "kast skill resolve",
            requestSchema = "KastResolveRequest",
            responseSchema = "KastResolveResponse",
        ),
        "/skill/references" to pathItem(
            operationId = "kastSkillReferences",
            summary = "Find references for a named symbol",
            command = "kast skill references",
            requestSchema = "KastReferencesRequest",
            responseSchema = "KastReferencesResponse",
        ),
        "/skill/callers" to pathItem(
            operationId = "kastSkillCallers",
            summary = "Expand an incoming or outgoing call hierarchy",
            command = "kast skill callers",
            requestSchema = "KastCallersRequest",
            responseSchema = "KastCallersResponse",
        ),
        "/skill/diagnostics" to pathItem(
            operationId = "kastSkillDiagnostics",
            summary = "Run diagnostics on Kotlin files",
            command = "kast skill diagnostics",
            requestSchema = "KastDiagnosticsRequest",
            responseSchema = "KastDiagnosticsResponse",
        ),
        "/skill/rename" to pathItem(
            operationId = "kastSkillRename",
            summary = "Resolve or target a symbol and apply a rename",
            command = "kast skill rename",
            requestSchema = "KastRenameRequest",
            responseSchema = "KastRenameResponse",
        ),
        "/skill/scaffold" to pathItem(
            operationId = "kastSkillScaffold",
            summary = "Gather structural generation context",
            command = "kast skill scaffold",
            requestSchema = "KastScaffoldRequest",
            responseSchema = "KastScaffoldResponse",
        ),
        "/skill/workspace-files" to pathItem(
            operationId = "kastSkillWorkspaceFiles",
            summary = "List workspace modules and optional file paths",
            command = "kast skill workspace-files",
            requestSchema = "KastWorkspaceFilesRequest",
            responseSchema = "KastWorkspaceFilesResponse",
        ),
        "/skill/write-and-validate" to pathItem(
            operationId = "kastSkillWriteAndValidate",
            summary = "Apply generated Kotlin code and validate the result",
            command = "kast skill write-and-validate",
            requestSchema = "KastWriteAndValidateRequest",
            responseSchema = "KastWriteAndValidateResponse",
        ),
    )

    private fun pathItem(
        operationId: String,
        summary: String,
        command: String,
        requestSchema: String,
        responseSchema: String,
    ): Map<String, Any?> = linkedMapOf(
        "post" to linkedMapOf(
            "operationId" to operationId,
            "summary" to summary,
            "x-command" to command,
            "requestBody" to linkedMapOf(
                "required" to true,
                "content" to linkedMapOf(
                    "application/json" to linkedMapOf(
                        "schema" to ref(requestSchema),
                    ),
                ),
            ),
            "responses" to linkedMapOf(
                "200" to linkedMapOf(
                    "description" to "Wrapper JSON response",
                    "content" to linkedMapOf(
                        "application/json" to linkedMapOf(
                            "schema" to ref(responseSchema),
                        ),
                    ),
                ),
            ),
        ),
    )

    private fun ref(name: String): Map<String, Any?> = linkedMapOf("\$ref" to "#/components/schemas/$name")
}

@OptIn(ExperimentalSerializationApi::class)
private class SchemaRegistry {
    val schemas = linkedMapOf<String, Any?>()
    private val registeredDescriptors = mutableMapOf<String, String>()

    fun register(name: String, serializer: KSerializer<*>) {
        if (schemas.containsKey(name)) return
        registeredDescriptors[serializer.descriptor.serialName] = name
        schemas[name] = emptyMap<String, Any?>()
        schemas[name] = schemaFor(serializer.descriptor, rootName = name)
    }

    private fun schemaFor(
        descriptor: SerialDescriptor,
        rootName: String? = null,
        includeNullable: Boolean = true,
    ): Map<String, Any?> {
        manualUnionSchema(rootName ?: simpleName(descriptor.serialName))?.let { manual ->
            return manual
        }

        val schema = when (descriptor.kind) {
            is PrimitiveKind -> primitiveSchema(descriptor.kind as PrimitiveKind)
            StructureKind.CLASS,
            StructureKind.OBJECT,
            -> objectSchema(descriptor, rootName)
            StructureKind.LIST -> linkedMapOf(
                "type" to "array",
                "items" to inlineSchema(descriptor.getElementDescriptor(0)),
            )
            StructureKind.MAP -> linkedMapOf(
                "type" to "object",
                "additionalProperties" to inlineSchema(descriptor.getElementDescriptor(1)),
            )
            SerialKind.ENUM -> linkedMapOf(
                "type" to "string",
                "enum" to List(descriptor.elementsCount) { descriptor.getElementName(it) },
            )
            PolymorphicKind.SEALED -> linkedMapOf("type" to "object")
            else -> linkedMapOf("type" to "object")
        }
        return if (includeNullable && descriptor.isNullable) {
            linkedMapOf(
                "anyOf" to listOf(schema, linkedMapOf("type" to "null")),
            )
        } else {
            schema
        }
    }

    private fun objectSchema(
        descriptor: SerialDescriptor,
        componentName: String?,
    ): Map<String, Any?> {
        val properties = linkedMapOf<String, Any?>()
        val required = mutableListOf<String>()
        repeat(descriptor.elementsCount) { index ->
            val name = descriptor.getElementName(index)
            properties[name] = inlineSchema(descriptor.getElementDescriptor(index))
            if (!descriptor.isElementOptional(index)) {
                required += name
            }
        }
        discriminatorValue(componentName)?.let { value ->
            properties["type"] = linkedMapOf(
                "type" to "string",
                "const" to value,
            )
            required += "type"
        }
        return linkedMapOf<String, Any?>(
            "type" to "object",
            "properties" to properties,
            "additionalProperties" to false,
        ).also {
            if (required.isNotEmpty()) {
                it["required"] = required
            }
        }
    }

    private fun inlineSchema(descriptor: SerialDescriptor): Any? =
        when (descriptor.kind) {
            is PrimitiveKind -> schemaFor(descriptor)
            StructureKind.LIST -> schemaFor(descriptor)
            StructureKind.MAP -> schemaFor(descriptor)
            SerialKind.ENUM,
            StructureKind.CLASS,
            StructureKind.OBJECT,
            PolymorphicKind.SEALED,
            -> WrapperOpenApiDocument.run {
                linkedMapOf("\$ref" to "#/components/schemas/${ensureRegistered(descriptor, forceNonNullable = true)}")
            }.let { ref ->
                if (descriptor.isNullable) {
                    linkedMapOf(
                        "anyOf" to listOf(ref, linkedMapOf("type" to "null")),
                    )
                } else {
                    ref
                }
            }
            else -> linkedMapOf("type" to "object")
        }

    private fun ensureRegistered(
        descriptor: SerialDescriptor,
        forceNonNullable: Boolean = false,
    ): String {
        val serialName = descriptor.serialName.removeSuffix("?")
        registeredDescriptors[serialName]?.let { return it }
        val componentName = simpleName(serialName)
        if (!schemas.containsKey(componentName)) {
            registeredDescriptors[serialName] = componentName
            schemas[componentName] = emptyMap<String, Any?>()
            schemas[componentName] = schemaFor(
                descriptor = descriptor,
                rootName = componentName,
                includeNullable = !forceNonNullable,
            )
        }
        return componentName
    }

    private fun manualUnionSchema(componentName: String): Map<String, Any?>? =
        when (componentName) {
            "KastRenameRequest" -> discriminatedUnion(
                "RENAME_BY_SYMBOL_REQUEST" to "KastRenameBySymbolRequest",
                "RENAME_BY_OFFSET_REQUEST" to "KastRenameByOffsetRequest",
            )
            "KastWriteAndValidateRequest" -> discriminatedUnion(
                "CREATE_FILE_REQUEST" to "KastWriteAndValidateCreateFileRequest",
                "INSERT_AT_OFFSET_REQUEST" to "KastWriteAndValidateInsertAtOffsetRequest",
                "REPLACE_RANGE_REQUEST" to "KastWriteAndValidateReplaceRangeRequest",
            )
            "KastRenameQuery" -> discriminatedUnion(
                "RENAME_BY_SYMBOL_REQUEST" to "KastRenameBySymbolQuery",
                "RENAME_BY_OFFSET_REQUEST" to "KastRenameByOffsetQuery",
            )
            "KastWriteAndValidateQuery" -> discriminatedUnion(
                "CREATE_FILE_REQUEST" to "KastWriteAndValidateCreateFileQuery",
                "INSERT_AT_OFFSET_REQUEST" to "KastWriteAndValidateInsertAtOffsetQuery",
                "REPLACE_RANGE_REQUEST" to "KastWriteAndValidateReplaceRangeQuery",
            )
            "KastResolveResponse" -> discriminatedUnion(
                "RESOLVE_SUCCESS" to "KastResolveSuccessResponse",
                "RESOLVE_FAILURE" to "KastResolveFailureResponse",
            )
            "KastReferencesResponse" -> discriminatedUnion(
                "REFERENCES_SUCCESS" to "KastReferencesSuccessResponse",
                "REFERENCES_FAILURE" to "KastReferencesFailureResponse",
            )
            "KastCallersResponse" -> discriminatedUnion(
                "CALLERS_SUCCESS" to "KastCallersSuccessResponse",
                "CALLERS_FAILURE" to "KastCallersFailureResponse",
            )
            "KastDiagnosticsResponse" -> discriminatedUnion(
                "DIAGNOSTICS_SUCCESS" to "KastDiagnosticsSuccessResponse",
                "DIAGNOSTICS_FAILURE" to "KastDiagnosticsFailureResponse",
            )
            "KastRenameResponse" -> discriminatedUnion(
                "RENAME_SUCCESS" to "KastRenameSuccessResponse",
                "RENAME_FAILURE" to "KastRenameFailureResponse",
            )
            "KastScaffoldResponse" -> discriminatedUnion(
                "SCAFFOLD_SUCCESS" to "KastScaffoldSuccessResponse",
                "SCAFFOLD_FAILURE" to "KastScaffoldFailureResponse",
            )
            "KastWorkspaceFilesResponse" -> discriminatedUnion(
                "WORKSPACE_FILES_SUCCESS" to "KastWorkspaceFilesSuccessResponse",
                "WORKSPACE_FILES_FAILURE" to "KastWorkspaceFilesFailureResponse",
            )
            "KastWriteAndValidateResponse" -> discriminatedUnion(
                "WRITE_AND_VALIDATE_SUCCESS" to "KastWriteAndValidateSuccessResponse",
                "WRITE_AND_VALIDATE_FAILURE" to "KastWriteAndValidateFailureResponse",
            )
            else -> null
        }

    private fun discriminatedUnion(vararg mappingEntries: Pair<String, String>): Map<String, Any?> {
        val mapping = linkedMapOf<String, String>()
        val refs = mutableListOf<Any?>()
        mappingEntries.forEach { (value, component) ->
            mapping[value] = "#/components/schemas/$component"
            refs += linkedMapOf("\$ref" to "#/components/schemas/$component")
        }
        return linkedMapOf(
            "oneOf" to refs,
            "discriminator" to linkedMapOf(
                "propertyName" to "type",
                "mapping" to mapping,
            ),
        )
    }

    private fun discriminatorValue(componentName: String?): String? =
        when (componentName) {
            "KastRenameBySymbolRequest" -> "RENAME_BY_SYMBOL_REQUEST"
            "KastRenameByOffsetRequest" -> "RENAME_BY_OFFSET_REQUEST"
            "KastWriteAndValidateCreateFileRequest" -> "CREATE_FILE_REQUEST"
            "KastWriteAndValidateInsertAtOffsetRequest" -> "INSERT_AT_OFFSET_REQUEST"
            "KastWriteAndValidateReplaceRangeRequest" -> "REPLACE_RANGE_REQUEST"
            "KastRenameBySymbolQuery" -> "RENAME_BY_SYMBOL_REQUEST"
            "KastRenameByOffsetQuery" -> "RENAME_BY_OFFSET_REQUEST"
            "KastWriteAndValidateCreateFileQuery" -> "CREATE_FILE_REQUEST"
            "KastWriteAndValidateInsertAtOffsetQuery" -> "INSERT_AT_OFFSET_REQUEST"
            "KastWriteAndValidateReplaceRangeQuery" -> "REPLACE_RANGE_REQUEST"
            "KastResolveSuccessResponse" -> "RESOLVE_SUCCESS"
            "KastResolveFailureResponse" -> "RESOLVE_FAILURE"
            "KastReferencesSuccessResponse" -> "REFERENCES_SUCCESS"
            "KastReferencesFailureResponse" -> "REFERENCES_FAILURE"
            "KastCallersSuccessResponse" -> "CALLERS_SUCCESS"
            "KastCallersFailureResponse" -> "CALLERS_FAILURE"
            "KastDiagnosticsSuccessResponse" -> "DIAGNOSTICS_SUCCESS"
            "KastDiagnosticsFailureResponse" -> "DIAGNOSTICS_FAILURE"
            "KastRenameSuccessResponse" -> "RENAME_SUCCESS"
            "KastRenameFailureResponse" -> "RENAME_FAILURE"
            "KastScaffoldSuccessResponse" -> "SCAFFOLD_SUCCESS"
            "KastScaffoldFailureResponse" -> "SCAFFOLD_FAILURE"
            "KastWorkspaceFilesSuccessResponse" -> "WORKSPACE_FILES_SUCCESS"
            "KastWorkspaceFilesFailureResponse" -> "WORKSPACE_FILES_FAILURE"
            "KastWriteAndValidateSuccessResponse" -> "WRITE_AND_VALIDATE_SUCCESS"
            "KastWriteAndValidateFailureResponse" -> "WRITE_AND_VALIDATE_FAILURE"
            else -> null
        }

    private fun primitiveSchema(kind: PrimitiveKind): Map<String, Any?> =
        when (kind) {
            PrimitiveKind.BOOLEAN -> linkedMapOf("type" to "boolean")
            PrimitiveKind.BYTE,
            PrimitiveKind.SHORT,
            PrimitiveKind.INT,
            -> linkedMapOf("type" to "integer", "format" to "int32")
            PrimitiveKind.LONG -> linkedMapOf("type" to "integer", "format" to "int64")
            PrimitiveKind.FLOAT -> linkedMapOf("type" to "number", "format" to "float")
            PrimitiveKind.DOUBLE -> linkedMapOf("type" to "number", "format" to "double")
            PrimitiveKind.CHAR,
            PrimitiveKind.STRING,
            -> linkedMapOf("type" to "string")
        }

    private fun simpleName(serialName: String): String = serialName.substringAfterLast('.')
}

fun main(args: Array<String>) {
    val target = args.firstOrNull()?.let(Path::of)
        ?: Path.of(".agents/skills/kast/fixtures/maintenance/references/wrapper-openapi.yaml")
    Files.writeString(target, WrapperOpenApiDocument.renderYaml())
}

private fun renderYaml(
    value: Any?,
    indent: Int = 0,
): String = when (value) {
    null -> "${" ".repeat(indent)}null\n"
    is Map<*, *> -> {
        if (value.isEmpty()) {
            "${" ".repeat(indent)}{}\n"
        } else {
            buildString {
                value.forEach { (rawKey, rawEntryValue) ->
                    val key = renderKey(rawKey.toString())
                    when (rawEntryValue) {
                        is Map<*, *>,
                        is List<*>,
                        -> {
                            append(" ".repeat(indent))
                            append(key)
                            append(":\n")
                            append(renderYaml(rawEntryValue, indent + 2))
                        }
                        else -> {
                            append(" ".repeat(indent))
                            append(key)
                            append(": ")
                            append(renderScalar(rawEntryValue))
                            append('\n')
                        }
                    }
                }
            }
        }
    }
    is List<*> -> {
        if (value.isEmpty()) {
            "${" ".repeat(indent)}[]\n"
        } else {
            buildString {
                value.forEach { entry ->
                    when (entry) {
                        is Map<*, *>,
                        is List<*>,
                        -> {
                            append(" ".repeat(indent))
                            append("-")
                            val rendered = renderYaml(entry, indent + 2)
                            val lines = rendered.lines().filter { it.isNotEmpty() }
                            if (lines.isEmpty()) {
                                append(" {}\n")
                            } else {
                                append('\n')
                                lines.forEach { append(it).append('\n') }
                            }
                        }
                        else -> {
                            append(" ".repeat(indent))
                            append("- ")
                            append(renderScalar(entry))
                            append('\n')
                        }
                    }
                }
            }
        }
    }
    else -> "${" ".repeat(indent)}${renderScalar(value)}\n"
}

private fun renderKey(key: String): String =
    if (key.matches(Regex("[A-Za-z_\\-\\$][A-Za-z0-9_\\-\\.\\$]*"))) key else "\"$key\""

private fun renderScalar(value: Any?): String = when (value) {
    null -> "null"
    is String -> renderString(value)
    is Boolean,
    is Int,
    is Long,
    is Float,
    is Double,
    -> value.toString()
    else -> renderString(value.toString())
}

private fun renderString(value: String): String =
    if (
        value.matches(Regex("[A-Za-z0-9_./:#\\- ]+")) &&
        !value.contains('\n') &&
        !value.startsWith(" ") &&
        !value.startsWith("#")
    ) {
        value
    } else {
        buildString {
            append('"')
            value.forEach { char ->
                when (char) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> append(char)
                }
            }
            append('"')
        }
    }
