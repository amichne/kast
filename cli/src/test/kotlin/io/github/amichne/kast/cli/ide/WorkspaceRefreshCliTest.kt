package io.github.amichne.kast.cli.ide

import io.github.amichne.kast.appserver.ide.CanonicalRootDiscoverer
import io.github.amichne.kast.appserver.ide.CanonicalRootDiscovery
import io.github.amichne.kast.appserver.ide.ExistingIdeClient
import io.github.amichne.kast.appserver.ide.ExistingIdeDescriptor
import io.github.amichne.kast.appserver.ide.ExistingIdeDocuments
import io.github.amichne.kast.appserver.ide.ExistingIdeExchange
import io.github.amichne.kast.appserver.ide.ExistingIdeFailure
import io.github.amichne.kast.appserver.ide.ExistingIdeOperation
import io.github.amichne.kast.appserver.ide.canonicalRootFixture
import io.github.amichne.kast.appserver.ide.encodeControlRequest
import io.github.amichne.kast.protocol.contract.WorkspaceRefreshCommand
import io.github.amichne.kast.protocol.contract.WorkspaceRefreshEffect
import io.github.amichne.kast.protocol.contract.WorkspaceRefreshResponse
import io.github.amichne.kast.protocol.contract.WorkspaceRefreshResult
import io.github.amichne.kast.protocol.contract.WorkspaceRefreshRule
import io.github.amichne.kast.protocol.wire.presentation.ProjectedOperationOutcome
import java.nio.file.Path
import java.util.UUID
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

class WorkspaceRefreshCliTest {
    private val root = canonicalRootFixture(Path.of("/workspace"))
    private val host = UUID.fromString("00000000-0000-0000-0000-000000000001")
    private val descriptor = ExistingIdeDescriptor(123, host)
    private val command = WorkspaceRefreshCommand.Request("refresh-1", WorkspaceRefreshEffect.FILE_REFRESH)

    @Test
    fun `ambiguous request and nested rule fields are rejected before command dispatch`() {
        for (request in
            listOf<WorkspaceRefreshCommand>(
                command,
                WorkspaceRefreshCommand.Configure(
                    WorkspaceRefreshRule.TaskSuccess(":build", WorkspaceRefreshEffect.FILE_REFRESH)
                ),
            )) {
            val encoded = Json.encodeToString(request)
            val ambiguous = encoded.replace("\"effect\":", "\"effect\":\"GRADLE_MODEL_RELOAD\",\"effect\":")
            assertEquals(
                io.github.amichne.kast.kernel.Refinement.Rejected(ExistingIdeFailure.INVALID_REQUEST),
                ExistingIdeDocuments.admitRefreshCommand(ambiguous),
            )
        }
    }

    @Test
    fun `refresh request status and configure route through both existing IDE command families`() {
        for (family in listOf("index", "ide")) {
            for (request in
                listOf(
                    command,
                    WorkspaceRefreshCommand.Status("refresh-1"),
                    WorkspaceRefreshCommand.Configure(WorkspaceRefreshRule.Off),
                    WorkspaceRefreshCommand.Configure(
                        WorkspaceRefreshRule.TaskSuccess(":compileKotlin", WorkspaceRefreshEffect.GRADLE_MODEL_RELOAD)
                    ),
                )) {
                var calls = 0
                executeExistingIdeCli(
                    listOf(family, "refresh", Json.encodeToString<WorkspaceRefreshCommand>(request)),
                    root.path,
                    CanonicalRootDiscoverer { CanonicalRootDiscovery.Discovered(root) },
                    ExistingIdeClient { actualRoot, operation ->
                        calls++
                        assertEquals(root, actualRoot)
                        assertEquals(ExistingIdeOperation.Refresh(request), operation)
                        ExistingIdeExchange.Rejected(ExistingIdeFailure.HOST_UNAVAILABLE)
                    },
                )
                assertEquals(1, calls)
            }
        }
    }

