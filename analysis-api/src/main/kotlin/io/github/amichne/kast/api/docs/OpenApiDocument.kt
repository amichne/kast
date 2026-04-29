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

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PolymorphicKind
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.StructureKind
import java.nio.file.Files
import java.nio.file.Path

/**
 * Generates an OpenAPI 3.1 specification for the Kast analysis daemon JSON-RPC API.
 *
 * Each JSON-RPC method dispatched by [AnalysisDispatcher] is modelled as a logical
 * `POST /rpc/{method}` operation whose request body is the `params` payload and whose
 * response body is the `result` payload. The JSON-RPC envelope and error format are
 * documented as separate schemas.
 *
 * The generated YAML is checked in at `docs/openapi.yaml` so Zensical can serve it,
 * and is validated by [AnalysisOpenApiDocumentTest] to prevent drift.
 */
object OpenApiDocument {

    fun renderYaml(): String {
        val registry = SchemaRegistry()
        registerSchemas(registry)
        return buildString {
            appendLine("openapi: 3.1.0")
            appendLine("info:")
            appendLine("  title: Kast Analysis API")
            appendLine("  version: \"$SCHEMA_VERSION.0.0\"")
            appendLine("  description: >")
            appendLine("    JSON-RPC 2.0 analysis protocol for the Kast daemon. Each operation is")
            appendLine("    modelled as a logical POST whose request body is the JSON-RPC params")
            appendLine("    payload and whose response body is the result payload. The actual")
            appendLine("    transport is line-delimited JSON-RPC over Unix domain sockets, stdio,")
            appendLine("    or TCP — not HTTP. Batch requests and JSON-RPC notifications are not")
            appendLine("    supported. Capability gating is noted per operation via")
            appendLine("    x-kast-required-capability.")
            appendLine("  license:")
            appendLine("    name: Apache-2.0")
            appendLine("    url: https://www.apache.org/licenses/LICENSE-2.0")
            appendLine("servers:")
            appendLine("  - url: jsonrpc://localhost")
            appendLine("    description: >")
            appendLine("      Logical server — the daemon binds a Unix domain socket, stdio pipe,")
            appendLine("      or TCP port, not an HTTP endpoint.")
            appendLine("tags:")
            appendLine("  - name: system")
            appendLine("    description: Health, status, and capability introspection")
            appendLine("  - name: read")
            appendLine("    description: Read-only analysis operations")
            appendLine("  - name: mutation")
            appendLine("    description: Operations that modify workspace state")
            appendLine("paths:")
            append(renderYaml(writePaths(), 2))
            appendLine("components:")
            appendLine("  schemas:")
            appendLine("    JsonRpcRequest:")
            appendLine("      type: object")
            appendLine("      required:")
            appendLine("        - jsonrpc")
            appendLine("        - method")
            appendLine("      properties:")
            appendLine("        jsonrpc:")
            appendLine("          type: string")
            appendLine("          const: \"2.0\"")
            appendLine("        method:")
            appendLine("          type: string")
            appendLine("        params:")
            appendLine("          description: Method-specific parameter object")
            appendLine("        id:")
            appendLine("          description: Request identifier (string, number, or null)")
            appendLine("      additionalProperties: false")
            appendLine("    JsonRpcSuccessResponse:")
            appendLine("      type: object")
            appendLine("      required:")
            appendLine("        - jsonrpc")
            appendLine("        - result")
            appendLine("      properties:")
            appendLine("        jsonrpc:")
            appendLine("          type: string")
            appendLine("          const: \"2.0\"")
            appendLine("        result:")
            appendLine("          description: Method-specific result object")
            appendLine("        id:")
            appendLine("          description: Echoed request identifier")
            appendLine("      additionalProperties: false")
            appendLine("    JsonRpcErrorResponse:")
            appendLine("      type: object")
            appendLine("      required:")
            appendLine("        - jsonrpc")
            appendLine("        - error")
            appendLine("      properties:")
            appendLine("        jsonrpc:")
            appendLine("          type: string")
            appendLine("          const: \"2.0\"")
            appendLine("        error:")
            appendLine("          \$ref: \"#/components/schemas/JsonRpcErrorObject\"")
            appendLine("        id:")
            appendLine("          description: Echoed request identifier")
            appendLine("      additionalProperties: false")
            append(renderYaml(registry.schemas, 4))
        }
    }

