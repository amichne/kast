package io.github.amichne.kast.cli.broker.protocol.codex

import io.github.amichne.kast.cli.broker.core.Broker
import io.github.amichne.kast.cli.broker.core.BrokerInvocationActivity
import io.github.amichne.kast.cli.broker.core.BrokerInvocationActivitySink
import io.github.amichne.kast.cli.broker.core.BrokerInvocationActivityPublication
import io.github.amichne.kast.cli.broker.core.BrokerInvocationCompletion
import io.github.amichne.kast.cli.broker.core.BrokerInvocationContext
import io.github.amichne.kast.cli.broker.core.BrokerLimits
import io.github.amichne.kast.cli.broker.core.BrokerTool
import io.github.amichne.kast.cli.broker.core.ProviderCall
import io.github.amichne.kast.cli.broker.core.ProviderNamespace
import io.github.amichne.kast.cli.broker.core.ProviderRegistration
import io.github.amichne.kast.cli.broker.core.ProviderStartup
import io.github.amichne.kast.cli.broker.core.ProviderVersion
import io.github.amichne.kast.cli.broker.core.JsonLineBrokerInvocationActivitySink
import io.github.amichne.kast.cli.broker.core.ToolAddress
import io.github.amichne.kast.cli.broker.core.ToolDescription
import io.github.amichne.kast.cli.broker.core.ToolLoading
import io.github.amichne.kast.cli.broker.core.ToolName
import io.github.amichne.kast.cli.broker.core.ToolPresentation
import io.github.amichne.kast.cli.broker.protocol.MemoryThreadCatalogStore
import io.github.amichne.kast.cli.broker.protocol.ThreadCatalogBinding
import io.github.amichne.kast.cli.broker.protocol.ThreadStoreRead
import io.github.amichne.kast.cli.broker.schema.CompiledJsonSchema
import io.github.amichne.kast.cli.broker.schema.JsonDomainDefinition
import io.github.amichne.kast.cli.broker.schema.NetworkntJsonSchemaCompiler
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.RefinementDefinition
import io.github.amichne.kast.kernel.Validation
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.io.ByteArrayOutputStream
import java.io.PrintStream

class CodexProtocolAdapterTest {
    @Test
    fun `owned requests refine while unowned messages remain byte exact`(
        @TempDir temporary: Path,
    ) = runBlocking {
        val cwd = Files.createDirectory(temporary.resolve("workspace")).toRealPath()
        val broker = echoBroker()
        val store = MemoryThreadCatalogStore()
        val adapter = CodexProtocolAdapter(broker, protocolContracts(), store)
        val unowned = " { \"id\" : 8, \"method\" : \"model/list\", \"params\" : {} } "

        val passThrough = adapter.fromDownstream(unowned)
        val initialize = adapter.fromDownstream(
            """{"id":1,"method":"initialize","params":{"clientInfo":{"name":"fixture"},"capabilities":{"requestAttestation":true}}}""",
        )
        val start = adapter.fromDownstream(
            """{"id":2,"method":"thread/start","params":{"cwd":"$cwd","dynamicTools":[],"preserved":"field"}}""",
        )

        assertEquals(unowned, (passThrough as ProtocolRouting.ForwardUpstream).message)
        val initializeParams = (initialize as ProtocolRouting.ForwardUpstream).message.objectValue("params")
        assertEquals(
            true,
            initializeParams.getValue("capabilities").jsonObject
                .getValue("experimentalApi").jsonPrimitive.content.toBoolean(),
        )
        val startParams = (start as ProtocolRouting.ForwardUpstream).message.objectValue("params")
        assertEquals("field", startParams.getValue("preserved").jsonPrimitive.content)
        assertEquals(
            listOf("echo"),
            startParams.getValue("dynamicTools").jsonArray.map { namespace ->
                namespace.jsonObject.getValue("name").jsonPrimitive.content
            },
        )

        val response =
            """{"id":2,"result":{"thread":{"id":"thread-1"},"cwd":"$cwd"}}"""
        assertEquals(
            response,
            (adapter.fromUpstream(response) as ProtocolRouting.ForwardDownstream).message,
        )
        assertInstanceOf(ThreadStoreRead.Found::class.java, store.read("thread-1"))
        Unit
    }

    @Test
    fun `owned dynamic call is consumed and replied upstream`(
        @TempDir temporary: Path,
    ) = runBlocking {
        val cwd = Files.createDirectory(temporary.resolve("workspace")).toRealPath()
        val broker = echoBroker()
        val store = MemoryThreadCatalogStore()
        store.write(ThreadCatalogBinding.admit("thread-1", broker.catalog.digest, cwd).refinedValue())
        val adapter = CodexProtocolAdapter(broker, protocolContracts(), store)
        val call =
            """{"id":9,"method":"item/tool/call","params":{"threadId":"thread-1","turnId":"turn-1","callId":"call-1","namespace":"echo","tool":"say","arguments":{"value":"hello"}}}"""

        val routing = adapter.fromUpstream(call)

        val reply = Json.parseToJsonElement(
            (routing as ProtocolRouting.ReplyUpstream).message,
        ).jsonObject
        assertEquals(9, reply.getValue("id").jsonPrimitive.content.toInt())
        val result = reply.getValue("result").jsonObject
        assertEquals(true, result.getValue("success").jsonPrimitive.content.toBoolean())
        assertEquals(
            "hello",
            result.getValue("contentItems").jsonArray.single().jsonObject
                .getValue("text").jsonPrimitive.content,
        )
    }