    @Test
    fun `transport encodes its discriminator and command as a typed document`() {
        val request = ExistingIdeOperation.Refresh(command).encodeControlRequest(root)
        val outer = Json.parseToJsonElement(request.toString(Charsets.UTF_8)).jsonObject
        assertEquals(setOf("root", "type", "document"), outer.keys)
        assertEquals("WORKSPACE_REFRESH", outer.getValue("type").jsonPrimitive.content)
        assertEquals(root.path.toString(), outer.getValue("root").jsonPrimitive.content)
        val inner = Json.parseToJsonElement(outer.getValue("document").jsonPrimitive.content).jsonObject
        assertEquals(setOf("type", "requestId", "effect"), inner.keys)
        assertEquals("request", inner.getValue("type").jsonPrimitive.content)
        assertEquals("FILE_REFRESH", inner.getValue("effect").jsonPrimitive.content)
    }

    @Test
    fun `pending and failed refresh responses retain qualified and rejected process outcomes`() {
        val pending =
            WorkspaceRefreshResult.Pending(
                "refresh-1",
                io.github.amichne.kast.protocol.contract.WorkspaceRefreshStage.EFFECT,
            )
        val failed =
            WorkspaceRefreshResult.Failed(
                "refresh-1",
                io.github.amichne.kast.protocol.contract.WorkspaceRefreshFailure.EFFECT_FAILED,
            )
        val rejected =
            WorkspaceRefreshResult.Rejected(io.github.amichne.kast.protocol.contract.WorkspaceRefreshFailure.CAPACITY)
        for (result in listOf(pending, failed, rejected)) {
            val response = WorkspaceRefreshResponse(root.path.toString(), host.toString(), result)
            val exchange =
                ExistingIdeDocuments.response(
                    Json.encodeToString(response).toByteArray(),
                    root,
                    ExistingIdeOperation.Refresh(command),
                    descriptor,
                ) as ExistingIdeExchange.Semantic
            if (result is WorkspaceRefreshResult.Pending)
                assertInstanceOf(
                    io.github.amichne.kast.protocol.wire.presentation.ProjectedOperationOutcome.Qualified::class.java,
                    exchange.outcome,
                )
            else
                assertInstanceOf(
                    io.github.amichne.kast.protocol.wire.presentation.ProjectedOperationOutcome.Rejected::class.java,
                    exchange.outcome,
                )
        }
    }

    @Test
    fun `schema and typed admission reject wrong root host request id and incompatible variants`() {
        val response =
            WorkspaceRefreshResponse(
                root.path.toString(),
                host.toString(),
                WorkspaceRefreshResult.Complete("refresh-1"),
            )
        fun exchange(value: WorkspaceRefreshResponse) =
            ExistingIdeDocuments.response(
                Json.encodeToString(value).toByteArray(),
                root,
                ExistingIdeOperation.Refresh(command),
                descriptor,
            )
        assertInstanceOf(ExistingIdeExchange.Received::class.java, exchange(response))
        for (invalid in
            listOf(
                response.copy(root = "/other"),
                response.copy(host = "00000000-0000-0000-0000-000000000002"),
                response.copy(result = WorkspaceRefreshResult.Complete("other")),
                response.copy(result = WorkspaceRefreshResult.Configured(WorkspaceRefreshRule.Off)),
            )) assertEquals(ExistingIdeExchange.Rejected(ExistingIdeFailure.RESPONSE_REJECTED), exchange(invalid))
        val encoded = Json.encodeToString(response)
        for (invalid in
            listOf(encoded.replace("complete", "unrecognized"), encoded.replace("requestId", "unknownField"))) {
            assertEquals(
                ExistingIdeExchange.Rejected(ExistingIdeFailure.RESPONSE_REJECTED),
                ExistingIdeDocuments.response(
                    invalid.toByteArray(),
                    root,
                    ExistingIdeOperation.Refresh(command),
                    descriptor,
                ),
            )
        }
    }
}
