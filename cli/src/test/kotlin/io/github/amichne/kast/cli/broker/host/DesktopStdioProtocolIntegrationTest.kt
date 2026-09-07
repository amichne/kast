package io.github.amichne.kast.cli.broker.host

import io.github.amichne.kast.cli.broker.CodexIntegrationRun
import io.github.amichne.kast.cli.broker.core.AgentSessionBootstrap
import io.github.amichne.kast.cli.broker.core.Broker
import io.github.amichne.kast.cli.broker.core.BrokerLimits
import io.github.amichne.kast.cli.broker.core.BrokerTool
import io.github.amichne.kast.cli.broker.core.ProviderCall
import io.github.amichne.kast.cli.broker.core.ProviderNamespace
import io.github.amichne.kast.cli.broker.core.ProviderRegistration
import io.github.amichne.kast.cli.broker.core.ProviderStartup
import io.github.amichne.kast.cli.broker.core.ProviderVersion
import io.github.amichne.kast.cli.broker.core.ToolLoading
import io.github.amichne.kast.cli.broker.core.ToolDescription
import io.github.amichne.kast.cli.broker.core.ToolName
import io.github.amichne.kast.cli.broker.core.ToolPresentation
import io.github.amichne.kast.cli.broker.protocol.MemoryThreadCatalogStore
import io.github.amichne.kast.cli.broker.protocol.codex.CodexOwnedSchema
import io.github.amichne.kast.cli.broker.protocol.codex.CodexProtocolContracts
import io.github.amichne.kast.cli.broker.protocol.codex.agentSessionBootstrapFixture
import io.github.amichne.kast.cli.broker.runtime.BrokerSocketPath
import io.github.amichne.kast.cli.broker.runtime.BrokerUpstreamConnection
import io.github.amichne.kast.cli.broker.runtime.BrokerUpstreamConnectionAdmission
import io.github.amichne.kast.cli.broker.runtime.BrokerUpstreamConnector
import io.github.amichne.kast.cli.broker.runtime.BrokerUpstreamFrame
import io.github.amichne.kast.cli.broker.runtime.BrokerUpstreamSend
import io.github.amichne.kast.cli.broker.runtime.KtorBrokerServer
import io.github.amichne.kast.cli.broker.runtime.KtorBrokerServerOptions
import io.github.amichne.kast.cli.broker.runtime.KtorBrokerServerStart
import io.github.amichne.kast.cli.broker.runtime.connectCodexUnixWebSocket
import io.github.amichne.kast.cli.broker.schema.JsonDomainDefinition
import io.github.amichne.kast.cli.broker.schema.ValidatedJsonValue
import io.github.amichne.kast.kernel.RefinementDefinition
import io.github.amichne.kast.kernel.Validation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

class DesktopStdioProtocolIntegrationTest {
    @Test
    fun `desktop stdio injects one qualified catalog and reaches broker dispatch`(
        @TempDir temporary: Path,
    ) = runBlocking {
        val socket = Path.of("/private/tmp/kast-desktop-${UUID.randomUUID()}.sock")
        val bootstrap = agentSessionBootstrapFixture()
        val invocations = AtomicInteger()
        val upstream = FakeCodexUpstream()
        val server = KtorBrokerServer.start(
            KtorBrokerServerOptions(
                publicSocket = BrokerSocketPath.admit(socket).validatedValue(),
                broker = broker(bootstrap, invocations),
                contracts = protocolContracts(),
                threadStore = MemoryThreadCatalogStore(),
                upstream = BrokerUpstreamConnector {
                    BrokerUpstreamConnectionAdmission.Connected(upstream)
                },
                maximumConnections = 1,
                maximumMessageBytes = MAXIMUM_MESSAGE_BYTES,
                sessionBootstrap = bootstrap,
            ),
        ) as KtorBrokerServerStart.Started
        val hostInput = PipedInputStream()
        val desktopOutput = PipedOutputStream(hostInput)
        val desktopInput = PipedInputStream()
        val hostOutput = PipedOutputStream(desktopInput)
        val reader = BufferedReader(InputStreamReader(desktopInput, Charsets.UTF_8))
        val host = DesktopStdioHost(
            input = hostInput,
            output = hostOutput,
            connector = BrokerUpstreamConnector {
                connectCodexUnixWebSocket(socket, MAXIMUM_MESSAGE_BYTES, 2_000)
            },
            maximumMessageBytes = MAXIMUM_MESSAGE_BYTES,
            shutdownHooks = NoOpShutdownHooks,
        )
        val running = async { host.run(server.server::close) }
        val cwd = Files.createDirectory(temporary.resolve("workspace")).toRealPath()
        try {
            desktopOutput.send(
                """{"id":0,"method":"initialize","params":{"clientInfo":{"name":"desktop-fixture"}}}""",
            )
            val initialize = withTimeout(2_000) { upstream.sentByBroker.receive() }
            assertTrue(initialize.contains("\"experimentalApi\":true"))
            upstream.receivedFromCodex.send(
                BrokerUpstreamFrame.Text("""{"id":0,"result":{}}"""),
            )
            assertEquals(
                Json.parseToJsonElement("""{"id":0,"result":{}}"""),
                Json.parseToJsonElement(reader.readBoundedLine()),
            )

            desktopOutput.send("""{"id":1,"method":"thread/start","params":{"cwd":"$cwd"}}""")
            val start = Json.parseToJsonElement(
                withTimeout(2_000) { upstream.sentByBroker.receive() },
            ).jsonObject
            val namespace = start.getValue("params").jsonObject
                .getValue("dynamicTools").jsonArray.single().jsonObject
            assertEquals("kast", namespace.getValue("name").jsonPrimitive.content)
            assertEquals(
                bootstrap.tools.definitions.map { definition -> definition.name.value },
                namespace.getValue("tools").jsonArray.map { tool ->
                    tool.jsonObject.getValue("name").jsonPrimitive.content
                },
            )
            upstream.receivedFromCodex.send(
                BrokerUpstreamFrame.Text(
                    """{"id":1,"result":{"thread":{"id":"thread-1","turns":[]},"cwd":"$cwd"}}""",
                ),
            )
            reader.readBoundedLine()

            val toolName = bootstrap.tools.definitions.single().name.value
            upstream.receivedFromCodex.send(
                BrokerUpstreamFrame.Text(
                    """{"id":9,"method":"item/tool/call","params":{"threadId":"thread-1","turnId":"turn-1","callId":"call-1","namespace":"kast","tool":"$toolName","arguments":{}}}""",
                ),
            )
            val reply = Json.parseToJsonElement(
                withTimeout(2_000) { upstream.sentByBroker.receive() },
            ).jsonObject
            assertEquals(9, reply.getValue("id").jsonPrimitive.content.toInt())
            assertEquals(
                true,
                reply.getValue("result").jsonObject.getValue("success")
                    .jsonPrimitive.content.toBoolean(),
            )
            assertEquals(1, invocations.get())

            desktopOutput.close()
            assertEquals(CodexIntegrationRun.Completed(0), withTimeout(5_000) { running.await() })
        } finally {
            desktopOutput.close()
            if (!running.isCompleted) server.server.close()
            Files.deleteIfExists(socket.resolveSibling("${socket.fileName}.lock"))
        }
    }