    private fun registerSchemas(registry: SchemaRegistry) {
        // JSON-RPC error envelope
        registry.register("JsonRpcErrorObject", JsonRpcErrorObject.serializer())
        registry.register("ApiErrorResponse", ApiErrorResponse.serializer())

        // System responses
        registry.register("HealthResponse", HealthResponse.serializer())
        registry.register("RuntimeStatusResponse", RuntimeStatusResponse.serializer())
        registry.register("BackendCapabilities", BackendCapabilities.serializer())

        // Shared types
        registry.register("FilePosition", FilePosition.serializer())
        registry.register("Location", Location.serializer())
        registry.register("Symbol", Symbol.serializer())
        registry.register("ParameterInfo", ParameterInfo.serializer())
        registry.register("PageInfo", PageInfo.serializer())
        registry.register("SearchScope", SearchScope.serializer())
        registry.register("DeclarationScope", DeclarationScope.serializer())
        registry.register("ServerLimits", ServerLimits.serializer())
        registry.register("TextEdit", TextEdit.serializer())
        registry.register("FileHash", FileHash.serializer())
        registry.register("OutlineSymbol", OutlineSymbol.serializer())
        registry.register("WorkspaceModule", WorkspaceModule.serializer())

        // Read queries & results
        registry.register("SymbolQuery", SymbolQuery.serializer())
        registry.register("SymbolResult", SymbolResult.serializer())
        registry.register("ReferencesQuery", ReferencesQuery.serializer())
        registry.register("ReferencesResult", ReferencesResult.serializer())
        registry.register("CallHierarchyQuery", CallHierarchyQuery.serializer())
        registry.register("CallHierarchyResult", CallHierarchyResult.serializer())
        registry.register("CallHierarchyStats", CallHierarchyStats.serializer())
        registry.register("CallNode", CallNode.serializer())
        registry.register("CallNodeTruncation", CallNodeTruncation.serializer())
        registry.register("TypeHierarchyQuery", TypeHierarchyQuery.serializer())
        registry.register("TypeHierarchyResult", TypeHierarchyResult.serializer())
        registry.register("TypeHierarchyNode", TypeHierarchyNode.serializer())
        registry.register("TypeHierarchyStats", TypeHierarchyStats.serializer())
        registry.register("TypeHierarchyTruncation", TypeHierarchyTruncation.serializer())
        registry.register("SemanticInsertionQuery", SemanticInsertionQuery.serializer())
        registry.register("SemanticInsertionResult", SemanticInsertionResult.serializer())
        registry.register("DiagnosticsQuery", DiagnosticsQuery.serializer())
        registry.register("DiagnosticsResult", DiagnosticsResult.serializer())
        registry.register("Diagnostic", Diagnostic.serializer())
        registry.register("FileOutlineQuery", FileOutlineQuery.serializer())
        registry.register("FileOutlineResult", FileOutlineResult.serializer())
        registry.register("WorkspaceSymbolQuery", WorkspaceSymbolQuery.serializer())
        registry.register("WorkspaceSymbolResult", WorkspaceSymbolResult.serializer())
        registry.register("WorkspaceFilesQuery", WorkspaceFilesQuery.serializer())
        registry.register("WorkspaceFilesResult", WorkspaceFilesResult.serializer())
        registry.register("ImplementationsQuery", ImplementationsQuery.serializer())
        registry.register("ImplementationsResult", ImplementationsResult.serializer())
        registry.register("CodeActionsQuery", CodeActionsQuery.serializer())
        registry.register("CodeActionsResult", CodeActionsResult.serializer())
        registry.register("CodeAction", CodeAction.serializer())
        registry.register("CompletionsQuery", CompletionsQuery.serializer())
        registry.register("CompletionsResult", CompletionsResult.serializer())
        registry.register("CompletionItem", CompletionItem.serializer())

        // Mutation queries & results
        registry.register("RenameQuery", RenameQuery.serializer())
        registry.register("RenameResult", RenameResult.serializer())
        registry.register("ImportOptimizeQuery", ImportOptimizeQuery.serializer())
        registry.register("ImportOptimizeResult", ImportOptimizeResult.serializer())
        registry.register("ApplyEditsQuery", ApplyEditsQuery.serializer())
        registry.register("ApplyEditsResult", ApplyEditsResult.serializer())
        registry.register("RefreshQuery", RefreshQuery.serializer())
        registry.register("RefreshResult", RefreshResult.serializer())

        // FileOperation sealed hierarchy
        registry.register("FileOperation", FileOperation.serializer())
        registry.register("FileOperation.CreateFile", FileOperation.CreateFile.serializer())
        registry.register("FileOperation.DeleteFile", FileOperation.DeleteFile.serializer())
    }

