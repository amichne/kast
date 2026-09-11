package io.github.amichne.kast.appserver.runtime

import io.github.amichne.kast.appserver.*
import io.github.amichne.kast.appserver.core.*
import io.github.amichne.kast.appserver.protocol.*
import io.github.amichne.kast.appserver.protocol.codex.*
import io.github.amichne.kast.appserver.provider.*
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.Validation
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.*
import io.ktor.client.request.unixSocket
import io.ktor.client.request.url
import io.ktor.websocket.*
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.json.*

/** Test-only scripted upstream; every provider call executes the real private installed Kast product. */
object InstalledWorkspaceRoutingAcceptanceMain {
    @JvmStatic
    fun main(arguments: Array<String>): Unit = runBlocking {
        require(arguments.size == 5)
        val product = Path.of(arguments[0]).toRealPath()
        val first = Path.of(arguments[1]).toRealPath()
        val second = Path.of(arguments[2]).toRealPath()
        val report = Path.of(arguments[3])
        val ready = Path.of(arguments[4])
        val fixture = first.parent
        val scope = Files.createDirectory(fixture.resolve("routing-harness"))
        stage(Stage.PROVIDER_QUALIFICATION)
        val options = KastProviderOptions.admit(product.resolve("bin/kast"), first).refined()
        val qualification = KastProviderQualifier.qualify(options)
        check(qualification is KastProviderQualification.Qualified) {
            "installed provider qualification rejected: $qualification"
        }
        val broker = Broker.create(listOf(qualification.registration), BrokerLimits.defaults()).validated()
        val store = FileThreadCatalogStore.open(scope.resolve("threads.json"))
        check(store is FileThreadCatalogStoreOpen.Opened)
        val enrollment = WorkspaceEnrollmentStore(product.resolve("config/workspaces.json")).read()
        check(enrollment is EnrollmentRead.Read)
        val owner = BrokerInstallationState.observe(product).refined()
        // Only the upstream protocol is scripted. Installed semantic schemas, provider
        // subprocesses, registry, worker admission and indexers remain production paths.
        val shape = buildJsonObject { put("type", "object") }
        val contracts = CodexProtocolContracts.define(CodexOwnedSchema.entries.associateWith { shape }).validated()
        val connecting = Channel<ScriptedUpstream>(4)
        val socket = BrokerSocketPath.admit(fixture.resolve("routing.sock")).validated()
        val server =
            KtorBrokerServer.start(
                KtorBrokerServerOptions(
                    socket,
                    broker,
                    contracts,
                    store.store,
                    BrokerUpstreamConnector { BrokerUpstreamConnectionAdmission.Connected(connecting.receive()) },
                    4,
                    4 * 1024 * 1024,
                    enrollment = enrollment.enrollment,
                    bindingOwner = owner,
                    sessionBootstrap = qualification.bootstrap,
                    invocationJournal = scope.resolve("invocations.json"),
                )
            )
        check(server is KtorBrokerServerStart.Started) { "routing server rejected: $server" }
        val client = HttpClient(CIO) { install(WebSockets) { maxFrameSize = 4 * 1024 * 1024 } }
        val peers = mutableListOf<Peer>()
        suspend fun connect(): Peer {
            val upstream = ScriptedUpstream()
            connecting.send(upstream)
            val connection = client.webSocketSession {
                url("ws://localhost/rpc")
                unixSocket(socket.path.toString())
            }
            val peer = Peer(connection, upstream)
            peers += peer
            connection.send(
                """{"id":0,"method":"initialize","params":{"clientInfo":{"name":"installed-routing-fixture"}}}"""
            )
            upstream.sent.receive()
            upstream.received.send(BrokerUpstreamFrame.Text("""{"id":0,"result":{}}"""))
            peer.response()
            connection.send("""{"method":"initialized"}""")
            upstream.sent.receive()
            return peer
        }
        try {
            withTimeout(600_000) {
                stage(Stage.CLIENT_CONNECTIONS)
                val a = connect()
                val b = connect()
                stage(Stage.THREAD_BINDINGS)
                a.bind("thread-a", first)
                b.bind("thread-b", second)
                // Also put another root on the same client, proving binding is not a connection cwd.
                a.bind("thread-a-second", second)
                val bindingsBefore = mapOf("thread-a" to first, "thread-b" to second, "thread-a-second" to second)
                bindingsBefore.forEach { (thread, root) ->
                    val binding = store.store.read(thread)
                    check(binding is ThreadStoreRead.Found && binding.binding.workspace.root.path == root)
                }
                stage(Stage.INTERLEAVED_DISPATCH)
                a.upstream.call("thread-a", "call-1", "FirstWorkspaceValue")
                b.upstream.call("thread-b", "call-1", "SecondWorkspaceValue")
                val independent = b.toolResult("SecondWorkspaceValue", "FirstWorkspaceValue")
                check(!Files.exists(first.resolve(".acceptance-import-release"))) {
                    "B did not complete during delayed A import"
                }
                stage(Stage.READY_B_COMPLETED)
                Files.writeString(ready, "ready-workspace-semantic-complete\n")
                stage(Stage.DELAYED_A_COMPLETION)
                val delayed = a.toolResult("FirstWorkspaceValue", "SecondWorkspaceValue")
                a.upstream.call("thread-a-second", "call-2", "SecondWorkspaceValue")
                val interleaved = a.toolResult("SecondWorkspaceValue", "FirstWorkspaceValue")
                stage(Stage.RECONNECT)
                a.connection.close()
                val reconnected = connect()
                reconnected.bind("thread-a", first, resume = true)
                reconnected.upstream.call("thread-a", "call-3", "FirstWorkspaceValue")
                val resumed = reconnected.toolResult("FirstWorkspaceValue", "SecondWorkspaceValue")
                val persisted = FileThreadCatalogStore.open(scope.resolve("threads.json"))
                check(persisted is FileThreadCatalogStoreOpen.Opened)
                bindingsBefore.forEach { (thread, root) ->
                    val binding = persisted.store.read(thread)
                    check(
                        binding is ThreadStoreRead.Found &&
                            binding.binding.workspace.root.path == root &&
                            binding.binding.owner == owner
                    )
                }
                stage(Stage.COMPLETE)
                Files.writeString(
                    report,
                    buildJsonObject {
                        put("status", "complete")
                        put("upstream", "scripted protocol fixture; stock Codex qualification is a separate gate")
                        put("provider", "qualified private installed Kast executable")
                        put("installationId", owner.installationId.value)
                        put("stateEpoch", owner.stateEpoch.value.toString())
                        put("readyBCompletedDuringImportA", true)
                        put("reconnectRetainedBindings", true)
                        putJsonObject("threadRoots") {
                            bindingsBefore.forEach { (thread, root) -> put(thread, root.toString()) }
                        }
                        putJsonArray("semanticResults") {
                            listOf(independent, delayed, interleaved, resumed).forEach(::add)
                        }
                    }
                        .toString() + "\n",
                )
            }
        } finally {
            peers.forEach {
                try {
                    it.connection.close()
                } catch (_: Exception) {}
            }
            client.close()
            server.server.close()
        }
    }