    private fun broker(
        bootstrap: AgentSessionBootstrap,
        invocations: AtomicInteger,
    ): Broker {
        val definition = bootstrap.tools.definitions.single()
        val input = JsonDomainDefinition(
            definition.inputSchema,
            RefinementDefinition<ValidatedJsonValue, Unit, Nothing> {
                Validation.validated(Unit)
            },
        )
        val tool: BrokerTool<Unit, Unit, JsonObject, Nothing> = BrokerTool(
            ToolName.admit(definition.name.value).refinedValue(),
            ToolDescription.admit(definition.description.value).refinedValue(),
            ToolLoading.DEFERRED,
            input,
            definition.outputSchema,
            invoke = { _, _, _ ->
                invocations.incrementAndGet()
                ProviderCall.Completed(buildJsonObject {})
            },
            encode = { it },
            present = { ToolPresentation.text("{}", success = true) },
        )
        val provider = ProviderRegistration.define(
            ProviderNamespace.admit("kast").refinedValue(),
            ProviderVersion.admit("1.0.0").refinedValue(),
            listOf(tool),
            start = { ProviderStartup.Started(Unit) },
        ).validatedValue()
        return Broker.create(listOf(provider), BrokerLimits.defaults()).validatedValue()
    }

    private fun protocolContracts(): CodexProtocolContracts {
        val schema = Json.parseToJsonElement("""{"type":"object"}""").jsonObject
        return CodexProtocolContracts.define(
            CodexOwnedSchema.entries.associateWith { schema },
        ).validatedValue()
    }

    private suspend fun BufferedReader.readBoundedLine(): String = withTimeout(2_000) {
        withContext(Dispatchers.IO) { readLine() }
    }

    private fun PipedOutputStream.send(message: String) {
        write("$message\n".toByteArray(Charsets.UTF_8))
        flush()
    }

    private class FakeCodexUpstream : BrokerUpstreamConnection {
        val sentByBroker = Channel<String>(Channel.UNLIMITED)
        val receivedFromCodex = Channel<BrokerUpstreamFrame>(Channel.UNLIMITED)

        override suspend fun send(message: String): BrokerUpstreamSend {
            sentByBroker.send(message)
            return BrokerUpstreamSend.SENT
        }

        override suspend fun receive(): BrokerUpstreamFrame =
            receivedFromCodex.receiveCatching().getOrNull() ?: BrokerUpstreamFrame.Closed

        override suspend fun close() {
            sentByBroker.close()
            receivedFromCodex.close()
        }
    }

    private data object NoOpShutdownHooks : io.github.amichne.kast.cli.broker
        .CodexIntegrationShutdownHooks {
        override fun register(hook: Thread) = Unit
        override fun remove(hook: Thread) = Unit
    }

    private fun <Strong, Failure> io.github.amichne.kast.kernel.Refinement<Strong, Failure>
        .refinedValue(): Strong = when (this) {
        is io.github.amichne.kast.kernel.Refinement.Refined -> value
        is io.github.amichne.kast.kernel.Refinement.Rejected ->
            throw AssertionError("Expected refinement, received $failure")
    }

    private fun <Strong, Failure> Validation<Strong, Failure>.validatedValue(): Strong =
        when (this) {
            is Validation.Validated -> value
            is Validation.Rejected ->
                throw AssertionError("Expected validation, received $failures")
        }

    private companion object {
        const val MAXIMUM_MESSAGE_BYTES = 1024 * 1024
    }
}
