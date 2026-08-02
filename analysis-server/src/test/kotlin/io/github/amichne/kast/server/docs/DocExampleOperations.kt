package io.github.amichne.kast.server

import io.github.amichne.kast.api.contract.CallDirection
import io.github.amichne.kast.api.contract.FileHash
import io.github.amichne.kast.api.contract.FilePosition
import io.github.amichne.kast.api.contract.SemanticInsertionQuery
import io.github.amichne.kast.api.contract.SemanticInsertionTarget
import io.github.amichne.kast.api.contract.TextEdit
import io.github.amichne.kast.api.contract.TypeHierarchyDirection
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
import io.github.amichne.kast.api.contract.query.SemanticGraphPath
import io.github.amichne.kast.api.contract.query.SemanticGraphQuery
import io.github.amichne.kast.api.contract.query.SymbolQuery
import io.github.amichne.kast.api.contract.query.TypeHierarchyQuery
import io.github.amichne.kast.api.contract.query.WorkspaceFilesContinuationQuery
import io.github.amichne.kast.api.contract.query.WorkspaceFilesPublicContinuationIdentity
import io.github.amichne.kast.api.contract.query.WorkspaceFilesQuery
import io.github.amichne.kast.api.contract.query.WorkspaceSearchQuery
import io.github.amichne.kast.api.contract.query.WorkspaceSymbolQuery
import io.github.amichne.kast.api.contract.result.WorkspaceFilesPublicContinuationState
import io.github.amichne.kast.api.protocol.JsonRpcRequest
import io.github.amichne.kast.api.validation.FileHashing
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