    private fun writePaths(): Map<String, Any?> = linkedMapOf(
        // System
        "/rpc/health" to systemMethod(
            operationId = "health",
            summary = "Basic health check",
            method = "health",
            responseSchema = "HealthResponse",
        ),
        "/rpc/runtime-status" to systemMethod(
            operationId = "runtimeStatus",
            summary = "Detailed runtime state including indexing progress",
            method = "runtime/status",
            responseSchema = "RuntimeStatusResponse",
        ),
        "/rpc/capabilities" to systemMethod(
            operationId = "capabilities",
            summary = "Advertised read and mutation capabilities",
            method = "capabilities",
            responseSchema = "BackendCapabilities",
        ),

        // Read operations
        "/rpc/symbol/resolve" to readMethod(
            operationId = "resolveSymbol",
            summary = "Resolve the symbol at a file position",
            method = "symbol/resolve",
            requestSchema = "SymbolQuery",
            responseSchema = "SymbolResult",
            capability = "RESOLVE_SYMBOL",
        ),
        "/rpc/references" to readMethod(
            operationId = "findReferences",
            summary = "Find all references to the symbol at a file position",
            method = "references",
            requestSchema = "ReferencesQuery",
            responseSchema = "ReferencesResult",
            capability = "FIND_REFERENCES",
        ),
        "/rpc/call-hierarchy" to readMethod(
            operationId = "callHierarchy",
            summary = "Expand a bounded incoming or outgoing call tree",
            method = "call-hierarchy",
            requestSchema = "CallHierarchyQuery",
            responseSchema = "CallHierarchyResult",
            capability = "CALL_HIERARCHY",
        ),
        "/rpc/type-hierarchy" to readMethod(
            operationId = "typeHierarchy",
            summary = "Expand supertypes and subtypes from a resolved symbol",
            method = "type-hierarchy",
            requestSchema = "TypeHierarchyQuery",
            responseSchema = "TypeHierarchyResult",
            capability = "TYPE_HIERARCHY",
        ),
        "/rpc/semantic-insertion-point" to readMethod(
            operationId = "semanticInsertionPoint",
            summary = "Find the best insertion point for a new declaration",
            method = "semantic-insertion-point",
            requestSchema = "SemanticInsertionQuery",
            responseSchema = "SemanticInsertionResult",
            capability = "SEMANTIC_INSERTION_POINT",
        ),
        "/rpc/diagnostics" to readMethod(
            operationId = "diagnostics",
            summary = "Run compilation diagnostics for one or more files",
            method = "diagnostics",
            requestSchema = "DiagnosticsQuery",
            responseSchema = "DiagnosticsResult",
            capability = "DIAGNOSTICS",
        ),
        "/rpc/file-outline" to readMethod(
            operationId = "fileOutline",
            summary = "Get a hierarchical symbol outline for a single file",
            method = "file-outline",
            requestSchema = "FileOutlineQuery",
            responseSchema = "FileOutlineResult",
            capability = "FILE_OUTLINE",
        ),
        "/rpc/workspace-symbol" to readMethod(
            operationId = "workspaceSymbolSearch",
            summary = "Search the workspace for symbols by name pattern",
            method = "workspace-symbol",
            requestSchema = "WorkspaceSymbolQuery",
            responseSchema = "WorkspaceSymbolResult",
            capability = "WORKSPACE_SYMBOL_SEARCH",
        ),
        "/rpc/workspace/files" to readMethod(
            operationId = "workspaceFiles",
            summary = "List workspace modules and their source files",
            method = "workspace/files",
            requestSchema = "WorkspaceFilesQuery",
            responseSchema = "WorkspaceFilesResult",
            capability = "WORKSPACE_FILES",
        ),
        "/rpc/implementations" to readMethod(
            operationId = "implementations",
            summary = "Find concrete implementations and subclasses for a declaration",
            method = "implementations",
            requestSchema = "ImplementationsQuery",
            responseSchema = "ImplementationsResult",
            capability = "IMPLEMENTATIONS",
        ),
        "/rpc/code-actions" to readMethod(
            operationId = "codeActions",
            summary = "Return available code actions at a file position",
            method = "code-actions",
            requestSchema = "CodeActionsQuery",
            responseSchema = "CodeActionsResult",
            capability = "CODE_ACTIONS",
        ),
        "/rpc/completions" to readMethod(
            operationId = "completions",
            summary = "Return completion candidates available at a file position",
            method = "completions",
            requestSchema = "CompletionsQuery",
            responseSchema = "CompletionsResult",
            capability = "COMPLETIONS",
        ),

        // Mutation operations
        "/rpc/rename" to mutationMethod(
            operationId = "rename",
            summary = "Plan a symbol rename (dry-run by default)",
            method = "rename",
            requestSchema = "RenameQuery",
            responseSchema = "RenameResult",
            capability = "RENAME",
        ),
        "/rpc/imports/optimize" to mutationMethod(
            operationId = "optimizeImports",
            summary = "Optimize imports for one or more files",
            method = "imports/optimize",
            requestSchema = "ImportOptimizeQuery",
            responseSchema = "ImportOptimizeResult",
            capability = "OPTIMIZE_IMPORTS",
        ),
        "/rpc/edits/apply" to mutationMethod(
            operationId = "applyEdits",
            summary = "Apply a prepared edit plan with file-hash conflict detection",
            method = "edits/apply",
            requestSchema = "ApplyEditsQuery",
            responseSchema = "ApplyEditsResult",
            capability = "APPLY_EDITS",
            extraExtensions = mapOf(
                "x-kast-conditional-capability" to "FILE_OPERATIONS — required when fileOperations is non-empty",
            ),
        ),
        "/rpc/workspace/refresh" to mutationMethod(
            operationId = "refreshWorkspace",
            summary = "Force a targeted or full workspace state refresh",
            method = "workspace/refresh",
            requestSchema = "RefreshQuery",
            responseSchema = "RefreshResult",
            capability = "REFRESH_WORKSPACE",
        ),
    )

