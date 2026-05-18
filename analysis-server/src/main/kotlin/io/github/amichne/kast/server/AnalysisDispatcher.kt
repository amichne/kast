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
import io.github.amichne.kast.api.contract.PageableResult
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
import io.github.amichne.kast.api.contract.query.WorkspaceSearchQuery
import io.github.amichne.kast.api.contract.result.WorkspaceSearchResult
import io.github.amichne.kast.api.contract.query.WorkspaceSymbolQuery
import io.github.amichne.kast.api.contract.result.WorkspaceSymbolResult
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import java.util.UUID
import io.github.amichne.kast.api.validation.parsed
import io.github.amichne.kast.api.contract.skill.*

@Deprecated("Use RpcAnalysisDispatcher instead")
interface AnalysisDispatcher {
    suspend fun dispatch(request: JsonRpcRequest): String
    suspend fun dispatchRaw(requestText: String): String
}

class RpcAnalysisDispatcher(
    private val backend: AnalysisBackend,
    private val config: AnalysisServerConfig,
    private val json: Json = Json {
        encodeDefaults = true
        explicitNulls = false
        prettyPrint = false
    },
) : AnalysisDispatcher {
    private val skillRpc = SkillRpcOrchestrator(backend, config, json)

    override suspend fun dispatch(request: JsonRpcRequest): String {
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
            val result = withTimeout(config.effectiveRequestTimeoutMillis) {
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

    override suspend fun dispatchRaw(requestText: String): String {
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
            "raw/resolve" -> encode(
                SymbolResult.serializer(),
                backend.resolveSymbol(
                    decodeParams(SymbolQuery.serializer(), params).parsed().also {
                        requireReadCapability(ReadCapability.RESOLVE_SYMBOL)
                    },
                ),
            )

            "raw/references" -> encode(
                ReferencesResult.serializer(),
                backend.findReferences(
                    decodeParams(ReferencesQuery.serializer(), params).parsed().also {
                        requireReadCapability(ReadCapability.FIND_REFERENCES)
                    },
                ).withLimit(config.maxResults, ::referencePageToken),
            )

            "raw/call-hierarchy" -> encode(
                CallHierarchyResult.serializer(),
                backend.callHierarchy(
                    decodeParams(CallHierarchyQuery.serializer(), params).parsed().also {
                        requireReadCapability(ReadCapability.CALL_HIERARCHY)
                    },
                ),
            )

            "raw/type-hierarchy" -> encode(
                TypeHierarchyResult.serializer(),
                backend.typeHierarchy(
                    decodeParams(TypeHierarchyQuery.serializer(), params).parsed().also {
                        requireReadCapability(ReadCapability.TYPE_HIERARCHY)
                    },
                ),
            )

            "raw/semantic-insertion-point" -> encode(
                SemanticInsertionResult.serializer(),
                backend.semanticInsertionPoint(
                    decodeParams(SemanticInsertionQuery.serializer(), params).parsed().also {
                        requireReadCapability(ReadCapability.SEMANTIC_INSERTION_POINT)
                    },
                ),
            )

            "raw/diagnostics" -> encode(
                DiagnosticsResult.serializer(),
                backend.diagnostics(
                    decodeParams(DiagnosticsQuery.serializer(), params).parsed().also {
                        requireReadCapability(ReadCapability.DIAGNOSTICS)
                    },
                ).withLimit(config.maxResults, ::diagnosticPageToken),
            )

            "raw/rename" -> encode(
                RenameResult.serializer(),
                backend.rename(
                    decodeParams(RenameQuery.serializer(), params).parsed().also {
                        requireMutationCapability(MutationCapability.RENAME)
                    },
                ),
            )

            "raw/optimize-imports" -> encode(
                ImportOptimizeResult.serializer(),
                backend.optimizeImports(
                    decodeParams(ImportOptimizeQuery.serializer(), params).parsed().also {
                        requireMutationCapability(MutationCapability.OPTIMIZE_IMPORTS)
                    },
                ),
            )

            "raw/apply-edits" -> encode(
                ApplyEditsResult.serializer(),
                backend.applyEdits(
                    decodeParams(ApplyEditsQuery.serializer(), params).parsed().also { query ->
                        requireMutationCapability(MutationCapability.APPLY_EDITS)
                        if (query.fileOperations.isNotEmpty()) {
                            requireMutationCapability(MutationCapability.FILE_OPERATIONS)
                        }
                    },
                ),
            )

            "raw/workspace-refresh" -> encode(
                RefreshResult.serializer(),
                backend.refresh(
                    decodeParams(RefreshQuery.serializer(), params).parsed().also {
                        requireMutationCapability(MutationCapability.REFRESH_WORKSPACE)
                    },
                ),
            )

            "raw/file-outline" -> encode(
                FileOutlineResult.serializer(),
                backend.fileOutline(
                    decodeParams(FileOutlineQuery.serializer(), params).parsed().also {
                        requireReadCapability(ReadCapability.FILE_OUTLINE)
                    },
                ),
            )

            "raw/workspace-symbol" -> encode(
                WorkspaceSymbolResult.serializer(),
                backend.workspaceSymbolSearch(
                    decodeParams(WorkspaceSymbolQuery.serializer(), params).parsed().also {
                        requireReadCapability(ReadCapability.WORKSPACE_SYMBOL_SEARCH)
                    },
                ).withLimit(config.maxResults) { workspaceSymbolPageToken(config.maxResults) },
            )

            "raw/workspace-search" -> encode(
                WorkspaceSearchResult.serializer(),
                backend.workspaceSearch(
                    decodeParams(WorkspaceSearchQuery.serializer(), params).parsed().also {
                        requireReadCapability(ReadCapability.WORKSPACE_SEARCH)
                    },
                ),
            )

            "raw/workspace-files" -> encode(
                WorkspaceFilesResult.serializer(),
                backend.workspaceFiles(
                    decodeParams(WorkspaceFilesQuery.serializer(), params).also { query ->
                        val maxFilesPerModule = query.maxFilesPerModule
                        if (maxFilesPerModule != null && maxFilesPerModule > config.maxResults) {
                            throw ValidationException(
                                "Workspace files maxFilesPerModule must be less than or equal to server maxResults (${config.maxResults})",
                            )
                        }
                        requireReadCapability(ReadCapability.WORKSPACE_FILES)
                    }.parsed(),
                ),
            )

            "symbol/resolve" -> encode(
                KastResolveResponse.serializer(),
                skillRpc.resolve(decodeParams(KastResolveRequest.serializer(), params)),
            )

            "symbol/references" -> encode(
                KastReferencesResponse.serializer(),
                skillRpc.references(decodeParams(KastReferencesRequest.serializer(), params)),
            )

            "symbol/callers" -> encode(
                KastCallersResponse.serializer(),
                skillRpc.callers(decodeParams(KastCallersRequest.serializer(), params)),
            )

            "symbol/scaffold" -> encode(
                KastScaffoldResponse.serializer(),
                skillRpc.scaffold(decodeParams(KastScaffoldRequest.serializer(), params)),
            )

            "symbol/rename" -> encode(
                KastRenameResponse.serializer(),
                skillRpc.rename(decodeParams(KastRenameRequest.serializer(), params)),
            )

            "symbol/write-and-validate" -> encode(
                KastWriteAndValidateResponse.serializer(),
                skillRpc.writeAndValidate(decodeParams(KastWriteAndValidateRequest.serializer(), params)),
            )

            "database/metrics" -> encode(
                KastMetricsResponse.serializer(),
                skillRpc.metrics(decodeParams(KastMetricsRequest.serializer(), params)),
            )

            "raw/implementations" -> encode(
                ImplementationsResult.serializer(),
                backend.implementations(
                    decodeParams(ImplementationsQuery.serializer(), params).parsed().also {
                        requireReadCapability(ReadCapability.IMPLEMENTATIONS)
                    },
                ),
            )

            "raw/code-actions" -> encode(
                CodeActionsResult.serializer(),
                backend.codeActions(
                    decodeParams(CodeActionsQuery.serializer(), params).parsed().also {
                        requireReadCapability(ReadCapability.CODE_ACTIONS)
                    },
                ),
            )

            "raw/completions" -> encode(
                CompletionsResult.serializer(),
                backend.completions(
                    decodeParams(CompletionsQuery.serializer(), params).parsed().also {
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

private fun referencePageToken(location: io.github.amichne.kast.api.contract.Location): String =
    location.startOffset.toString()

private fun diagnosticPageToken(diagnostic: io.github.amichne.kast.api.contract.Diagnostic): String =
    diagnostic.location.startOffset.toString()

private fun workspaceSymbolPageToken(limit: Int): String = limit.toString()

@Suppress("UNCHECKED_CAST")
private fun <T, R : PageableResult<T>> R.withLimit(
    limit: Int,
    nextPageToken: (T) -> String,
): R {
    if (items.size <= limit) {
        return this
    }

    return withItems(
        items = items.take(limit),
        page = PageInfo(
            truncated = true,
            nextPageToken = nextPageToken(items[limit - 1]),
        ),
    ) as R
}
