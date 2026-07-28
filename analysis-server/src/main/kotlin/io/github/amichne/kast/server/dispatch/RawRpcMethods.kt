package io.github.amichne.kast.server.dispatch

import io.github.amichne.kast.api.contract.PageInfo
import io.github.amichne.kast.api.contract.PageableResult
import io.github.amichne.kast.api.contract.PositiveInt
import io.github.amichne.kast.api.contract.ReadCapability
import io.github.amichne.kast.api.contract.MutationCapability
import io.github.amichne.kast.api.contract.SemanticInsertionQuery
import io.github.amichne.kast.api.contract.SemanticInsertionResult
import io.github.amichne.kast.api.contract.query.*
import io.github.amichne.kast.api.contract.result.*
import io.github.amichne.kast.api.validation.parsed
import io.github.amichne.kast.api.protocol.ValidationException
import kotlinx.serialization.json.JsonElement

internal suspend fun RpcMethodRouter.dispatchRawMethod(
    method: String,
    params: JsonElement?,
): JsonElement? = when (method) {
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
            decodeParams(ReferencesQuery.serializer(), params).parsed().also { query ->
                requireReadCapability(ReadCapability.FIND_REFERENCES)
                if (query.maxResults.value > config.maxResults) {
                    throw ValidationException(
                        "References maxResults must be less than or equal to server maxResults (${config.maxResults})",
                    )
                }
            },
        ),
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
            decodeParams(DiagnosticsQuery.serializer(), params).parsed().let { query ->
                requireReadCapability(ReadCapability.DIAGNOSTICS)
                query.copy(maxResults = PositiveInt(minOf(query.maxResults.value, config.maxResults)))
            },
        ),
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
    "raw/semantic-graph" -> encode(
        SemanticGraphResult.serializer(),
        backend.semanticGraph(
            decodeParams(SemanticGraphQuery.serializer(), params).also { query ->
                if (query.filePaths.size > config.maxResults) {
                    throw ValidationException(
                        "Semantic graph selected file count must be less than or equal to " +
                            "server maxResults (${config.maxResults})",
                    )
                }
            }.parsed().also {
                requireReadCapability(ReadCapability.SEMANTIC_GRAPH)
            },
        ),
    )
    "raw/workspace-files-continuation" -> encode(
        WorkspaceFilesContinuationResult.serializer(),
        workspaceFilesContinuation.execute(
            decodeParams(WorkspaceFilesContinuationQuery.serializer(), params).parsed(),
        ),
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
    else -> null
}

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
