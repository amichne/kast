package io.github.amichne.kast.server

import io.github.amichne.kast.api.contract.query.ApplyEditsQuery
import io.github.amichne.kast.api.contract.CallDirection
import io.github.amichne.kast.api.contract.query.CallHierarchyQuery
import io.github.amichne.kast.api.contract.query.CodeActionsQuery
import io.github.amichne.kast.api.contract.query.CompletionsQuery
import io.github.amichne.kast.api.contract.query.DiagnosticsQuery
import io.github.amichne.kast.api.contract.FileHash
import io.github.amichne.kast.api.validation.FileHashing
import io.github.amichne.kast.api.contract.query.FileOutlineQuery
import io.github.amichne.kast.api.contract.FilePosition
import io.github.amichne.kast.api.contract.query.ImplementationsQuery
import io.github.amichne.kast.api.contract.query.ImportOptimizeQuery
import io.github.amichne.kast.api.protocol.JsonRpcRequest
import io.github.amichne.kast.api.contract.query.RefreshQuery
import io.github.amichne.kast.api.contract.query.ReferencesQuery
import io.github.amichne.kast.api.contract.query.RenameQuery
import io.github.amichne.kast.api.contract.SemanticInsertionQuery
import io.github.amichne.kast.api.contract.SemanticInsertionTarget
import io.github.amichne.kast.api.contract.query.SymbolQuery
import io.github.amichne.kast.api.contract.TextEdit
import io.github.amichne.kast.api.contract.TypeHierarchyDirection
import io.github.amichne.kast.api.contract.query.TypeHierarchyQuery
import io.github.amichne.kast.api.contract.query.WorkspaceFilesContinuationQuery
import io.github.amichne.kast.api.contract.query.WorkspaceFilesPublicContinuationIdentity
import io.github.amichne.kast.api.contract.query.WorkspaceFilesQuery
import io.github.amichne.kast.api.contract.query.WorkspaceSearchQuery
import io.github.amichne.kast.api.contract.query.WorkspaceSymbolQuery
import io.github.amichne.kast.api.contract.result.WorkspaceFilesPublicContinuationState
import io.github.amichne.kast.api.validation.WorkspaceFilesPublicPageToken
import io.github.amichne.kast.testing.FakeAnalysisBackend
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.io.path.writeText

object DocExampleGenerator {

