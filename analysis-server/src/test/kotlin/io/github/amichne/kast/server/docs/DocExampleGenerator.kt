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
import io.github.amichne.kast.api.contract.query.SemanticGraphPath
import io.github.amichne.kast.api.contract.query.SemanticGraphQuery
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
            val delegate = FakeAnalysisBackend.sample(tempDir)
            val sampleFile = tempDir.resolve("src/Sample.kt").toString()
            val typeFile = tempDir.resolve("src/Types.kt").toString()
            val sampleContent = Path.of(sampleFile).readText()
            val typeContent = Path.of(typeFile).readText()

            val mutationFixture = buildDocExampleGeneratorMutationFixture(
                json = json,
                delegate = delegate,
                workspaceRoot = tempDir,
                sampleFile = sampleFile,
                sampleContent = sampleContent,
            )
            val dispatcher = RpcAnalysisDispatcher(
                backend = mutationFixture.backend,
                config = AnalysisServerConfig(),
            )

            val greetDeclarationOffset = sampleContent.indexOf("greet")
            val greetReferenceOffset = sampleContent.lastIndexOf("greet")
            val friendlyGreeterOffset = typeContent.indexOf("FriendlyGreeter")

            val pathToSanitize = tempDir.toString()
            val canonicalPathToSanitize = tempDir.toRealPath().toString()
            val continuationIdentity = continuationIdentity(sampleFile)

            val operations = insertDocMutationOperations(
                base = buildOperations(
                    json = json,
                    sampleFile = sampleFile,
                    typeFile = typeFile,
                    sampleContent = sampleContent,
                    greetDeclarationOffset = greetDeclarationOffset,
                    greetReferenceOffset = greetReferenceOffset,
                    friendlyGreeterOffset = friendlyGreeterOffset,
                    continuationIdentity = continuationIdentity,
                ),
                mutation = mutationFixture.operations,
            )

            val result = linkedMapOf<String, ExamplePair>()
            for ((operationId, request) in operations) {
                val requestJson = json.encodeToString(JsonRpcRequest.serializer(), request)
                    .replace(canonicalPathToSanitize, "/workspace")
                    .replace(pathToSanitize, "/workspace")
                    .withDeterministicWorkspaceHandles(operationId)
                val responseRaw = runBlocking { dispatcher.dispatch(request) }
                val responseElement = json.parseToJsonElement(responseRaw)
                val responseJson = json.encodeToString(JsonElement.serializer(), responseElement)
                    .replace(canonicalPathToSanitize, "/workspace")
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

fun main(args: Array<String>) {
    val outputDir = if (args.isNotEmpty()) {
        Path.of(args[0])
    } else {
        Path.of("build/generated/kast-protocol/examples")
    }
    Files.createDirectories(outputDir)
    val examples = DocExampleGenerator.generateExamples()
    examples.forEach { (operationId, pair) ->
        outputDir.resolve("$operationId-request.json").writeText(pair.request + "\n")
        outputDir.resolve("$operationId-response.json").writeText(pair.response + "\n")
    }
    println("Generated ${examples.size} example pairs in $outputDir")
}