    private fun systemMethod(
        operationId: String,
        summary: String,
        method: String,
        responseSchema: String,
    ): Map<String, Any?> = linkedMapOf(
        "post" to linkedMapOf(
            "operationId" to operationId,
            "summary" to summary,
            "tags" to listOf("system"),
            "x-jsonrpc-method" to method,
            "responses" to linkedMapOf(
                "200" to linkedMapOf(
                    "description" to "JSON-RPC success result",
                    "content" to linkedMapOf(
                        "application/json" to linkedMapOf(
                            "schema" to ref(responseSchema),
                        ),
                    ),
                ),
                "default" to errorResponse(),
            ),
        ),
    )

    private fun readMethod(
        operationId: String,
        summary: String,
        method: String,
        requestSchema: String,
        responseSchema: String,
        capability: String,
    ): Map<String, Any?> = linkedMapOf(
        "post" to linkedMapOf(
            "operationId" to operationId,
            "summary" to summary,
            "tags" to listOf("read"),
            "x-jsonrpc-method" to method,
            "x-kast-required-capability" to capability,
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
                    "description" to "JSON-RPC success result",
                    "content" to linkedMapOf(
                        "application/json" to linkedMapOf(
                            "schema" to ref(responseSchema),
                        ),
                    ),
                ),
                "default" to errorResponse(),
            ),
        ),
    )

    private fun mutationMethod(
        operationId: String,
        summary: String,
        method: String,
        requestSchema: String,
        responseSchema: String,
        capability: String,
        extraExtensions: Map<String, String> = emptyMap(),
    ): Map<String, Any?> = linkedMapOf(
        "post" to linkedMapOf(
            "operationId" to operationId,
            "summary" to summary,
            "tags" to listOf("mutation"),
            "x-jsonrpc-method" to method,
            "x-kast-required-capability" to capability,
        ).also { op ->
            extraExtensions.forEach { (k, v) -> op[k] = v }
            op["requestBody"] = linkedMapOf(
                "required" to true,
                "content" to linkedMapOf(
                    "application/json" to linkedMapOf(
                        "schema" to ref(requestSchema),
                    ),
                ),
            )
            op["responses"] = linkedMapOf(
                "200" to linkedMapOf(
                    "description" to "JSON-RPC success result",
                    "content" to linkedMapOf(
                        "application/json" to linkedMapOf(
                            "schema" to ref(responseSchema),
                        ),
                    ),
                ),
                "default" to errorResponse(),
            )
        },
    )

    private fun errorResponse(): Map<String, Any?> = linkedMapOf(
        "description" to "JSON-RPC error response",
        "content" to linkedMapOf(
            "application/json" to linkedMapOf(
                "schema" to ref("JsonRpcErrorResponse"),
            ),
        ),
    )

    private fun ref(name: String): Map<String, Any?> = linkedMapOf("\$ref" to "#/components/schemas/$name")
}

