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
import kotlinx.serialization.json.encodeToJsonElement
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class DaemonQueryTest {
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
    private data class ExpectedQualified(
        val type: String,
        val target: DaemonManagementTarget,
        val root: String,
        val document: Document,
    )

    private fun root(): Path = directory.also { Files.writeString(it.resolve("settings.gradle.kts"), "") }.toRealPath()

    private fun request(path: Path) =
        DaemonQueryRequest(
            target,
            path.toString(),
            Json.encodeToJsonElement(Arguments(Source(SourceType.ALL_DECLARATIONS, null, null), null, emptyList())),
        )

    @Test
    fun `identity version and schema reject before workspace effects`() = runBlocking {
        val path = root()
        val query = DaemonQuery(target, { true }, WorkspaceDemand { _, _ -> error("Workspace was prepared") })
        assertEquals(
            DaemonQueryFailure.Protocol(DaemonQueryProtocolFailure.IDENTITY_REJECTED),
            (query.execute(request(path).copy(target = target.copy(stateEpoch = "other")))
                    as DaemonQueryResponse.Rejected)
                .failure,
        )
        assertEquals(
            DaemonQueryFailure.Protocol(DaemonQueryProtocolFailure.UNSUPPORTED_VERSION),
            (query.execute(request(path).copy(version = 2)) as DaemonQueryResponse.Rejected).failure,
        )
        val malformed = request(path).copy(arguments = Json.encodeToJsonElement(Document("wrong schema")))
        assertEquals(
            DaemonQueryFailure.Input(DaemonQueryInputFailure.SchemaRejected),
            (query.execute(malformed) as DaemonQueryResponse.Rejected).failure,
        )
        assertEquals(
            DaemonQueryFailure.Protocol(DaemonQueryProtocolFailure.LIFECYCLE_TRANSITION),
            (DaemonQuery(target, { false }, WorkspaceDemand { _, _ -> error("Workspace was prepared") })
                    .execute(request(path)) as DaemonQueryResponse.Rejected)
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
            DaemonQuery(
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
        val response = assertInstanceOf(DaemonQueryResponse.Qualified::class.java, query.execute(request(path)))
        assertEquals(1, calls)
        assertEquals(target, response.target)
        assertEquals(path.toString(), response.root)
        assertEquals(Json.encodeToJsonElement(Document("partial")), response.document)
        val wire = DaemonQueryProtocol.json.encodeToString(DaemonQueryResponse.serializer(), response)
        assertEquals(
            Json.encodeToJsonElement(ExpectedQualified("qualified", target, path.toString(), Document("partial"))),
            Json.parseToJsonElement(wire),
        )
        assertEquals(response, DaemonQueryProtocol.json.decodeFromString<DaemonQueryResponse>(wire))
    }

    @Test
    fun `client rejects a response for another target without presenting success`() {
        val path = root()
        val admittedRoot = (FilesystemCanonicalRootDiscovery.discover(path) as CanonicalRootDiscovery.Discovered).root
        val response =
            DaemonQueryResponse.Complete(
                target.copy(stateEpoch = "other"),
                path.toString(),
                Json.encodeToJsonElement(Document("success")),
            )
        assertEquals(
            DaemonQueryResult.Rejected(
                DaemonQueryClientRejection.Transport(DaemonQueryClientFailure.RESPONSE_REJECTED)
            ),
            admitQueryResponse(response, target, admittedRoot),
        )
    }

    @Test
    fun `client keeps a correlated qualified document qualified`() {
        val path = root()
        val admittedRoot = (FilesystemCanonicalRootDiscovery.discover(path) as CanonicalRootDiscovery.Discovered).root
        val payload = Json.encodeToJsonElement(Document("partial"))
        val result =
            admitQueryResponse(DaemonQueryResponse.Qualified(target, path.toString(), payload), target, admittedRoot)
        assertEquals(
            CanonicalJsonDocument.generated(Document.serializer()).create(Document("partial")).value,
            assertInstanceOf(DaemonQueryResult.Qualified::class.java, result).document.value,
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
                    DaemonQueryResponse.Complete(target, path.toString(), element),
                ExistingIdeExchange.Semantic(ProjectedOperationOutcome.Rejected(document)) to
                    DaemonQueryResponse.OperationRejected(target, path.toString(), element),
                ExistingIdeExchange.HostRejected(document) to
                    DaemonQueryResponse.OperationRejected(target, path.toString(), element),
                ExistingIdeExchange.Rejected(ExistingIdeFailure.HOST_UNAVAILABLE) to
                    DaemonQueryResponse.Rejected(DaemonQueryFailure.Host(ExistingIdeFailure.HOST_UNAVAILABLE)),
                ExistingIdeExchange.Received(document) to
                    DaemonQueryResponse.Rejected(
                        DaemonQueryFailure.Protocol(DaemonQueryProtocolFailure.RESPONSE_REJECTED)
                    ),
            )
        for ((exchange, expected) in cases) {
            val query =
                DaemonQuery(
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
}