    data class ExamplePair(val request: String, val response: String)

    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
        explicitNulls = false
    }

    fun generateExamples(): Map<String, ExamplePair> {
        val tempDir = Files.createTempDirectory("kast-doc-examples")
        try {
            val backend = FakeAnalysisBackend.sample(tempDir)
            val dispatcher = RpcAnalysisDispatcher(
                backend = backend,
                config = AnalysisServerConfig(),
                lifecycleController = RuntimeLifecycleController { {} },
            )

            val sampleFile = tempDir.resolve("src/Sample.kt").toString()
            val typeFile = tempDir.resolve("src/Types.kt").toString()
            val sampleContent = Path.of(sampleFile).readText()
            val typeContent = Path.of(typeFile).readText()

            val greetDeclarationOffset = sampleContent.indexOf("greet")
            val greetReferenceOffset = sampleContent.lastIndexOf("greet")
            val friendlyGreeterOffset = typeContent.indexOf("FriendlyGreeter")

            val pathToSanitize = tempDir.toString()
            val continuationIdentity = continuationIdentity(sampleFile)

            val operations = buildOperations(
                sampleFile = sampleFile,
                typeFile = typeFile,
                sampleContent = sampleContent,
                greetDeclarationOffset = greetDeclarationOffset,
                greetReferenceOffset = greetReferenceOffset,
                friendlyGreeterOffset = friendlyGreeterOffset,
                continuationIdentity = continuationIdentity,
            )

            val result = linkedMapOf<String, ExamplePair>()
            for ((operationId, request) in operations) {
                val requestJson = json.encodeToString(JsonRpcRequest.serializer(), request)
                    .replace(pathToSanitize, "/workspace")
                    .withDeterministicWorkspaceHandles(operationId)
                val responseRaw = runBlocking { dispatcher.dispatch(request) }
                val responseElement = json.parseToJsonElement(responseRaw)
                val responseJson = json.encodeToString(JsonElement.serializer(), responseElement)
                    .replace(pathToSanitize, "/workspace")
                    .withDeterministicWorkspaceHandles(operationId)
                result[operationId] = ExamplePair(requestJson, responseJson)

                when (operationId) {
                    "workspaceFiles" -> {
                        val snapshotToken = responseElement.resultString("snapshotToken")
                        val pageRequest = request(
                            method = "raw/workspace-files",
                            params = json.encodeToJsonElement(
                                WorkspaceFilesQuery.serializer(),
                                WorkspaceFilesQuery(
                                    moduleName = "fake-module",
                                    includeFiles = true,
                                    maxFilesPerModule = 1,
                                    snapshotToken = snapshotToken,
                                ),
                            ),
                        )
                        result["workspaceFilesPage"] = executeExample(
                            operationId = "workspaceFilesPage",
                            request = pageRequest,
                            dispatcher = dispatcher,
                            pathToSanitize = pathToSanitize,
                        )
                    }
                    "workspaceFilesContinuation" -> {
                        val pageToken = WorkspaceFilesPublicPageToken.parse(responseElement.resultString("pageToken"))
                        val consumeRequest = request(
                            method = "raw/workspace-files-continuation",
                            params = json.encodeToJsonElement(
                                WorkspaceFilesContinuationQuery.serializer(),
                                WorkspaceFilesContinuationQuery.consume(continuationIdentity, pageToken),
                            ),
                        )
                        result["workspaceFilesContinuationConsume"] = executeExample(
                            operationId = "workspaceFilesContinuationConsume",
                            request = consumeRequest,
                            dispatcher = dispatcher,
                            pathToSanitize = pathToSanitize,
                        )
                    }
                }
            }
            return result
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    private fun buildOperations(
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

    private fun continuationIdentity(sampleFile: String): WorkspaceFilesPublicContinuationIdentity =
        WorkspaceFilesPublicContinuationIdentity(
            workspaceRoot = WorkspaceFilesPublicContinuationIdentity.WorkspaceRoot.parse(
                Path.of(sampleFile).parent.parent.toString(),
            ),
            backendName = WorkspaceFilesPublicContinuationIdentity.BackendName.parse("fake"),
            normalizedQuery = WorkspaceFilesPublicContinuationIdentity.NormalizedQuery.parse(
                "kind=mixed;module=*;package=*;sourceSet=*",
            ),
            projection = WorkspaceFilesPublicContinuationIdentity.Projection.parse("compact:path,evidence"),
            limit = WorkspaceFilesPublicContinuationIdentity.Limit.of(1),
        )

    private fun request(method: String, params: JsonElement? = null): JsonRpcRequest =
        JsonRpcRequest(id = JsonPrimitive(1), method = method, params = params)

    private fun executeExample(
        operationId: String,
        request: JsonRpcRequest,
        dispatcher: RpcAnalysisDispatcher,
        pathToSanitize: String,
    ): ExamplePair {
        val requestJson = json.encodeToString(JsonRpcRequest.serializer(), request)
            .replace(pathToSanitize, "/workspace")
            .withDeterministicWorkspaceHandles(operationId)
        val responseElement = json.parseToJsonElement(runBlocking { dispatcher.dispatch(request) })
        val responseJson = json.encodeToString(JsonElement.serializer(), responseElement)
            .replace(pathToSanitize, "/workspace")
            .withDeterministicWorkspaceHandles(operationId)
        return ExamplePair(requestJson, responseJson)
    }

    private fun JsonElement.resultString(field: String): String =
        jsonObject.getValue("result").jsonObject.getValue(field).jsonPrimitive.content

    private fun String.withDeterministicWorkspaceHandles(operationId: String): String = when (operationId) {
        "workspaceFiles", "workspaceFilesPage" ->
            replaceUuidField("nextPageToken", RAW_WORKSPACE_PAGE_HANDLE)
                .replaceUuidField("snapshotToken", RAW_WORKSPACE_SNAPSHOT_HANDLE)
        "workspaceFilesContinuation", "workspaceFilesContinuationConsume" ->
            replaceUuidField("pageToken", PUBLIC_WORKSPACE_PAGE_HANDLE)
        else -> this
    }

    private fun String.replaceUuidField(field: String, replacement: String): String =
        Regex("""("$field"\s*:\s*")$UUID_PATTERN(")""").replace(this) { match ->
            match.groupValues[1] + replacement + match.groupValues[2]
        }

    private const val UUID_PATTERN = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"
    private const val RAW_WORKSPACE_PAGE_HANDLE = "00000000-0000-4000-8000-000000000001"
    private const val RAW_WORKSPACE_SNAPSHOT_HANDLE = "00000000-0000-4000-8000-000000000002"
    private const val PUBLIC_WORKSPACE_PAGE_HANDLE = "00000000-0000-4000-8000-000000000003"
}

private fun repoRoot(): Path =
    generateSequence(Path.of("").toAbsolutePath()) { it.parent }
        .first { Files.isDirectory(it.resolve("docs")) }

fun main(args: Array<String>) {
    val outputDir = if (args.isNotEmpty()) Path.of(args[0]) else repoRoot().resolve("cli-rs/protocol/examples")
    Files.createDirectories(outputDir)
    val examples = DocExampleGenerator.generateExamples()
    examples.forEach { (operationId, pair) ->
        outputDir.resolve("$operationId-request.json").writeText(pair.request + "\n")
        outputDir.resolve("$operationId-response.json").writeText(pair.response + "\n")
    }
    println("Generated ${examples.size} example pairs in $outputDir")
}