@OptIn(ExperimentalSerializationApi::class)
internal class SchemaRegistry {
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
        manualUnionSchema(rootName ?: simpleName(descriptor.serialName))?.let { return it }

        val schema = when (descriptor.kind) {
            is PrimitiveKind -> primitiveSchema(descriptor.kind as PrimitiveKind)
            StructureKind.CLASS, StructureKind.OBJECT -> objectSchema(descriptor)
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
            linkedMapOf("anyOf" to listOf(schema, linkedMapOf("type" to "null")))
        } else {
            schema
        }
    }

    private fun objectSchema(descriptor: SerialDescriptor): Map<String, Any?> {
        val properties = linkedMapOf<String, Any?>()
        val required = mutableListOf<String>()
        repeat(descriptor.elementsCount) { index ->
            val name = descriptor.getElementName(index)
            properties[name] = inlineSchema(descriptor.getElementDescriptor(index))
            if (!descriptor.isElementOptional(index)) {
                required += name
            }
        }
        return linkedMapOf<String, Any?>(
            "type" to "object",
            "properties" to properties,
            "additionalProperties" to false,
        ).also { if (required.isNotEmpty()) it["required"] = required }
    }

    private fun inlineSchema(descriptor: SerialDescriptor): Any? =
        when (descriptor.kind) {
            is PrimitiveKind -> schemaFor(descriptor)
            StructureKind.LIST -> schemaFor(descriptor)
            StructureKind.MAP -> schemaFor(descriptor)
            SerialKind.ENUM, StructureKind.CLASS, StructureKind.OBJECT, PolymorphicKind.SEALED -> {
                val refMap = linkedMapOf(
                    "\$ref" to "#/components/schemas/${ensureRegistered(descriptor, forceNonNullable = true)}",
                )
                if (descriptor.isNullable) {
                    linkedMapOf("anyOf" to listOf(refMap, linkedMapOf("type" to "null")))
                } else {
                    refMap
                }
            }
            else -> linkedMapOf("type" to "object")
        }

    private fun ensureRegistered(descriptor: SerialDescriptor, forceNonNullable: Boolean = false): String {
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
            "FileOperation" -> discriminatedUnion(
                "CREATE_FILE" to "FileOperation.CreateFile",
                "DELETE_FILE" to "FileOperation.DeleteFile",
            )
            "FileOperation.CreateFile" -> subtypeWithDiscriminator(
                FileOperation.CreateFile.serializer(),
                discriminatorValue = "CREATE_FILE",
            )
            "FileOperation.DeleteFile" -> subtypeWithDiscriminator(
                FileOperation.DeleteFile.serializer(),
                discriminatorValue = "DELETE_FILE",
            )
            else -> null
        }

    private fun subtypeWithDiscriminator(
        serializer: KSerializer<*>,
        discriminatorValue: String,
    ): Map<String, Any?> {
        val base = objectSchema(serializer.descriptor) as LinkedHashMap<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val props = base["properties"] as LinkedHashMap<String, Any?>
        val typeProperty = linkedMapOf<String, Any?>("type" to "string", "const" to discriminatorValue)
        val withType = linkedMapOf<String, Any?>("type" to typeProperty)
        withType.putAll(props)
        base["properties"] = withType
        @Suppress("UNCHECKED_CAST")
        val required = (base["required"] as? MutableList<String>) ?: mutableListOf()
        if ("type" !in required) required.add(0, "type")
        base["required"] = required
        return base
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

    private fun primitiveSchema(kind: PrimitiveKind): Map<String, Any?> =
        when (kind) {
            PrimitiveKind.BOOLEAN -> linkedMapOf("type" to "boolean")
            PrimitiveKind.BYTE, PrimitiveKind.SHORT, PrimitiveKind.INT ->
                linkedMapOf("type" to "integer", "format" to "int32")
            PrimitiveKind.LONG -> linkedMapOf("type" to "integer", "format" to "int64")
            PrimitiveKind.FLOAT -> linkedMapOf("type" to "number", "format" to "float")
            PrimitiveKind.DOUBLE -> linkedMapOf("type" to "number", "format" to "double")
            PrimitiveKind.CHAR, PrimitiveKind.STRING -> linkedMapOf("type" to "string")
        }

    private fun simpleName(serialName: String): String = serialName.substringAfterLast('.')
}

