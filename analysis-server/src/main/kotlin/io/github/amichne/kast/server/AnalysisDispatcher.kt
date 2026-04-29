package io.github.amichne.kast.server

import io.github.amichne.kast.api.contract.AnalysisBackend
import io.github.amichne.kast.api.protocol.AnalysisException
import io.github.amichne.kast.api.protocol.ApiErrorResponse
import io.github.amichne.kast.api.contract.query.ApplyEditsQuery
import io.github.amichne.kast.api.contract.result.ApplyEditsResult
import io.github.amichne.kast.api.contract.BackendCapabilities
import io.github.amichne.kast.api.contract.query.CallHierarchyQuery
import io.github.amichne.kast.api.contract.result.CallHierarchyResult
import io.github.amichne.kast.api.protocol.CapabilityNotSupportedException
import io.github.amichne.kast.api.contract.query.CodeActionsQuery
import io.github.amichne.kast.api.contract.result.CodeActionsResult
import io.github.amichne.kast.api.contract.query.CompletionsQuery
import io.github.amichne.kast.api.contract.result.CompletionsResult
import io.github.amichne.kast.api.contract.query.DiagnosticsQuery
import io.github.amichne.kast.api.contract.result.DiagnosticsResult
import io.github.amichne.kast.api.contract.query.FileOutlineQuery
import io.github.amichne.kast.api.contract.result.FileOutlineResult
import io.github.amichne.kast.api.contract.HealthResponse
import io.github.amichne.kast.api.contract.query.ImportOptimizeQuery
import io.github.amichne.kast.api.contract.result.ImportOptimizeResult
import io.github.amichne.kast.api.contract.query.ImplementationsQuery
import io.github.amichne.kast.api.contract.result.ImplementationsResult
import io.github.amichne.kast.api.protocol.JSON_RPC_INTERNAL_ERROR
import io.github.amichne.kast.api.protocol.JSON_RPC_INVALID_REQUEST
import io.github.amichne.kast.api.protocol.JSON_RPC_METHOD_NOT_FOUND
import io.github.amichne.kast.api.protocol.JSON_RPC_PARSE_ERROR
import io.github.amichne.kast.api.protocol.JSON_RPC_SERVER_ERROR_BASE
import io.github.amichne.kast.api.protocol.JSON_RPC_VERSION
import io.github.amichne.kast.api.protocol.JsonRpcErrorObject
import io.github.amichne.kast.api.protocol.JsonRpcErrorResponse
import io.github.amichne.kast.api.protocol.JsonRpcRequest
import io.github.amichne.kast.api.protocol.JsonRpcSuccessResponse
import io.github.amichne.kast.api.contract.MutationCapability
import io.github.amichne.kast.api.contract.PageInfo
import io.github.amichne.kast.api.contract.ReadCapability
import io.github.amichne.kast.api.contract.query.RefreshQuery
import io.github.amichne.kast.api.contract.result.RefreshResult
import io.github.amichne.kast.api.contract.query.ReferencesQuery
import io.github.amichne.kast.api.contract.result.ReferencesResult
import io.github.amichne.kast.api.contract.query.RenameQuery
import io.github.amichne.kast.api.contract.result.RenameResult
import io.github.amichne.kast.api.contract.RuntimeStatusResponse
import io.github.amichne.kast.api.contract.SemanticInsertionQuery
import io.github.amichne.kast.api.contract.SemanticInsertionResult
import io.github.amichne.kast.api.contract.query.SymbolQuery
import io.github.amichne.kast.api.contract.result.SymbolResult
import io.github.amichne.kast.api.contract.query.TypeHierarchyQuery
import io.github.amichne.kast.api.contract.result.TypeHierarchyResult
import io.github.amichne.kast.api.protocol.ValidationException
import io.github.amichne.kast.api.contract.query.WorkspaceFilesQuery
import io.github.amichne.kast.api.contract.result.WorkspaceFilesResult
import io.github.amichne.kast.api.contract.query.WorkspaceSymbolQuery
import io.github.amichne.kast.api.contract.result.WorkspaceSymbolResult
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import java.nio.file.Path
import java.util.UUID
import kotlin.time.Duration.Companion.milliseconds

