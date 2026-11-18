package io.github.amichne.kast.appserver

import io.github.amichne.kast.appserver.query.PublicToolCheckDiagnostics
import io.github.amichne.kast.appserver.query.PublicToolContract
import io.github.amichne.kast.distribution.managed.CanonicalRootDiscovery
import io.github.amichne.kast.distribution.managed.FilesystemCanonicalRootDiscovery
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.registry.PublicToolCatalog
import io.github.amichne.kast.protocol.registry.PublicToolIdentity
import io.github.amichne.kast.protocol.wire.presentation.CanonicalJsonDocument
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.unixSocket
import io.ktor.client.request.url
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import io.ktor.websocket.send
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PublicInvocationEndpointTest {
    @Test
    fun `incompatible version and relative directory are rejected before the public shell owner runs`() =
        withCoordinatorPayload { root, kast ->
            val invoked = AtomicInteger()
            withInvocations(root, kast, { _, _, _ -> outcome(invoked) }) { socket ->
                for ((request, failure) in
                    listOf(
                        request(root, version = 2) to "UNSUPPORTED_VERSION",
                        request(Path.of("relative"), version = 1) to "INVALID_REQUEST",
                    )) {
                    val reply = exchange(socket) { send(encode(request)) }.single()
                    assertEquals("rejected", reply.string("type"))
                    assertEquals(failure, reply.string("failure"))
                }
                assertEquals(0, invoked.get())
            }
        }

    @Test
    fun `completed reply carries the presented output, status, diagnostics and serving coordinator`() =
        withCoordinatorPayload { root, kast ->
            val seen = mutableListOf<AdmittedPublicInvocation>()
            withInvocations(
                root,
                kast,
                { invocation, _, _ ->
                    seen += invocation
                    PublicInvocationOutcome(2, "presented document", listOf("kast: guidance"))
                },
            ) { socket ->
                val reply = exchange(socket) { send(encode(request(root, version = 1))) }.single()
                assertEquals(setOf("type", "coordinator", "exitCode", "output", "diagnostics"), reply.keys)
                assertEquals("completed", reply.string("type"))
                assertEquals(2, reply.getValue("exitCode").jsonPrimitive.int)
                assertEquals("presented document", reply.string("output"))
                assertEquals(
                    listOf("kast: guidance"),
                    reply.getValue("diagnostics").jsonArray.map { it.jsonPrimitive.content },
                )
                val coordinator = reply.getValue("coordinator").jsonObject
                assertEquals(ProcessHandle.current().pid(), coordinator.getValue("processId").jsonPrimitive.long)
                assertTrue(coordinator.getValue("serviceGeneration").jsonPrimitive.content.isNotBlank())
                assertEquals(root, seen.single().directory)
                assertEquals(listOf("query", "--name", "Fixture"), seen.single().arguments)
                assertEquals("caller", seen.single().environment["KAST_FIXTURE"])
            }
        }

    @Test
    fun `stdin is requested on demand with the owner's bound and delivered byte for byte`() =
        withCoordinatorPayload { root, kast ->
            val input = byteArrayOf(0x7b, 0x00, 0xff.toByte(), 0x7d)
            withInvocations(
                root,
                kast,
                { _, _, stdin ->
                    val bytes = stdin.read(16)
                    PublicInvocationOutcome(0, bytes.joinToString(",") { it.toUByte().toString() }, emptyList())
                },
            ) { socket ->
                val replies =
                    exchange(socket) {
                        send(encode(request(root, version = 1)))
                        val requested =
                            Json.parseToJsonElement((incoming.receive() as Frame.Text).readText()).jsonObject
                        assertEquals("input_required", requested.string("type"))
                        assertEquals(16, requested.getValue("maximumBytes").jsonPrimitive.int)
                        send(Frame.Binary(true, input))
                    }
                assertEquals("123,0,255,125", replies.single().string("output"))
            }
        }

    @Test
    fun `closing the connection cancels the invocation without a reply`() = withCoordinatorPayload { root, kast ->
        val waiting = CountDownLatch(1)
        val cancelled = CountDownLatch(1)
        withInvocations(
            root,
            kast,
            { _, _, stdin ->
                waiting.countDown()
                try {
                    stdin.read(16)
                } catch (interrupted: java.io.InterruptedIOException) {
                    cancelled.countDown()
                    throw interrupted
                }
                PublicInvocationOutcome(0, "unreachable", emptyList())
            },
        ) { socket ->
            exchange(socket) {
                send(encode(request(root, version = 1)))
                incoming.receive()
                assertTrue(waiting.await(5, TimeUnit.SECONDS))
                close()
            }
            assertTrue(cancelled.await(5, TimeUnit.SECONDS), "the executor observed cancellation")
        }
    }

    @Test
    fun `in-process semantic client applies the semantic route's projection and reply admission`() =
        withCoordinatorPayload { root, kast ->
            Files.writeString(root.resolve("settings.gradle.kts"), "")
            val workspace = (FilesystemCanonicalRootDiscovery.discover(root) as CanonicalRootDiscovery.Discovered).root
            val tool =
                (PublicToolContract.admit(
                        PublicToolIdentity.CHECK_DIAGNOSTICS,
                        Json.encodeToJsonElement(
                            PublicToolCheckDiagnostics.serializer(),
                            PublicToolCheckDiagnostics((ProtocolText.parse(".") as Refinement.Refined).value),
                        ),
                        PublicToolCatalog.COMPLETE,
                    ) as Refinement.Refined)
                    .value
            val document = JsonPrimitive("host document")
            val results = mutableListOf<DaemonOperationResult>()
            val semantic = CoordinatorRequestExecutor { request ->
                assertEquals(workspace, request.root)
                DaemonOperationResult.Hosted(CanonicalJsonDocument.generated(JsonElement.serializer()).create(document))
            }
            withInvocations(
                root,
                kast,
                { _, client, _ ->
                    results += client(root, emptyMap()).read(workspace, DaemonOperationCall.PublicTool(tool))
                    PublicInvocationOutcome(0, "", emptyList())
                },
                semantic,
            ) { socket ->
                exchange(socket) { send(encode(request(root, version = 1))) }
                // A raw hosted document is admitted only for canonical and change calls, exactly as over the socket.
                assertEquals(
                    DaemonOperationResult.Rejected(
                        DaemonOperationClientRejection.Transport(DaemonOperationClientFailure.RESPONSE_REJECTED)
                    ),
                    results.single(),
                )
            }
        }

    private fun outcome(invoked: AtomicInteger): PublicInvocationOutcome {
        invoked.incrementAndGet()
        return PublicInvocationOutcome(0, "", emptyList())
    }

    private fun request(directory: Path, version: Int) =
        PublicInvocationRequest(
            version,
            directory.toString(),
            listOf("query", "--name", "Fixture"),
            mapOf("KAST_FIXTURE" to "caller"),
        )

    private fun encode(request: PublicInvocationRequest) =
        PublicInvocationProtocol.json.encodeToString(PublicInvocationRequest.serializer(), request)

    private fun withInvocations(
        root: Path,
        kast: Path,
        executor: PublicInvocationExecutor,
        semantic: CoordinatorRequestExecutor = CoordinatorRequestExecutor { error("no semantic call expected") },
        test: suspend (Path) -> Unit,
    ) = runBlocking {
        val options = (InstalledCoordinatorConfiguration.admit(kast, root, emptyMap()) as Refinement.Refined).value
        val started =
            InstalledCoordinator.start(options, semantic, invocations = executor) as InstalledCoordinatorStart.Started
        try {
            test(options.socket.path)
        } finally {
            started.coordinator.close()
        }
    }

    /** Runs [script] on one invocation connection and returns the text replies through the first terminal one. */
    private suspend fun exchange(
        socket: Path,
        script: suspend DefaultClientWebSocketSession.() -> Unit,
    ): List<JsonObject> =
        HttpClient(CIO) { install(WebSockets) }
            .use { client ->
                val replies = mutableListOf<JsonObject>()
                withTimeout(10_000) {
                    client.webSocket({
                        url("ws://localhost${PublicInvocationProtocol.route}")
                        unixSocket(socket.toString())
                    }) {
                        script()
                        replies += terminalReplies(this)
                    }
                }
                replies
            }

    private suspend fun terminalReplies(session: DefaultClientWebSocketSession): List<JsonObject> {
        val replies = mutableListOf<JsonObject>()
        while (replies.lastOrNull()?.string("type").let { it == null || it == "input_required" }) {
            val frame = session.incoming.receiveCatching().getOrNull() ?: break
            if (frame is Frame.Text) replies += Json.parseToJsonElement(frame.readText()).jsonObject
        }
        return replies
    }

    private fun JsonObject.string(key: String) = getValue(key).jsonPrimitive.content
}