/** Renders a YAML document fragment from a nested Map/List/scalar structure. */
internal fun renderYaml(value: Any?, indent: Int = 0): String = when (value) {
    null -> "${" ".repeat(indent)}null\n"
    is Map<*, *> -> {
        if (value.isEmpty()) {
            "${" ".repeat(indent)}{}\n"
        } else {
            buildString {
                value.forEach { (rawKey, rawEntryValue) ->
                    val key = renderKey(rawKey.toString())
                    when (rawEntryValue) {
                        is Map<*, *>, is List<*> -> {
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
                        is Map<*, *> -> {
                            val entries = entry.entries.toList()
                            if (entries.isNotEmpty()) {
                                val (firstKey, firstValue) = entries.first()
                                val key = renderKey(firstKey.toString())
                                append(" ".repeat(indent))
                                append("- ")
                                when (firstValue) {
                                    is Map<*, *>, is List<*> -> {
                                        append(key)
                                        append(":\n")
                                        append(renderYaml(firstValue, indent + 4))
                                    }
                                    else -> {
                                        append(key)
                                        append(": ")
                                        append(renderScalar(firstValue))
                                        append('\n')
                                    }
                                }
                                entries.drop(1).forEach { (restKey, restValue) ->
                                    val rk = renderKey(restKey.toString())
                                    append(" ".repeat(indent + 2))
                                    when (restValue) {
                                        is Map<*, *>, is List<*> -> {
                                            append(rk)
                                            append(":\n")
                                            append(renderYaml(restValue, indent + 4))
                                        }
                                        else -> {
                                            append(rk)
                                            append(": ")
                                            append(renderScalar(restValue))
                                            append('\n')
                                        }
                                    }
                                }
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

private fun renderScalar(value: Any?): String = when (value) {
    null -> "null"
    is Boolean -> value.toString()
    is Number -> value.toString()
    is String -> when {
        value.isEmpty() -> "\"\""
        value.startsWith("#") || value.contains(": ") || value.contains("\"") ||
            value == "true" || value == "false" || value == "null" ||
            value.contains("{") || value.contains("}") -> "\"${value.replace("\"", "\\\"")}\""
        else -> value
    }
    else -> value.toString()
}

private fun renderKey(key: String): String =
    if (key.startsWith("\$") || key.contains("/") || key.contains(" ")) "\"$key\"" else key

fun main(args: Array<String>) {
    val target = args.firstOrNull()?.let(Path::of)
        ?: Path.of("docs/openapi.yaml")
    Files.writeString(target, OpenApiDocument.renderYaml())
}