class AnalysisDispatcher(
    private val backend: AnalysisBackend,
    private val config: AnalysisServerConfig,
    private val json: Json = Json {
        encodeDefaults = true
        explicitNulls = false
        prettyPrint = false
    },
) {
    suspend fun dispatch(request: JsonRpcRequest): String {
        if (request.jsonrpc != JSON_RPC_VERSION || request.method.isBlank()) {
            return json.encodeToString(
                JsonRpcErrorResponse(
                    error = JsonRpcErrorObject(
                        code = JSON_RPC_INVALID_REQUEST,
                        message = "Invalid JSON-RPC request",
                    ),
                    id = request.id,
                ),
            )
        }

        return try {
            val result = withTimeout(config.requestTimeoutMillis.milliseconds) {
                dispatchMethod(request.method, request.params)
            }
            json.encodeToString(
                JsonRpcSuccessResponse(
                    id = request.id,
                    result = result,
                ),
            )
        } catch (exception: AnalysisException) {
            json.encodeToString(
                JsonRpcErrorResponse(
                    id = request.id,
                    error = exception.toJsonRpcError(request.id),
                ),
            )
        } catch (exception: UnknownRpcMethodException) {
            json.encodeToString(
                JsonRpcErrorResponse(
                    id = request.id,
                    error = JsonRpcErrorObject(
                        code = JSON_RPC_METHOD_NOT_FOUND,
                        message = exception.message ?: "Unknown JSON-RPC method",
                    ),
                ),
            )
        } catch (exception: Throwable) {
            json.encodeToString(
                JsonRpcErrorResponse(
                    id = request.id,
                    error = JsonRpcErrorObject(
                        code = JSON_RPC_INTERNAL_ERROR,
                        message = exception.message ?: exception::class.java.simpleName,
                        data = ApiErrorResponse(
                            requestId = requestId(request.id),
                            code = "INTERNAL_ERROR",
                            message = exception.message ?: exception::class.java.simpleName,
                            retryable = false,
                        ),
                    ),
                ),
            )
        }
    }

    suspend fun dispatchRaw(requestText: String): String {
        val request = runCatching {
            json.decodeFromString(JsonRpcRequest.serializer(), requestText)
        }.getOrElse { exception ->
            return json.encodeToString(
                JsonRpcErrorResponse(
                    error = JsonRpcErrorObject(
                        code = JSON_RPC_PARSE_ERROR,
                        message = exception.message ?: "Failed to parse JSON-RPC request",
                    ),
                ),
            )
        }
        return dispatch(request)
    }

    private suspend fun dispatchMethod(
        method: String,
        params: JsonElement?,
    ): JsonElement {
        return when (method) {
            "health" -> encode(HealthResponse.serializer(), backend.health())
            "runtime/status" -> encode(RuntimeStatusResponse.serializer(), backend.runtimeStatus())
            "capabilities" -> encode(BackendCapabilities.serializer(), backend.capabilities())
            "symbol/resolve" -> encode(
                SymbolResult.serializer(),
                backend.resolveSymbol(
                    decodeParams(SymbolQuery.serializer(), params).also { query ->
                        validateFilePosition(query.position.filePath, query.position.offset)
                        requireReadCapability(ReadCapability.RESOLVE_SYMBOL)
                    },
                ),
            )

            "references" -> encode(
                ReferencesResult.serializer(),
                backend.findReferences(
                    decodeParams(ReferencesQuery.serializer(), params).also { query ->
                        validateFilePosition(query.position.filePath, query.position.offset)
                        requireReadCapability(ReadCapability.FIND_REFERENCES)
                    },
                ).withLimit(config.maxResults),
            )

            "call-hierarchy" -> encode(
                CallHierarchyResult.serializer(),
                backend.callHierarchy(
                    decodeParams(CallHierarchyQuery.serializer(), params).also { query ->
                        validateFilePosition(query.position.filePath, query.position.offset)
                        if (query.depth < 0) {
                            throw ValidationException("Call hierarchy depth must be greater than or equal to zero")
                        }
                        if (query.maxTotalCalls < 1) {
                            throw ValidationException("Call hierarchy maxTotalCalls must be greater than zero")
                        }
                        if (query.maxChildrenPerNode < 1) {
                            throw ValidationException("Call hierarchy maxChildrenPerNode must be greater than zero")
                        }
                        val timeoutMillis = query.timeoutMillis
                        if (timeoutMillis != null && timeoutMillis < 1) {
                            throw ValidationException("Call hierarchy timeoutMillis must be greater than zero when provided")
                        }
                        requireReadCapability(ReadCapability.CALL_HIERARCHY)
                    },
                ),
            )

            "type-hierarchy" -> encode(
                TypeHierarchyResult.serializer(),
                backend.typeHierarchy(
                    decodeParams(TypeHierarchyQuery.serializer(), params).also { query ->
                        validateFilePosition(query.position.filePath, query.position.offset)
                        if (query.depth < 0) {
                            throw ValidationException("Type hierarchy depth must be greater than or equal to zero")
                        }
                        if (query.maxResults < 1) {
                            throw ValidationException("Type hierarchy maxResults must be greater than zero")
                        }
                        requireReadCapability(ReadCapability.TYPE_HIERARCHY)
                    },
                ),
            )

            "semantic-insertion-point" -> encode(
                SemanticInsertionResult.serializer(),
                backend.semanticInsertionPoint(
                    decodeParams(SemanticInsertionQuery.serializer(), params).also { query ->
                        validateFilePosition(query.position.filePath, query.position.offset)
                        requireReadCapability(ReadCapability.SEMANTIC_INSERTION_POINT)
                    },
                ),
            )

            "diagnostics" -> encode(
                DiagnosticsResult.serializer(),
                backend.diagnostics(
                    decodeParams(DiagnosticsQuery.serializer(), params).also { query ->
                        if (query.filePaths.isEmpty()) {
                            throw ValidationException("At least one file path is required for diagnostics")
                        }
                        query.filePaths.forEach(::validateAbsoluteFilePath)
                        requireReadCapability(ReadCapability.DIAGNOSTICS)
                    },
                ).withLimit(config.maxResults),
            )

            "rename" -> encode(
                RenameResult.serializer(),
                backend.rename(
                    decodeParams(RenameQuery.serializer(), params).also { query ->
                        validateFilePosition(query.position.filePath, query.position.offset)
                        if (query.newName.isBlank()) {
                            throw ValidationException("The new symbol name must not be blank")
                        }
                        requireMutationCapability(MutationCapability.RENAME)
                    },
                ),
            )

            "imports/optimize" -> encode(
                ImportOptimizeResult.serializer(),
                backend.optimizeImports(
                    decodeParams(ImportOptimizeQuery.serializer(), params).also { query ->
                        if (query.filePaths.isEmpty()) {
                            throw ValidationException("At least one file path is required for import optimization")
                        }
                        query.filePaths.forEach(::validateAbsoluteFilePath)
                        requireMutationCapability(MutationCapability.OPTIMIZE_IMPORTS)
                    },
                ),
            )

            "edits/apply" -> encode(
                ApplyEditsResult.serializer(),
                backend.applyEdits(
                    decodeParams(ApplyEditsQuery.serializer(), params).also { query ->
                        query.fileOperations.forEach { operation ->
                            validateAbsoluteFilePath(operation.filePath)
                        }
                        requireMutationCapability(MutationCapability.APPLY_EDITS)
                        if (query.fileOperations.isNotEmpty()) {
                            requireMutationCapability(MutationCapability.FILE_OPERATIONS)
                        }
                    },
                ),
            )

            "workspace/refresh" -> encode(
                RefreshResult.serializer(),
                backend.refresh(
                    decodeParams(RefreshQuery.serializer(), params).also { query ->
                        query.filePaths.forEach(::validateAbsoluteFilePath)
                        requireMutationCapability(MutationCapability.REFRESH_WORKSPACE)
                    },
                ),
            )

            "file-outline" -> encode(
                FileOutlineResult.serializer(),
                backend.fileOutline(
                    decodeParams(FileOutlineQuery.serializer(), params).also { query ->
                        validateAbsoluteFilePath(query.filePath)
                        requireReadCapability(ReadCapability.FILE_OUTLINE)
                    },
                ),
            )

            "workspace-symbol" -> encode(
                WorkspaceSymbolResult.serializer(),
                backend.workspaceSymbolSearch(
                    decodeParams(WorkspaceSymbolQuery.serializer(), params).also { query ->
                        if (query.pattern.isBlank()) {
                            throw ValidationException("Symbol search pattern must not be blank")
                        }
                        if (query.maxResults < 1) {
                            throw ValidationException("Symbol search maxResults must be greater than zero")
                        }
                        requireReadCapability(ReadCapability.WORKSPACE_SYMBOL_SEARCH)
                    },
                ).withLimit(config.maxResults),
            )

            "workspace/files" -> encode(
                WorkspaceFilesResult.serializer(),
                backend.workspaceFiles(
                    decodeParams(WorkspaceFilesQuery.serializer(), params).also { query ->
                        val moduleName = query.moduleName
                        val maxFilesPerModule = query.maxFilesPerModule
                        if (moduleName != null && moduleName.isBlank()) {
                            throw ValidationException("Workspace files moduleName must not be blank when provided")
                        }
                        if (maxFilesPerModule != null && maxFilesPerModule < 1) {
                            throw ValidationException("Workspace files maxFilesPerModule must be greater than zero")
                        }
                        if (maxFilesPerModule != null && maxFilesPerModule > config.maxResults) {
                            throw ValidationException(
                                "Workspace files maxFilesPerModule must be less than or equal to server maxResults (${config.maxResults})",
                            )
                        }
                        requireReadCapability(ReadCapability.WORKSPACE_FILES)
                    },
                ),
            )

            "implementations" -> encode(
                ImplementationsResult.serializer(),
                backend.implementations(
                    decodeParams(ImplementationsQuery.serializer(), params).also { query ->
                        validateFilePosition(query.position.filePath, query.position.offset)
                        if (query.maxResults < 1) {
                            throw ValidationException("Implementations maxResults must be greater than zero")
                        }
                        requireReadCapability(ReadCapability.IMPLEMENTATIONS)
                    },
                ),
            )

            "code-actions" -> encode(
                CodeActionsResult.serializer(),
                backend.codeActions(
                    decodeParams(CodeActionsQuery.serializer(), params).also { query ->
                        validateFilePosition(query.position.filePath, query.position.offset)
                        requireReadCapability(ReadCapability.CODE_ACTIONS)
                    },
                ),
            )

            "completions" -> encode(
                CompletionsResult.serializer(),
                backend.completions(
                    decodeParams(CompletionsQuery.serializer(), params).also { query ->
                        validateFilePosition(query.position.filePath, query.position.offset)
                        if (query.maxResults < 1) {
                            throw ValidationException("Completions maxResults must be greater than zero")
                        }
                        requireReadCapability(ReadCapability.COMPLETIONS)
                    },
                ),
            )

            else -> throw UnknownRpcMethodException(method)
        }
    }

    private suspend fun requireReadCapability(capability: ReadCapability) {
        val capabilities = backend.capabilities()
        if (!capabilities.readCapabilities.contains(capability)) {
            throw CapabilityNotSupportedException(
                capability = capability.name,
                message = "The backend does not advertise $capability",
            )
        }
    }

    private suspend fun requireMutationCapability(capability: MutationCapability) {
        val capabilities = backend.capabilities()
        if (!capabilities.mutationCapabilities.contains(capability)) {
            throw CapabilityNotSupportedException(
                capability = capability.name,
                message = "The backend does not advertise $capability",
            )
        }
    }

    private fun <T> decodeParams(
        serializer: KSerializer<T>,
        params: JsonElement?,
    ): T = params?.let { json.decodeFromJsonElement(serializer, it) }
        ?: throw ValidationException("The JSON-RPC request is missing params")

    private fun <T> encode(
        serializer: KSerializer<T>,
        value: T,
    ): JsonElement = json.encodeToJsonElement(serializer, value)
}

