package io.github.amichne.kast.appserver

import io.github.amichne.kast.appserver.ide.CanonicalRootDiscovery
import io.github.amichne.kast.appserver.ide.ExistingIdeExchange
import io.github.amichne.kast.appserver.ide.ExistingIdeFailure
import io.github.amichne.kast.appserver.ide.ExistingIdeOperation
import io.github.amichne.kast.appserver.ide.ExistingIdeReadOperation
import io.github.amichne.kast.appserver.ide.FilesystemCanonicalRootDiscovery
import io.github.amichne.kast.appserver.runtime.WorkspaceDemand
import io.github.amichne.kast.appserver.runtime.WorkspaceDemandResult
import io.github.amichne.kast.protocol.wire.presentation.CanonicalJsonDocument
import io.github.amichne.kast.protocol.wire.presentation.ProjectedOperationOutcome
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.encodeToJsonElement
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class DaemonOperationTest {
    @TempDir lateinit var directory: Path

    private val target = DaemonManagementTarget("installation", "epoch", "generation", "configuration")

    @Serializable
    private enum class SourceType {
        @SerialName("all_declarations") ALL_DECLARATIONS
    }

    @Serializable
    private data class Source(
        val type: SourceType,
        @SerialName("declaration_kinds") val kinds: List<String>?,
        val scope: String?,
    )

    @Serializable
    private data class Arguments(
        val source: Source,
        val steps: List<String>?,
        @SerialName("return_fields") val fields: List<String>,
    )

    @Serializable private data class Document(val value: String)

    @Serializable
    private data class SearchClasses(
        @SerialName("class_name") val name: String,
        @SerialName("name_match") val match: String?,
        val scope: String?,
    )

    @Serializable
    private data class SearchFunctions(
        @SerialName("function_name") val name: String,
        @SerialName("name_match") val match: String?,
        val scope: String?,
    )

    @Serializable
    private data class SearchDeclarations(
        @SerialName("declaration_name") val name: String,
        @SerialName("name_match") val match: String?,
        val scope: String?,
        @SerialName("declaration_kinds") val kinds: List<String>?,
    )

    @Serializable
    private data class Diagnostics(
        @SerialName("relative_path") val path: String,
        @SerialName("max_diagnostics") val limit: Int?,
    )

    @Serializable
    private data class ExpectedQualified(
        val type: String,
        val target: DaemonManagementTarget,
        val root: String,
        val document: Document,
    )

    @Serializable
    private data class ExpectedRequest(
        val target: DaemonManagementTarget,
        val root: String,
        val selection: ExpectedSelection,
        val version: Int,
    )

    @Serializable
    private data class ExpectedSelection(
        val type: String,
        val tool: String,
        val arguments: JsonElement,
    )

    private fun root(): Path = directory.also { Files.writeString(it.resolve("settings.gradle.kts"), "") }.toRealPath()

    private fun request(path: Path, tool: DaemonOperationTool = DaemonOperationTool.QUERY_SYMBOLS) =
        DaemonOperationRequest(
            target,
            path.toString(),
            DaemonOperationSelection.PublicTool(
                tool,
                Json.encodeToJsonElement(Arguments(Source(SourceType.ALL_DECLARATIONS, null, null), null, emptyList())),
            ),
        )

    @Test
    fun `identity version and schema reject before workspace effects`() = runBlocking {
        val path = root()
        assertEquals(
            Json.encodeToJsonElement(
                ExpectedRequest(
                    target,
                    path.toString(),
                    ExpectedSelection(
                        "public_tool",
                        "QUERY_SYMBOLS",
                        (request(path).selection as DaemonOperationSelection.PublicTool).arguments,
                    ),
                    2,
                )
            ),
            DaemonOperationProtocol.json.encodeToJsonElement(DaemonOperationRequest.serializer(), request(path)),
        )
        val query = DaemonOperation(target, { true }, WorkspaceDemand { _, _ -> error("Workspace was prepared") })
        assertEquals(
            DaemonOperationFailure.Protocol(DaemonOperationProtocolFailure.IDENTITY_REJECTED),
            (query.execute(request(path).copy(target = target.copy(stateEpoch = "other")))
                    as DaemonOperationResponse.Rejected)
                .failure,
        )
        assertEquals(
            DaemonOperationFailure.Protocol(DaemonOperationProtocolFailure.UNSUPPORTED_VERSION),
            (query.execute(request(path).copy(version = 3)) as DaemonOperationResponse.Rejected).failure,
        )
        val malformed =
            request(path)
                .copy(
                    selection =
                        DaemonOperationSelection.PublicTool(
                            DaemonOperationTool.QUERY_SYMBOLS,
                            Json.encodeToJsonElement(Document("wrong schema")),
                        )
                )
        assertEquals(
            DaemonOperationFailure.Input(DaemonOperationInputFailure.SchemaRejected),
            (query.execute(malformed) as DaemonOperationResponse.Rejected).failure,
        )
        assertEquals(
            DaemonOperationFailure.Protocol(DaemonOperationProtocolFailure.LIFECYCLE_TRANSITION),
            (DaemonOperation(target, { false }, WorkspaceDemand { _, _ -> error("Workspace was prepared") })
                    .execute(request(path)) as DaemonOperationResponse.Rejected)
                .failure,
        )
    }

    @Test
    fun `query prepares the exact root and preserves qualified outcome`() = runBlocking {
        val path = root()
        val admittedRoot = (FilesystemCanonicalRootDiscovery.discover(path) as CanonicalRootDiscovery.Discovered).root
        val document = CanonicalJsonDocument.generated(Document.serializer()).create(Document("partial"))
        var calls = 0
        val query =
            DaemonOperation(
                target,
                { true },
                WorkspaceDemand { root, operation ->
                    assertEquals(admittedRoot, root)
                    assertEquals(ExistingIdeReadOperation.QUERY_RUN, (operation as ExistingIdeOperation.Read).kind)
                    calls++
                    WorkspaceDemandResult.Native(
                        ExistingIdeExchange.Semantic(ProjectedOperationOutcome.Qualified(document))
                    )
                },
            )
        val response = assertInstanceOf(DaemonOperationResponse.Qualified::class.java, query.execute(request(path)))
        assertEquals(1, calls)
        assertEquals(target, response.target)
        assertEquals(path.toString(), response.root)
        assertEquals(Json.encodeToJsonElement(Document("partial")), response.document)
        val wire = DaemonOperationProtocol.json.encodeToString(DaemonOperationResponse.serializer(), response)
        assertEquals(
            Json.encodeToJsonElement(ExpectedQualified("qualified", target, path.toString(), Document("partial"))),
            Json.parseToJsonElement(wire),
        )
        assertEquals(response, DaemonOperationProtocol.json.decodeFromString<DaemonOperationResponse>(wire))
    }

    @Test
    fun `client rejects a response for another target without presenting success`() {
        val path = root()
        val admittedRoot = (FilesystemCanonicalRootDiscovery.discover(path) as CanonicalRootDiscovery.Discovered).root
        val response =
            DaemonOperationResponse.Complete(
                target.copy(stateEpoch = "other"),
                path.toString(),
                Json.encodeToJsonElement(Document("success")),
            )
        assertEquals(
            DaemonOperationResult.Rejected(
                DaemonOperationClientRejection.Transport(DaemonOperationClientFailure.RESPONSE_REJECTED)
            ),
            admitOperationResponse(response, target, admittedRoot),
        )
    }

    @Test
    fun `client keeps a correlated qualified document qualified`() {
        val path = root()
        val admittedRoot = (FilesystemCanonicalRootDiscovery.discover(path) as CanonicalRootDiscovery.Discovered).root
        val payload = Json.encodeToJsonElement(Document("partial"))
        val result =
            admitOperationResponse(
                DaemonOperationResponse.Qualified(target, path.toString(), payload),
                target,
                admittedRoot,
            )
        assertEquals(
            CanonicalJsonDocument.generated(Document.serializer()).create(Document("partial")).value,
            assertInstanceOf(DaemonOperationResult.Qualified::class.java, result).document.value,
        )
    }

    @Test
    fun `complete and rejected native outcomes retain their distinct wire variants`() = runBlocking {
        val path = root()
        val document = CanonicalJsonDocument.generated(Document.serializer()).create(Document("native"))
        val element = Json.encodeToJsonElement(Document("native"))
        val cases =
            listOf(
                ExistingIdeExchange.Semantic(ProjectedOperationOutcome.Complete(document)) to
                    DaemonOperationResponse.Complete(target, path.toString(), element),
                ExistingIdeExchange.Semantic(ProjectedOperationOutcome.Rejected(document)) to
                    DaemonOperationResponse.OperationRejected(target, path.toString(), element),
                ExistingIdeExchange.HostRejected(document) to
                    DaemonOperationResponse.OperationRejected(target, path.toString(), element),
                ExistingIdeExchange.Rejected(ExistingIdeFailure.HOST_UNAVAILABLE) to
                    DaemonOperationResponse.Rejected(DaemonOperationFailure.Host(ExistingIdeFailure.HOST_UNAVAILABLE)),
                ExistingIdeExchange.Received(document) to
                    DaemonOperationResponse.Rejected(
                        DaemonOperationFailure.Protocol(DaemonOperationProtocolFailure.RESPONSE_REJECTED)
                    ),
            )
        for ((exchange, expected) in cases) {
            val query =
                DaemonOperation(
                    target,
                    { true },
                    WorkspaceDemand { _, operation ->
                        assertEquals(ExistingIdeReadOperation.QUERY_RUN, (operation as ExistingIdeOperation.Read).kind)
                        WorkspaceDemandResult.Native(exchange)
                    },
                )
            assertEquals(expected, query.execute(request(path)))
        }
    }

    @Test
    fun `every public presentation re-admits its own schema before the correct native read`() = runBlocking {
        val path = root()
        for ((tool, arguments, operation) in readCases(path)) {
            var calls = 0
            val read =
                DaemonOperation(
                    target,
                    { true },
                    WorkspaceDemand { admittedRoot, native ->
                        assertEquals(path, admittedRoot.path)
                        assertEquals(operation, (native as ExistingIdeOperation.Read).kind)
                        calls++
                        WorkspaceDemandResult.Native(ExistingIdeExchange.Rejected(ExistingIdeFailure.HOST_UNAVAILABLE))
                    },
                )
            assertEquals(
                DaemonOperationFailure.Host(ExistingIdeFailure.HOST_UNAVAILABLE),
                (read.execute(
                        request(path, tool).copy(selection = DaemonOperationSelection.PublicTool(tool, arguments))
                    ) as DaemonOperationResponse.Rejected)
                    .failure,
            )
            assertEquals(1, calls)
        }
    }

    @Test
    fun `another public tool schema cannot prepare a workspace`() = runBlocking {
        val path = root()
        val rejected =
            DaemonOperation(target, { true }, WorkspaceDemand { _, _ -> error("Schema mismatch prepared workspace") })
        assertEquals(
            DaemonOperationFailure.Input(DaemonOperationInputFailure.SchemaRejected),
            (rejected.execute(
                    request(path, DaemonOperationTool.SEARCH_CLASSES)
                        .copy(
                            selection =
                                DaemonOperationSelection.PublicTool(
                                    DaemonOperationTool.SEARCH_CLASSES,
                                    Json.encodeToJsonElement(SearchFunctions("order", null, null)),
                                )
                        )
                ) as DaemonOperationResponse.Rejected)
                .failure,
        )
    }

    private fun readCases(path: Path): List<Triple<DaemonOperationTool, JsonElement, ExistingIdeReadOperation>> =
        listOf(
            Triple(
                DaemonOperationTool.SEARCH_CLASSES,
                Json.encodeToJsonElement(SearchClasses("Order", null, null)),
                ExistingIdeReadOperation.QUERY_RUN,
            ),
            Triple(
                DaemonOperationTool.SEARCH_FUNCTIONS,
                Json.encodeToJsonElement(SearchFunctions("order", null, null)),
                ExistingIdeReadOperation.QUERY_RUN,
            ),
            Triple(
                DaemonOperationTool.SEARCH_DECLARATIONS,
                Json.encodeToJsonElement(SearchDeclarations("order", null, null, listOf("property", "type_alias"))),
                ExistingIdeReadOperation.QUERY_RUN,
            ),
            Triple(
                DaemonOperationTool.CHECK_DIAGNOSTICS,
                Json.encodeToJsonElement(Diagnostics(".", null)),
                ExistingIdeReadOperation.DIAGNOSTIC_CHECK,
            ),
            Triple(
                DaemonOperationTool.QUERY_SYMBOLS,
                (request(path).selection as DaemonOperationSelection.PublicTool).arguments,
                ExistingIdeReadOperation.QUERY_RUN,
            ),
        )
}
