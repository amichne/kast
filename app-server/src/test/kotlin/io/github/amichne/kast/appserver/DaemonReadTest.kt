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

class DaemonReadTest {
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
        val tool: String,
        val arguments: JsonElement,
        val version: Int,
    )

    private fun root(): Path = directory.also { Files.writeString(it.resolve("settings.gradle.kts"), "") }.toRealPath()

    private fun request(path: Path, tool: DaemonReadTool = DaemonReadTool.QUERY_SYMBOLS) =
        DaemonReadRequest(
            target,
            path.toString(),
            tool,
            Json.encodeToJsonElement(Arguments(Source(SourceType.ALL_DECLARATIONS, null, null), null, emptyList())),
        )

    @Test
    fun `identity version and schema reject before workspace effects`() = runBlocking {
        val path = root()
        assertEquals(
            Json.encodeToJsonElement(
                ExpectedRequest(target, path.toString(), "QUERY_SYMBOLS", request(path).arguments, 1)
            ),
            DaemonReadProtocol.json.encodeToJsonElement(DaemonReadRequest.serializer(), request(path)),
        )
        val query = DaemonRead(target, { true }, WorkspaceDemand { _, _ -> error("Workspace was prepared") })
        assertEquals(
            DaemonReadFailure.Protocol(DaemonReadProtocolFailure.IDENTITY_REJECTED),
            (query.execute(request(path).copy(target = target.copy(stateEpoch = "other")))
                    as DaemonReadResponse.Rejected)
                .failure,
        )
        assertEquals(
            DaemonReadFailure.Protocol(DaemonReadProtocolFailure.UNSUPPORTED_VERSION),
            (query.execute(request(path).copy(version = 2)) as DaemonReadResponse.Rejected).failure,
        )
        val malformed = request(path).copy(arguments = Json.encodeToJsonElement(Document("wrong schema")))
        assertEquals(
            DaemonReadFailure.Input(DaemonReadInputFailure.SchemaRejected),
            (query.execute(malformed) as DaemonReadResponse.Rejected).failure,
        )
        assertEquals(
            DaemonReadFailure.Protocol(DaemonReadProtocolFailure.LIFECYCLE_TRANSITION),
            (DaemonRead(target, { false }, WorkspaceDemand { _, _ -> error("Workspace was prepared") })
                    .execute(request(path)) as DaemonReadResponse.Rejected)
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
            DaemonRead(
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
        val response = assertInstanceOf(DaemonReadResponse.Qualified::class.java, query.execute(request(path)))
        assertEquals(1, calls)
        assertEquals(target, response.target)
        assertEquals(path.toString(), response.root)
        assertEquals(Json.encodeToJsonElement(Document("partial")), response.document)
        val wire = DaemonReadProtocol.json.encodeToString(DaemonReadResponse.serializer(), response)
        assertEquals(
            Json.encodeToJsonElement(ExpectedQualified("qualified", target, path.toString(), Document("partial"))),
            Json.parseToJsonElement(wire),
        )
        assertEquals(response, DaemonReadProtocol.json.decodeFromString<DaemonReadResponse>(wire))
    }

    @Test
    fun `client rejects a response for another target without presenting success`() {
        val path = root()
        val admittedRoot = (FilesystemCanonicalRootDiscovery.discover(path) as CanonicalRootDiscovery.Discovered).root
        val response =
            DaemonReadResponse.Complete(
                target.copy(stateEpoch = "other"),
                path.toString(),
                Json.encodeToJsonElement(Document("success")),
            )
        assertEquals(
            DaemonReadResult.Rejected(DaemonReadClientRejection.Transport(DaemonReadClientFailure.RESPONSE_REJECTED)),
            admitQueryResponse(response, target, admittedRoot),
        )
    }

    @Test
    fun `client keeps a correlated qualified document qualified`() {
        val path = root()
        val admittedRoot = (FilesystemCanonicalRootDiscovery.discover(path) as CanonicalRootDiscovery.Discovered).root
        val payload = Json.encodeToJsonElement(Document("partial"))
        val result =
            admitQueryResponse(DaemonReadResponse.Qualified(target, path.toString(), payload), target, admittedRoot)
        assertEquals(
            CanonicalJsonDocument.generated(Document.serializer()).create(Document("partial")).value,
            assertInstanceOf(DaemonReadResult.Qualified::class.java, result).document.value,
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
                    DaemonReadResponse.Complete(target, path.toString(), element),
                ExistingIdeExchange.Semantic(ProjectedOperationOutcome.Rejected(document)) to
                    DaemonReadResponse.OperationRejected(target, path.toString(), element),
                ExistingIdeExchange.HostRejected(document) to
                    DaemonReadResponse.OperationRejected(target, path.toString(), element),
                ExistingIdeExchange.Rejected(ExistingIdeFailure.HOST_UNAVAILABLE) to
                    DaemonReadResponse.Rejected(DaemonReadFailure.Host(ExistingIdeFailure.HOST_UNAVAILABLE)),
                ExistingIdeExchange.Received(document) to
                    DaemonReadResponse.Rejected(
                        DaemonReadFailure.Protocol(DaemonReadProtocolFailure.RESPONSE_REJECTED)
                    ),
            )
        for ((exchange, expected) in cases) {
            val query =
                DaemonRead(
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
                DaemonRead(
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
                DaemonReadFailure.Host(ExistingIdeFailure.HOST_UNAVAILABLE),
                (read.execute(request(path, tool).copy(arguments = arguments)) as DaemonReadResponse.Rejected).failure,
            )
            assertEquals(1, calls)
        }
    }

    @Test
    fun `another public tool schema cannot prepare a workspace`() = runBlocking {
        val path = root()
        val rejected =
            DaemonRead(target, { true }, WorkspaceDemand { _, _ -> error("Schema mismatch prepared workspace") })
        assertEquals(
            DaemonReadFailure.Input(DaemonReadInputFailure.SchemaRejected),
            (rejected.execute(
                    request(path, DaemonReadTool.SEARCH_CLASSES)
                        .copy(arguments = Json.encodeToJsonElement(SearchFunctions("order", null, null)))
                ) as DaemonReadResponse.Rejected)
                .failure,
        )
    }

    private fun readCases(path: Path): List<Triple<DaemonReadTool, JsonElement, ExistingIdeReadOperation>> =
        listOf(
            Triple(
                DaemonReadTool.SEARCH_CLASSES,
                Json.encodeToJsonElement(SearchClasses("Order", null, null)),
                ExistingIdeReadOperation.QUERY_RUN,
            ),
            Triple(
                DaemonReadTool.SEARCH_FUNCTIONS,
                Json.encodeToJsonElement(SearchFunctions("order", null, null)),
                ExistingIdeReadOperation.QUERY_RUN,
            ),
            Triple(
                DaemonReadTool.SEARCH_DECLARATIONS,
                Json.encodeToJsonElement(SearchDeclarations("order", null, null, listOf("property", "type_alias"))),
                ExistingIdeReadOperation.QUERY_RUN,
            ),
            Triple(
                DaemonReadTool.CHECK_DIAGNOSTICS,
                Json.encodeToJsonElement(Diagnostics(".", null)),
                ExistingIdeReadOperation.DIAGNOSTIC_CHECK,
            ),
            Triple(
                DaemonReadTool.QUERY_SYMBOLS,
                request(path).arguments,
                ExistingIdeReadOperation.QUERY_RUN,
            ),
        )
}