internal fun buildOperations(
    json: Json,
    sampleFile: String,
    typeFile: String,
    sampleContent: String,
    greetDeclarationOffset: Int,
    greetReferenceOffset: Int,
    friendlyGreeterOffset: Int,
    continuationIdentity: WorkspaceFilesPublicContinuationIdentity,
): List<Pair<String, JsonRpcRequest>> {
    val ops = mutableListOf<Pair<String, JsonRpcRequest>>()

    // System operations (no params)
    ops += "health" to request("health")
    ops += "runtimeStatus" to request("runtime/status")
    ops += "runtimeShutdown" to request("runtime/shutdown")
    ops += "runtimeRestart" to request("runtime/restart")
    ops += "capabilities" to request("capabilities")

    // Read operations
    ops += "resolveSymbol" to request(
        "raw/resolve",
        json.encodeToJsonElement(
            SymbolQuery.serializer(),
            SymbolQuery(position = FilePosition(filePath = sampleFile, offset = greetDeclarationOffset)),
        ),
    )
    ops += "findReferences" to request(
        "raw/references",
        json.encodeToJsonElement(
            ReferencesQuery.serializer(),
            ReferencesQuery(
                position = FilePosition(filePath = sampleFile, offset = greetReferenceOffset),
                includeDeclaration = true,
            ),
        ),
    )
    ops += "callHierarchy" to request(
        "raw/call-hierarchy",
        json.encodeToJsonElement(
            CallHierarchyQuery.serializer(),
            CallHierarchyQuery(
                position = FilePosition(filePath = sampleFile, offset = greetReferenceOffset),
                direction = CallDirection.INCOMING,
                depth = 1,
                maxTotalCalls = 16,
                maxChildrenPerNode = 16,
            ),
        ),
    )
    ops += "typeHierarchy" to request(
        "raw/type-hierarchy",
        json.encodeToJsonElement(
            TypeHierarchyQuery.serializer(),
            TypeHierarchyQuery(
                position = FilePosition(filePath = typeFile, offset = friendlyGreeterOffset),
                direction = TypeHierarchyDirection.BOTH,
                depth = 1,
                maxResults = 16,
            ),
        ),
    )
    ops += "semanticInsertionPoint" to request(
        "raw/semantic-insertion-point",
        json.encodeToJsonElement(
            SemanticInsertionQuery.serializer(),
            SemanticInsertionQuery(
                position = FilePosition(filePath = sampleFile, offset = 0),
                target = SemanticInsertionTarget.FILE_BOTTOM,
            ),
        ),
    )
    ops += "diagnostics" to request(
        "raw/diagnostics",
        json.encodeToJsonElement(
            DiagnosticsQuery.serializer(),
            DiagnosticsQuery(filePaths = listOf(sampleFile)),
        ),
    )
    ops += "fileOutline" to request(
        "raw/file-outline",
        json.encodeToJsonElement(
            FileOutlineQuery.serializer(),
            FileOutlineQuery(filePath = sampleFile),
        ),
    )
    ops += "workspaceSymbolSearch" to request(
        "raw/workspace-symbol",
        json.encodeToJsonElement(
            WorkspaceSymbolQuery.serializer(),
            WorkspaceSymbolQuery(pattern = "greet"),
        ),
    )
    ops += "workspaceFiles" to request(
        "raw/workspace-files",
        json.encodeToJsonElement(
            WorkspaceFilesQuery.serializer(),
            WorkspaceFilesQuery(),
        ),
    )
    ops += "semanticGraph" to request(
        "raw/semantic-graph",
        json.encodeToJsonElement(
            SemanticGraphQuery.serializer(),
            SemanticGraphQuery(filePaths = listOf(SemanticGraphPath.parse(sampleFile))),
        ),
    )
    ops += "workspaceFilesContinuation" to request(
        "raw/workspace-files-continuation",
        json.encodeToJsonElement(
            WorkspaceFilesContinuationQuery.serializer(),
            WorkspaceFilesContinuationQuery.issue(
                identity = continuationIdentity,
                state = WorkspaceFilesPublicContinuationState(
                    identity = continuationIdentity,
                    compositionStampDigest =
                        WorkspaceFilesPublicContinuationState.CompositionStampDigest.parse("0".repeat(64)),
                    lastRelativePath =
                        WorkspaceFilesPublicContinuationState.LastRelativePath.parse("src/Sample.kt"),
                    cumulativeReturnedCount =
                        WorkspaceFilesPublicContinuationState.CumulativeReturnedCount.of(1),
                ),
            ),
        ),
    )
    ops += "workspaceSearch" to request(
        "raw/workspace-search",
        json.encodeToJsonElement(
            WorkspaceSearchQuery.serializer(),
            WorkspaceSearchQuery(pattern = "greet"),
        ),
    )
    ops += "implementations" to request(
        "raw/implementations",
        json.encodeToJsonElement(
            ImplementationsQuery.serializer(),
            ImplementationsQuery(
                position = FilePosition(filePath = typeFile, offset = friendlyGreeterOffset),
                maxResults = 10,
            ),
        ),
    )
    ops += "codeActions" to request(
        "raw/code-actions",
        json.encodeToJsonElement(
            CodeActionsQuery.serializer(),
            CodeActionsQuery(position = FilePosition(filePath = sampleFile, offset = 0)),
        ),
    )
    ops += "completions" to request(
        "raw/completions",
        json.encodeToJsonElement(
            CompletionsQuery.serializer(),
            CompletionsQuery(
                position = FilePosition(filePath = sampleFile, offset = 0),
                maxResults = 10,
            ),
        ),
    )

    // Mutation operations
    ops += "rename" to request(
        "raw/rename",
        json.encodeToJsonElement(
            RenameQuery.serializer(),
            RenameQuery(
                position = FilePosition(filePath = sampleFile, offset = greetDeclarationOffset),
                newName = "welcome",
            ),
        ),
    )
    ops += "optimizeImports" to request(
        "raw/optimize-imports",
        json.encodeToJsonElement(
            ImportOptimizeQuery.serializer(),
            ImportOptimizeQuery(filePaths = listOf(sampleFile)),
        ),
    )
    ops += "refreshWorkspace" to request(
        "raw/workspace-refresh",
        json.encodeToJsonElement(
            RefreshQuery.serializer(),
            RefreshQuery(filePaths = listOf(sampleFile)),
        ),
    )

    // applyEdits MUST be last — it modifies files on disk.
    ops += "applyEdits" to request(
        "raw/apply-edits",
        json.encodeToJsonElement(
            ApplyEditsQuery.serializer(),
            ApplyEditsQuery(
                edits = listOf(
                    TextEdit(
                        filePath = sampleFile,
                        startOffset = 0,
                        endOffset = 0,
                        newText = "// edited\n",
                    ),
                ),
                fileHashes = listOf(
                    FileHash(
                        filePath = sampleFile,
                        hash = FileHashing.sha256(sampleContent),
                    ),
                ),
            ),
        ),
    )

    return ops
}

internal fun request(method: String, params: JsonElement? = null): JsonRpcRequest =
    JsonRpcRequest(id = JsonPrimitive(1), method = method, params = params)