    private enum class Stage {
        PROVIDER_QUALIFICATION,
        CLIENT_CONNECTIONS,
        THREAD_BINDINGS,
        INTERLEAVED_DISPATCH,
        READY_B_COMPLETED,
        DELAYED_A_COMPLETION,
        RECONNECT,
        COMPLETE,
    }

    private fun stage(stage: Stage) =
        System.err.println(
            buildJsonObject {
                put("event", "installed-routing-acceptance")
                put("stage", stage.name)
            }
        )

    private class ScriptedUpstream : BrokerUpstreamConnection {
        val sent = Channel<String>(32)
        val received = Channel<BrokerUpstreamFrame>(32)

        override suspend fun send(message: String): BrokerUpstreamSend {
            sent.send(message)
            return BrokerUpstreamSend.SENT
        }

        override suspend fun receive(): BrokerUpstreamFrame =
            received.receiveCatching().getOrNull() ?: BrokerUpstreamFrame.Closed

        override suspend fun close() {
            sent.close()
            received.close()
        }

        suspend fun call(thread: String, call: String, symbol: String) {
            received.send(
                BrokerUpstreamFrame.Text(
                    buildJsonObject {
                        put("id", 7)
                        put("method", "item/tool/call")
                        putJsonObject("params") {
                            put("threadId", thread)
                            put("turnId", "turn-$call")
                            put("callId", call)
                            put("namespace", "kast")
                            put("tool", "query")
                            putJsonObject("arguments") {
                                put("type", "QUERY")
                                putJsonObject("from") {
                                    put("type", "SEARCH")
                                    put("query", symbol)
                                }
                            }
                        }
                    }
                        .toString()
                )
            )
        }
    }

    private class Peer(val connection: DefaultClientWebSocketSession, val upstream: ScriptedUpstream) {
        suspend fun response(): JsonObject {
            val frame = connection.incoming.receive()
            check(frame is Frame.Text)
            return Json.parseToJsonElement(frame.readText()).jsonObject
        }

        suspend fun bind(thread: String, root: Path, resume: Boolean = false) {
            connection.send(
                buildJsonObject {
                    put("id", 1)
                    put("method", if (resume) "thread/resume" else "thread/start")
                    putJsonObject("params") {
                        put("cwd", root.toString())
                        if (resume) put("threadId", thread)
                    }
                }
                    .toString()
            )
            val forwarded = Json.parseToJsonElement(upstream.sent.receive()).jsonObject
            check(forwarded["method"] == JsonPrimitive(if (resume) "thread/resume" else "thread/start"))
            upstream.received.send(
                BrokerUpstreamFrame.Text(
                    buildJsonObject {
                        put("id", 1)
                        putJsonObject("result") {
                            put("cwd", root.toString())
                            putJsonObject("thread") {
                                put("id", thread)
                                putJsonArray("turns") {}
                            }
                        }
                    }
                        .toString()
                )
            )
            check(response()["error"] == null) { "thread binding rejected" }
        }

        suspend fun toolResult(own: String, other: String): JsonObject {
            val document = Json.parseToJsonElement(upstream.sent.receive()).jsonObject
            val result = document["result"]?.jsonObject
            check(result?.get("success") == JsonPrimitive(true)) { "semantic provider rejected: $document" }
            check(own in document.toString() && other !in document.toString()) {
                "semantic result used wrong workspace: $document"
            }
            return document
        }
    }

    private fun <T, E> Refinement<T, E>.refined(): T = (this as Refinement.Refined).value

    private fun <T, E> Validation<T, E>.validated(): T = (this as Validation.Validated).value
}