    @Test
    fun `owned dynamic call publishes a bounded activity lifecycle`(
        @TempDir temporary: Path,
    ) = runBlocking {
        val cwd = Files.createDirectory(temporary.resolve("workspace")).toRealPath()
        val broker = echoBroker()
        val store = MemoryThreadCatalogStore()
        store.write(ThreadCatalogBinding.admit("thread-1", broker.catalog.digest, cwd).refinedValue())
        val activity = mutableListOf<BrokerInvocationActivity>()
        val adapter = CodexProtocolAdapter(
            broker,
            protocolContracts(),
            store,
            BrokerInvocationActivitySink { event ->
                activity += event
                BrokerInvocationActivityPublication.PUBLISHED
            },
        )

        adapter.fromUpstream(
            """{"id":9,"method":"item/tool/call","params":{"threadId":"thread-1","turnId":"turn-1","callId":"call-1","namespace":"echo","tool":"say","arguments":{"value":"hello"}}}""",
        )

        val started = assertInstanceOf(
            BrokerInvocationActivity.Started::class.java,
            activity.first(),
        )
        val finished = assertInstanceOf(
            BrokerInvocationActivity.Finished::class.java,
            activity.last(),
        )
        assertEquals(2, activity.size)
        assertEquals(started.context.invocationId, finished.context.invocationId)
        assertEquals(started.address, finished.address)
        assertEquals(BrokerInvocationCompletion.COMPLETED, finished.completion)
    }

    @Test
    fun `activity sink exceptions become finite broker failures`(
        @TempDir temporary: Path,
    ) = runBlocking {
        val cwd = Files.createDirectory(temporary.resolve("workspace")).toRealPath()
        val broker = echoBroker()
        val store = MemoryThreadCatalogStore()
        store.write(ThreadCatalogBinding.admit("thread-1", broker.catalog.digest, cwd).refinedValue())
        val adapter = CodexProtocolAdapter(
            broker,
            protocolContracts(),
            store,
            BrokerInvocationActivitySink { throw IllegalStateException("fixture sink failure") },
        )

        val routing = adapter.fromUpstream(
            """{"id":9,"method":"item/tool/call","params":{"threadId":"thread-1","turnId":"turn-1","callId":"call-1","namespace":"echo","tool":"say","arguments":{"value":"hello"}}}""",
        )

        val result = Json.parseToJsonElement(
            (routing as ProtocolRouting.ReplyUpstream).message,
        ).jsonObject.getValue("result").jsonObject
        assertFalse(result.getValue("success").jsonPrimitive.content.toBoolean())
        assertEquals(
            "BROKER_ACTIVITY_UNAVAILABLE",
            result.getValue("contentItems").jsonArray.single().jsonObject
                .getValue("text").jsonPrimitive.content,
        )
    }

    @Test
    fun `activity JSON lines omit invocation payloads`(@TempDir temporary: Path) {
        val context = BrokerInvocationContext.admit(
            "thread-1",
            "turn-1",
            "call-1",
            Files.createDirectory(temporary.resolve("workspace")).toRealPath(),
        ).refinedValue()
        val address = ToolAddress(namespace("echo"), toolName("say"))
        val bytes = ByteArrayOutputStream()
        val output = PrintStream(bytes, true, Charsets.UTF_8)
        val sink = JsonLineBrokerInvocationActivitySink(output)

        assertEquals(
            BrokerInvocationActivityPublication.PUBLISHED,
            sink.publish(BrokerInvocationActivity.Started(context, address)),
        )
        assertEquals(
            BrokerInvocationActivityPublication.PUBLISHED,
            sink.publish(
                BrokerInvocationActivity.Finished(
                    context,
                    address,
                    BrokerInvocationCompletion.COMPLETED,
                ),
            ),
        )

        val documents = bytes.toString(Charsets.UTF_8).lineSequence()
            .filter(String::isNotBlank)
            .map { line -> Json.parseToJsonElement(line).jsonObject }
            .toList()
        assertEquals(
            listOf("tool-call-started", "tool-call-finished"),
            documents.map { document -> document.getValue("event").jsonPrimitive.content },
        )
        assertEquals("completed", documents.last().getValue("completion").jsonPrimitive.content)
        documents.forEach { document ->
            assertFalse("arguments" in document)
            assertFalse("result" in document)
            assertFalse("workingDirectory" in document)
        }
    }