private class UnknownRpcMethodException(
    method: String,
) : RuntimeException("Unknown JSON-RPC method: $method")

private fun AnalysisException.toJsonRpcError(id: JsonElement): JsonRpcErrorObject = JsonRpcErrorObject(
    code = JSON_RPC_SERVER_ERROR_BASE - statusCode,
    message = message,
    data = ApiErrorResponse(
        requestId = requestId(id),
        code = errorCode,
        message = message,
        retryable = retryable,
        details = details,
    ),
)

private fun requestId(id: JsonElement): String {
    return id.toString().takeIf { candidate ->
        candidate.isNotBlank() && candidate != JsonNull.toString()
    } ?: UUID.randomUUID().toString()
}

private fun ReferencesResult.withLimit(limit: Int): ReferencesResult {
    if (references.size <= limit) {
        return this
    }

    return copy(
        references = references.take(limit),
        page = PageInfo(
            truncated = true,
            nextPageToken = references[limit - 1].startOffset.toString(),
        ),
    )
}

private fun DiagnosticsResult.withLimit(limit: Int): DiagnosticsResult {
    if (diagnostics.size <= limit) {
        return this
    }

    return copy(
        diagnostics = diagnostics.take(limit),
        page = PageInfo(
            truncated = true,
            nextPageToken = diagnostics[limit - 1].location.startOffset.toString(),
        ),
    )
}

private fun WorkspaceSymbolResult.withLimit(limit: Int): WorkspaceSymbolResult {
    if (symbols.size <= limit) {
        return this
    }

    return copy(
        symbols = symbols.take(limit),
        page = PageInfo(
            truncated = true,
            nextPageToken = limit.toString(),
        ),
    )
}

private fun validateFilePosition(
    filePath: String,
    offset: Int,
) {
    validateAbsoluteFilePath(filePath)
    if (offset < 0) {
        throw ValidationException("Offsets must be greater than or equal to zero")
    }
}

private fun validateAbsoluteFilePath(filePath: String) {
    val path = Path.of(filePath)
    if (!path.isAbsolute) {
        throw ValidationException(
            message = "File paths must be absolute",
            details = mapOf("filePath" to filePath),
        )
    }
}