    @Test
    fun `upstream request id collision cannot consume a pending thread response`(
        @TempDir temporary: Path,
    ) = runBlocking {
        val cwd = Files.createDirectory(temporary.resolve("workspace")).toRealPath()
        val broker = echoBroker()
        val store = MemoryThreadCatalogStore()
        store.write(ThreadCatalogBinding.admit("thread-1", broker.catalog.digest, cwd).refinedValue())
        val adapter = CodexProtocolAdapter(broker, protocolContracts(), store)
        assertInstanceOf(
            ProtocolRouting.ForwardUpstream::class.java,
            adapter.fromDownstream(
                """{"id":9,"method":"thread/start","params":{"cwd":"$cwd"}}""",
            ),
        )

        val call = adapter.fromUpstream(
            """{"id":9,"method":"item/tool/call","params":{"threadId":"thread-1","turnId":"turn-1","callId":"call-1","namespace":"echo","tool":"say","arguments":{"value":"hello"}}}""",
        )
        assertInstanceOf(ProtocolRouting.ReplyUpstream::class.java, call)

        val response = """{"id":9,"result":{"thread":{"id":"thread-2"},"cwd":"$cwd"}}"""
        assertEquals(
            response,
            (adapter.fromUpstream(response) as ProtocolRouting.ForwardDownstream).message,
        )
        assertInstanceOf(ThreadStoreRead.Found::class.java, store.read("thread-2"))
        Unit
    }

    @Test
    fun `duplicate downstream request id is rejected without replacing the first proof`(
        @TempDir temporary: Path,
    ) = runBlocking {
        val cwd = Files.createDirectory(temporary.resolve("workspace")).toRealPath()
        val store = MemoryThreadCatalogStore()
        val adapter = CodexProtocolAdapter(echoBroker(), protocolContracts(), store)
        val request = """{"id":7,"method":"thread/start","params":{"cwd":"$cwd"}}"""

        assertInstanceOf(
            ProtocolRouting.ForwardUpstream::class.java,
            adapter.fromDownstream(request),
        )
        assertInstanceOf(
            ProtocolRouting.ReplyDownstream::class.java,
            adapter.fromDownstream(request),
        )
        val response = """{"id":7,"result":{"thread":{"id":"thread-first"},"cwd":"$cwd"}}"""
        assertEquals(
            response,
            (adapter.fromUpstream(response) as ProtocolRouting.ForwardDownstream).message,
        )
        assertInstanceOf(ThreadStoreRead.Found::class.java, store.read("thread-first"))
        Unit
    }

    private fun echoBroker(): Broker {
        val inputSchema = schema(
            """{"type":"object","additionalProperties":false,"required":["value"],"properties":{"value":{"type":"string","minLength":1}}}""",
        )
        val outputSchema = schema(
            """{"type":"object","additionalProperties":false,"required":["value"],"properties":{"value":{"type":"string"}}}""",
        )
        val input = JsonDomainDefinition(
            inputSchema,
            RefinementDefinition { admitted ->
                Validation.validated(
                    EchoInput(
                        admitted.element.jsonObject.getValue("value").jsonPrimitive.content,
                    ),
                )
            },
        )
        val tool: BrokerTool<Unit, EchoInput, EchoOutput, Nothing> = BrokerTool(
            toolName("say"),
            ToolDescription.admit("Echo a value.").refinedValue(),
            ToolLoading.EAGER,
            input,
            outputSchema,
            invoke = { _, argument, _ -> ProviderCall.Completed(EchoOutput(argument.value)) },
            encode = { output -> buildJsonObject { put("value", output.value) } },
            present = { output -> ToolPresentation.text(output.value, success = true) },
        )
        val provider = ProviderRegistration.define(
            namespace("echo"),
            ProviderVersion.admit("1.0.0").refinedValue(),
            listOf(tool),
            start = { ProviderStartup.Started(Unit) },
        ).validatedValue()
        return Broker.create(listOf(provider), BrokerLimits.defaults()).validatedValue()
    }

    private fun protocolContracts(): CodexProtocolContracts {
        val objectSchema = Json.parseToJsonElement("""{"type":"object"}""").jsonObject
        return CodexProtocolContracts.define(
            CodexOwnedSchema.entries.associateWith { objectSchema },
        ).validatedValue()
    }

    private fun schema(source: String): CompiledJsonSchema =
        NetworkntJsonSchemaCompiler.compile(Json.parseToJsonElement(source).jsonObject).refinedValue()

    private fun namespace(raw: String): ProviderNamespace =
        ProviderNamespace.admit(raw).refinedValue()

    private fun toolName(raw: String): ToolName = ToolName.admit(raw).refinedValue()

    private fun String.objectValue(name: String): JsonObject =
        Json.parseToJsonElement(this).jsonObject.getValue(name).jsonObject

    private fun <Strong, Failure> Refinement<Strong, Failure>.refinedValue(): Strong = when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> throw AssertionError("Expected refinement, received $failure")
    }

    private fun <Strong, Failure> Validation<Strong, Failure>.validatedValue(): Strong = when (this) {
        is Validation.Validated -> value
        is Validation.Rejected -> throw AssertionError("Expected validation, received $failures")
    }

    private data class EchoInput(val value: String)
    private data class EchoOutput(val value: String)
}
