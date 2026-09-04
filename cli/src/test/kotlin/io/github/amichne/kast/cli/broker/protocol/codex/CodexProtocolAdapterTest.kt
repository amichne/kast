package io.github.amichne.kast.cli.broker.protocol.codex

import io.github.amichne.kast.cli.broker.core.Broker
import io.github.amichne.kast.cli.broker.core.BrokerCallId
import io.github.amichne.kast.cli.broker.core.BrokerInvocationActivity
import io.github.amichne.kast.cli.broker.core.BrokerInvocationActivitySink
import io.github.amichne.kast.cli.broker.core.BrokerInvocationActivityPublication
import io.github.amichne.kast.cli.broker.core.BrokerInvocationCompletion
import io.github.amichne.kast.cli.broker.core.BrokerInvocationContext
import io.github.amichne.kast.cli.broker.core.BrokerLimits
import io.github.amichne.kast.cli.broker.core.BrokerTool
import io.github.amichne.kast.cli.broker.core.ObserverMarkdown
import io.github.amichne.kast.cli.broker.core.ObserverPresentation
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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.util.concurrent.atomic.AtomicInteger

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
            """{"id":2,"result":{"thread":{"id":"thread-1","turns":[]},"cwd":"$cwd"}}"""
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
    fun `owned dynamic lifecycle is projected as structured MCP tool call for downstream observers`() =
        runBlocking {
            val adapter = CodexProtocolAdapter(
                echoBroker(),
                protocolContracts(),
                MemoryThreadCatalogStore(),
            )
            val arguments =
                Json.parseToJsonElement(
                    """{"value":"hello","scope":{"kind":"workspace"}}""",
                )
            val structuredResult =
                Json.parseToJsonElement(
                    """{"value":"hello","details":{"count":2}}""",
                )
            val started =
                """{"method":"item/started","params":{"threadId":"thread-1","turnId":"turn-1","startedAtMs":10,"item":{"type":"dynamicToolCall","id":"call-1","tool":"say","namespace":"echo","arguments":$arguments,"status":"inProgress"}}}"""
            val completed =
                """{"method":"item/completed","params":{"threadId":"thread-1","turnId":"turn-1","completedAtMs":27,"item":{"type":"dynamicToolCall","id":"call-1","tool":"say","namespace":"echo","arguments":$arguments,"status":"completed","contentItems":[{"type":"inputText","text":"{\"value\":\"hello\",\"details\":{\"count\":2}}"}],"success":true,"durationMs":17}}}"""

            val startedDocument = Json.parseToJsonElement(
                (adapter.fromUpstream(started) as ProtocolRouting.ForwardDownstream).message,
            ).jsonObject
            val startedParams = startedDocument.getValue("params").jsonObject
            val startedItem = startedParams.getValue("item").jsonObject
            assertEquals("mcpToolCall", startedItem.getValue("type").jsonPrimitive.content)
            assertEquals("call-1", startedItem.getValue("id").jsonPrimitive.content)
            assertEquals("echo", startedItem.getValue("server").jsonPrimitive.content)
            assertEquals("say", startedItem.getValue("tool").jsonPrimitive.content)
            assertEquals("inProgress", startedItem.getValue("status").jsonPrimitive.content)
            assertEquals(arguments, startedItem.getValue("arguments"))
            assertEquals(10, startedParams.getValue("startedAtMs").jsonPrimitive.content.toLong())

            val completedDocument = Json.parseToJsonElement(
                (adapter.fromUpstream(completed) as ProtocolRouting.ForwardDownstream).message,
            ).jsonObject
            val completedParams = completedDocument.getValue("params").jsonObject
            val completedItem = completedParams.getValue("item").jsonObject
            assertEquals("mcpToolCall", completedItem.getValue("type").jsonPrimitive.content)
            assertEquals("call-1", completedItem.getValue("id").jsonPrimitive.content)
            assertEquals("echo", completedItem.getValue("server").jsonPrimitive.content)
            assertEquals("say", completedItem.getValue("tool").jsonPrimitive.content)
            assertEquals("completed", completedItem.getValue("status").jsonPrimitive.content)
            assertEquals(arguments, completedItem.getValue("arguments"))
            assertEquals(17, completedItem.getValue("durationMs").jsonPrimitive.content.toLong())
            val result = completedItem.getValue("result").jsonObject
            assertEquals(structuredResult, result.getValue("structuredContent"))
            assertEquals(
                structuredResult.toString(),
                result.getValue("content").jsonArray.single().jsonObject
                    .getValue("text").jsonPrimitive.content,
            )
            assertEquals(27, completedParams.getValue("completedAtMs").jsonPrimitive.content.toLong())

            val unowned =
                " { \"method\":\"item/started\",\"params\":{\"threadId\":\"thread-1\",\"turnId\":\"turn-1\",\"startedAtMs\":10,\"item\":{\"type\":\"dynamicToolCall\",\"id\":\"call-2\",\"tool\":\"say\",\"namespace\":\"external\",\"arguments\":{},\"status\":\"inProgress\"}}} "
            assertEquals(
                unowned,
                (adapter.fromUpstream(unowned) as ProtocolRouting.ForwardDownstream).message,
            )
        }

    @Test
    fun `Kast observer projection preserves model authority and emits commentary after sanitized MCP completion`(
        @TempDir temporary: Path,
    ) = runBlocking {
        val cwd = Files.createDirectory(temporary.resolve("workspace")).toRealPath()
        val invocations = AtomicInteger()
        val executedArguments = mutableListOf<JsonElement>()
        val broker = observerBroker(invocations, executedArguments)
        val store = MemoryThreadCatalogStore()
        store.write(ThreadCatalogBinding.admit("thread-1", broker.catalog.digest, cwd).refinedValue())
        val adapter = CodexProtocolAdapter(broker, protocolContracts(), store)
        val arguments = Json.parseToJsonElement(
            """
            {
              "query": "EventConsumer",
              "match": "exact_name",
              "limit": 10,
              "candidate": "candidate:v2:opaque",
              "selector": "exact:v2:opaque",
              "anchor": "source-selector-v1:opaque",
              "continuation": "continuation:opaque",
              "plan": "sha256:plan",
              "target": "exact:v2:target"
            }
            """.trimIndent(),
        )
        val canonicalResult =
            "{\"fingerprint\":\"sha256:model-only\",\"selector\":\"exact:v2:model-only\"}"
        val call = buildJsonObject {
            put("id", 9)
            put("method", "item/tool/call")
            put("params", buildJsonObject {
                put("threadId", "thread-1")
                put("turnId", "turn-1")
                put("callId", "call-1")
                put("namespace", "kast")
                put("tool", "symbol_inspect")
                put("arguments", arguments)
            })
        }.toString()

        val reply = Json.parseToJsonElement(
            (adapter.fromUpstream(call) as ProtocolRouting.ReplyUpstream).message,
        ).jsonObject

        assertEquals(1, invocations.get())
        assertEquals(listOf(arguments), executedArguments)
        assertEquals(
            canonicalResult,
            reply.getValue("result").jsonObject.getValue("contentItems").jsonArray
                .single().jsonObject.getValue("text").jsonPrimitive.content,
        )
        assertFalse(reply.toString().contains("Human observer projection"))

        val started = buildJsonObject {
            put("method", "item/started")
            put("params", buildJsonObject {
                put("threadId", "thread-1")
                put("turnId", "turn-1")
                put("startedAtMs", 10)
                put("item", dynamicKastItem(arguments, canonicalResult, completed = false))
            })
        }.toString()
        val startedItem = Json.parseToJsonElement(
            (adapter.fromUpstream(started) as ProtocolRouting.ForwardDownstream).message,
        ).jsonObject.getValue("params").jsonObject.getValue("item").jsonObject
        assertSanitizedKastArguments(startedItem.getValue("arguments").jsonObject)

        val completed = buildJsonObject {
            put("method", "item/completed")
            put("params", buildJsonObject {
                put("threadId", "thread-1")
                put("turnId", "turn-1")
                put("completedAtMs", 27)
                put("item", dynamicKastItem(arguments, canonicalResult, completed = true))
            })
        }.toString()
        val batch = assertInstanceOf(
            ProtocolRouting.ForwardDownstreamBatch::class.java,
            adapter.fromUpstream(completed),
        )
        val downstream = batch.messages.inOrder().map { message ->
            Json.parseToJsonElement(message).jsonObject
        }
        val toolParams = downstream[0].getValue("params").jsonObject
        val toolItem = toolParams.getValue("item").jsonObject
        val commentaryParams = downstream[1].getValue("params").jsonObject
        val commentaryItem = commentaryParams.getValue("item").jsonObject

        assertEquals("mcpToolCall", toolItem.getValue("type").jsonPrimitive.content)
        assertEquals("call-1", toolItem.getValue("id").jsonPrimitive.content)
        assertSanitizedKastArguments(toolItem.getValue("arguments").jsonObject)
        assertEquals(JsonArray(emptyList()), toolItem.getValue("result").jsonObject.getValue("content"))
        assertFalse("structuredContent" in toolItem.getValue("result").jsonObject)
        assertFalse(downstream[0].toString().contains("sha256:"))
        assertFalse(downstream[0].toString().contains("exact:v"))

        assertEquals("agentMessage", commentaryItem.getValue("type").jsonPrimitive.content)
        assertEquals("commentary", commentaryItem.getValue("phase").jsonPrimitive.content)
        assertFalse(downstream[1].toString().contains("final_answer"))
        assertEquals(
            "**Kast · symbol**\n\nHuman observer projection",
            commentaryItem.getValue("text").jsonPrimitive.content,
        )
        assertTrue(
            commentaryItem.getValue("id").jsonPrimitive.content.startsWith("kast-observer-"),
        )
        listOf(toolParams, commentaryParams).forEach { params ->
            assertEquals("thread-1", params.getValue("threadId").jsonPrimitive.content)
            assertEquals("turn-1", params.getValue("turnId").jsonPrimitive.content)
            assertEquals(27, params.getValue("completedAtMs").jsonPrimitive.content.toLong())
        }

        val replayedCompletion = adapter.fromUpstream(completed)
        assertInstanceOf(ProtocolRouting.ForwardDownstream::class.java, replayedCompletion)

        val finalAnswer =
            " { \"method\":\"item/completed\",\"params\":{\"threadId\":\"thread-1\",\"turnId\":\"turn-1\",\"completedAtMs\":30,\"item\":{\"type\":\"agentMessage\",\"id\":\"answer-1\",\"text\":\"Done.\",\"phase\":\"final_answer\"}}} "
        assertEquals(
            finalAnswer,
            (adapter.fromUpstream(finalAnswer) as ProtocolRouting.ForwardDownstream).message,
        )
        assertEquals(1, invocations.get())
    }

    @Test
    fun `observer state is bounded take-only and cleared on adapter close`() {
        val pending = PendingObserverPresentations.withCapacity(1)
        val first = checkNotNull(BrokerCallId.admit("call-1"))
        val second = checkNotNull(BrokerCallId.admit("call-2"))
        val markdown = ObserverPresentation.Markdown(ObserverMarkdown("observer"))

        assertEquals(PendingObserverPresentationWrite.STORED, pending.put(first, markdown))
        assertEquals(
            PendingObserverPresentationWrite.DISCARDED_CAPACITY,
            pending.put(second, markdown),
        )
        assertEquals(
            PendingObserverPresentationTake.Found(markdown),
            pending.take(first),
        )
        assertEquals(PendingObserverPresentationTake.Missing, pending.take(first))

        assertEquals(PendingObserverPresentationWrite.STORED, pending.put(first, markdown))
        val adapter = CodexProtocolAdapter(
            echoBroker(),
            protocolContracts(),
            MemoryThreadCatalogStore(),
            pendingObserverPresentations = pending,
        )
        adapter.close()

        assertEquals(PendingObserverPresentationTake.Missing, pending.take(first))
    }

    @Test
    fun `commentary contract rejection suppresses companion without weakening the tool call`(
        @TempDir temporary: Path,
    ) = runBlocking {
        val cwd = Files.createDirectory(temporary.resolve("workspace")).toRealPath()
        val broker = observerBroker(AtomicInteger(), mutableListOf())
        val store = MemoryThreadCatalogStore()
        store.write(ThreadCatalogBinding.admit("thread-1", broker.catalog.digest, cwd).refinedValue())
        val adapter = CodexProtocolAdapter(broker, protocolContractsWithoutAgentMessages(), store)
        val arguments = Json.parseToJsonElement("""{"selector":"exact:v2:opaque"}""")
        adapter.fromUpstream(
            """{"id":9,"method":"item/tool/call","params":{"threadId":"thread-1","turnId":"turn-1","callId":"call-1","namespace":"kast","tool":"symbol_inspect","arguments":$arguments}}""",
        )
        val completed = buildJsonObject {
            put("method", "item/completed")
            put("params", buildJsonObject {
                put("threadId", "thread-1")
                put("turnId", "turn-1")
                put("completedAtMs", 27)
                put("item", dynamicKastItem(arguments, "model result", completed = true))
            })
        }.toString()

        val projected = assertInstanceOf(
            ProtocolRouting.ForwardDownstream::class.java,
            adapter.fromUpstream(completed),
        )
        val item = Json.parseToJsonElement(projected.message).jsonObject
            .getValue("params").jsonObject.getValue("item").jsonObject

        assertEquals("mcpToolCall", item.getValue("type").jsonPrimitive.content)
        assertEquals(JsonArray(emptyList()), item.getValue("result").jsonObject.getValue("content"))
    }

    @Test
    fun `observer capacity exhaustion discards only presentation`(
        @TempDir temporary: Path,
    ) = runBlocking {
        val cwd = Files.createDirectory(temporary.resolve("workspace")).toRealPath()
        val invocations = AtomicInteger()
        val broker = observerBroker(invocations, mutableListOf())
        val store = MemoryThreadCatalogStore()
        store.write(ThreadCatalogBinding.admit("thread-1", broker.catalog.digest, cwd).refinedValue())
        val pending = PendingObserverPresentations.withCapacity(1)
        pending.put(
            checkNotNull(BrokerCallId.admit("unrelated-call")),
            ObserverPresentation.Markdown(ObserverMarkdown("unrelated")),
        )
        val adapter = CodexProtocolAdapter(
            broker,
            protocolContracts(),
            store,
            pendingObserverPresentations = pending,
        )
        val arguments = Json.parseToJsonElement("""{"selector":"exact:v2:opaque"}""")

        val reply = adapter.fromUpstream(
            """{"id":9,"method":"item/tool/call","params":{"threadId":"thread-1","turnId":"turn-1","callId":"call-1","namespace":"kast","tool":"symbol_inspect","arguments":$arguments}}""",
        )
        val completed = buildJsonObject {
            put("method", "item/completed")
            put("params", buildJsonObject {
                put("threadId", "thread-1")
                put("turnId", "turn-1")
                put("completedAtMs", 27)
                put("item", dynamicKastItem(arguments, "model result", completed = true))
            })
        }.toString()

        assertInstanceOf(ProtocolRouting.ReplyUpstream::class.java, reply)
        assertEquals(1, invocations.get())
        assertInstanceOf(
            ProtocolRouting.ForwardDownstream::class.java,
            adapter.fromUpstream(completed),
        )
        Unit
    }

    @Test
    fun `failed dynamic lifecycle retains the complete broker result`() = runBlocking {
        val adapter = CodexProtocolAdapter(
            echoBroker(),
            protocolContracts(),
            MemoryThreadCatalogStore(),
        )
        val failure = Json.parseToJsonElement("""{"failure":"INVALID_ARGUMENTS"}""")
        val completed =
            """{"method":"item/completed","params":{"threadId":"thread-1","turnId":"turn-1","completedAtMs":15,"item":{"type":"dynamicToolCall","id":"call-1","tool":"say","namespace":"echo","arguments":{"value":4},"status":"failed","contentItems":[{"type":"inputText","text":"{\"failure\":\"INVALID_ARGUMENTS\"}"}],"success":false,"durationMs":5}}}"""

        val item = Json.parseToJsonElement(
            (adapter.fromUpstream(completed) as ProtocolRouting.ForwardDownstream).message,
        ).jsonObject.getValue("params").jsonObject.getValue("item").jsonObject

        assertEquals("failed", item.getValue("status").jsonPrimitive.content)
        assertFalse("error" in item)
        val result = item.getValue("result").jsonObject
        assertEquals(failure, result.getValue("structuredContent"))
        assertEquals(
            failure.toString(),
            result.getValue("content").jsonArray.single().jsonObject
                .getValue("text").jsonPrimitive.content,
        )
    }

    @Test
    fun `media dynamic result fails closed rather than emitting invalid MCP content`() =
        runBlocking {
            val adapter = CodexProtocolAdapter(
                echoBroker(),
                protocolContracts(),
                MemoryThreadCatalogStore(),
            )
            val completed =
                """{"method":"item/completed","params":{"threadId":"thread-1","turnId":"turn-1","completedAtMs":15,"item":{"type":"dynamicToolCall","id":"call-1","tool":"say","namespace":"echo","arguments":{"value":"image"},"status":"completed","contentItems":[{"type":"inputImage","imageUrl":"data:image/png;base64,AAAA"}],"success":true,"durationMs":5}}}"""

            val closed = assertInstanceOf(
                ProtocolRouting.Close::class.java,
                adapter.fromUpstream(completed),
            )

            assertEquals(
                ProtocolCloseFailure.ToolCallProjectionRejected(
                    CodexToolCallProjectionFailure.MEDIA_CONTENT_UNSUPPORTED,
                ),
                closed.failure,
            )
        }

    @Test
    fun `non-object JSON result remains exact text without invented structure`() = runBlocking {
        val adapter = CodexProtocolAdapter(
            echoBroker(),
            protocolContracts(),
            MemoryThreadCatalogStore(),
        )
        val completed =
            """{"method":"item/completed","params":{"threadId":"thread-1","turnId":"turn-1","completedAtMs":15,"item":{"type":"dynamicToolCall","id":"call-1","tool":"say","namespace":"echo","arguments":{"value":"list"},"status":"completed","contentItems":[{"type":"inputText","text":"[1,2,3]"}],"success":true,"durationMs":5}}}"""

        val result = Json.parseToJsonElement(
            (adapter.fromUpstream(completed) as ProtocolRouting.ForwardDownstream).message,
        ).jsonObject.getValue("params").jsonObject.getValue("item").jsonObject
            .getValue("result").jsonObject

        assertFalse("structuredContent" in result)
        assertEquals(
            "[1,2,3]",
            result.getValue("content").jsonArray.single().jsonObject
                .getValue("text").jsonPrimitive.content,
        )
    }

    @Test
    fun `started dynamic lifecycle rejects completion data`() = runBlocking {
        val adapter = CodexProtocolAdapter(
            echoBroker(),
            protocolContracts(),
            MemoryThreadCatalogStore(),
        )
        val contradictoryStarted =
            """{"method":"item/started","params":{"threadId":"thread-1","turnId":"turn-1","startedAtMs":10,"item":{"type":"dynamicToolCall","id":"call-1","tool":"say","namespace":"echo","arguments":{"value":"hello"},"status":"inProgress","contentItems":[{"type":"inputText","text":"premature result"}],"success":true}}}"""

        val closed = assertInstanceOf(
            ProtocolRouting.Close::class.java,
            adapter.fromUpstream(contradictoryStarted),
        )

        val failure = assertInstanceOf(
            ProtocolCloseFailure.ToolCallProjectionRejected::class.java,
            closed.failure,
        )
        assertEquals("STARTED_COMPLETION_PRESENT", failure.failure.name)
    }

    @Test
    fun `all item-bearing Codex carriers project owned dynamic calls`() = runBlocking {
        val adapter = CodexProtocolAdapter(
            echoBroker(),
            protocolContracts(),
            MemoryThreadCatalogStore(),
        )
        val arguments = Json.parseToJsonElement(
            """{"path":"cli/src/main/kotlin","depth":2}""",
        )
        val structuredResult = Json.parseToJsonElement(
            """{"entries":[{"name":"broker","kind":"directory"}]}""",
        )
        val ownedItem = Json.parseToJsonElement(
            """{"type":"dynamicToolCall","id":"call-1","tool":"read","namespace":"echo","arguments":$arguments,"status":"completed","contentItems":[{"type":"inputText","text":"{\"entries\":[{\"name\":\"broker\",\"kind\":\"directory\"}]}"}],"success":true,"durationMs":12}""",
        ).jsonObject
        val unownedItem = Json.parseToJsonElement(
            """{"type":"dynamicToolCall","id":"call-2","tool":"lookup","namespace":"external","arguments":{"query":"value"},"status":"completed","contentItems":[{"type":"inputText","text":"external result"}],"success":true,"durationMs":3}""",
        ).jsonObject
        fun turn(): JsonObject = buildJsonObject {
            put("id", "turn-1")
            put("status", "completed")
            put("items", buildJsonArray {
                add(ownedItem)
                add(unownedItem)
            })
        }
        fun thread(): JsonObject = buildJsonObject {
            put("id", "thread-1")
            put("turns", buildJsonArray { add(turn()) })
        }
        val timelineBoundary = buildJsonObject {
            put("type", "turnStarted")
            put("position", 0)
            put("turn_id", "turn-1")
        }
        val responseCarriers = listOf(
            "thread/read" to buildJsonObject { put("thread", thread()) },
            "thread/rollback" to buildJsonObject { put("thread", thread()) },
            "thread/revert" to buildJsonObject { put("thread", thread()) },
            "thread/metadata/update" to buildJsonObject { put("thread", thread()) },
            "thread/unarchive" to buildJsonObject { put("thread", thread()) },
            "thread/list" to buildJsonObject {
                put("data", buildJsonArray { add(thread()) })
            },
            "thread/search" to buildJsonObject {
                put("data", buildJsonArray {
                    add(buildJsonObject {
                        put("snippet", "broker result")
                        put("thread", thread())
                    })
                })
            },
            "turn/start" to buildJsonObject { put("turn", turn()) },
            "thread/queue/start" to buildJsonObject { put("turn", turn()) },
            "review/start" to buildJsonObject {
                put("reviewThreadId", "review-thread-1")
                put("turn", turn())
            },
            "thread/turns/list" to buildJsonObject {
                put("data", buildJsonArray { add(turn()) })
            },
            "thread/items/list" to buildJsonObject {
                put("data", buildJsonArray {
                    add(buildJsonObject {
                        put("turnId", "turn-1")
                        put("item", ownedItem)
                    })
                    add(buildJsonObject {
                        put("turnId", "turn-1")
                        put("item", unownedItem)
                    })
                })
            },
            "thread/timeline/list" to buildJsonObject {
                put("data", buildJsonArray {
                    add(timelineBoundary)
                    add(buildJsonObject {
                        put("type", "item")
                        put("position", 1)
                        put("turnId", "turn-1")
                        put("item", ownedItem)
                    })
                    add(buildJsonObject {
                        put("type", "item")
                        put("position", 2)
                        put("turnId", "turn-1")
                        put("item", unownedItem)
                    })
                })
            },
        )

        responseCarriers.forEachIndexed { index, (method, result) ->
            val id = 100 + index
            assertInstanceOf(
                ProtocolRouting.ForwardUpstream::class.java,
                adapter.fromDownstream(
                    buildJsonObject {
                        put("id", id)
                        put("method", method)
                        if (method != "thread/list") {
                            put("params", buildJsonObject {})
                        }
                    }.toString(),
                ),
            )
            val routed = assertInstanceOf(
                ProtocolRouting.ForwardDownstream::class.java,
                adapter.fromUpstream(
                    buildJsonObject {
                        put("id", id)
                        put("result", result)
                    }.toString(),
                ),
            )
            val projectedResult = Json.parseToJsonElement(routed.message).jsonObject
                .getValue("result")
            assertProjectedToolCall(
                projectedResult.objectWithId("call-1"),
                arguments,
                structuredResult,
            )
            assertEquals(unownedItem, projectedResult.objectWithId("call-2"))
            if (method == "thread/timeline/list") {
                assertEquals(
                    timelineBoundary,
                    projectedResult.jsonObject.getValue("data").jsonArray.first(),
                )
            }
        }

        val notificationCarriers = listOf(
            "thread/started" to buildJsonObject { put("thread", thread()) },
            "turn/started" to buildJsonObject {
                put("threadId", "thread-1")
                put("turn", turn())
            },
            "turn/completed" to buildJsonObject {
                put("threadId", "thread-1")
                put("turn", turn())
            },
        )
        notificationCarriers.forEach { (method, params) ->
            val projectedNotification = Json.parseToJsonElement(
                assertInstanceOf(
                    ProtocolRouting.ForwardDownstream::class.java,
                    adapter.fromUpstream(
                        buildJsonObject {
                            put("method", method)
                            put("params", params)
                        }.toString(),
                    ),
                ).message,
            ).jsonObject.getValue("params")
            assertProjectedToolCall(
                projectedNotification.objectWithId("call-1"),
                arguments,
                structuredResult,
            )
            assertEquals(unownedItem, projectedNotification.objectWithId("call-2"))
        }

        val unownedNotification =
            " { \"method\":\"turn/completed\",\"params\":{\"threadId\":\"thread-1\",\"turn\":{\"id\":\"turn-1\",\"status\":\"completed\",\"items\":[$unownedItem]}}} "
        assertEquals(
            unownedNotification,
            assertInstanceOf(
                ProtocolRouting.ForwardDownstream::class.java,
                adapter.fromUpstream(unownedNotification),
            ).message,
        )
    }

    @Test
    fun `resumed thread history projects owned dynamic calls for downstream observers`(
        @TempDir temporary: Path,
    ) = runBlocking {
        val cwd = Files.createDirectory(temporary.resolve("workspace")).toRealPath()
        val broker = echoBroker()
        val store = MemoryThreadCatalogStore()
        store.write(ThreadCatalogBinding.admit("thread-1", broker.catalog.digest, cwd).refinedValue())
        val adapter = CodexProtocolAdapter(broker, protocolContracts(), store)
        val arguments = Json.parseToJsonElement(
            """{"path":"cli/src/main/kotlin","depth":2}""",
        )
        val structuredResult = Json.parseToJsonElement(
            """{"entries":[{"name":"broker","kind":"directory"}]}""",
        )
        val ownedItem = Json.parseToJsonElement(
            """{"type":"dynamicToolCall","id":"call-1","tool":"read","namespace":"echo","arguments":$arguments,"status":"completed","contentItems":[{"type":"inputText","text":"{\"entries\":[{\"name\":\"broker\",\"kind\":\"directory\"}]}"}],"success":true,"durationMs":12}""",
        )
        val unownedItem = Json.parseToJsonElement(
            """{"type":"dynamicToolCall","id":"call-2","tool":"lookup","namespace":"external","arguments":{"query":"value"},"status":"completed","contentItems":[{"type":"inputText","text":"external result"}],"success":true,"durationMs":3}""",
        )
        assertInstanceOf(
            ProtocolRouting.ForwardUpstream::class.java,
            adapter.fromDownstream(
                """{"id":12,"method":"thread/resume","params":{"threadId":"thread-1"}}""",
            ),
        )
        val response = buildJsonObject {
            put("id", 12)
            put("result", buildJsonObject {
                put("thread", buildJsonObject {
                    put("id", "thread-1")
                    put("turns", buildJsonArray {
                        add(buildJsonObject {
                            put("id", "turn-1")
                            put("items", buildJsonArray {
                                add(ownedItem)
                                add(unownedItem)
                            })
                        })
                    })
                })
                put("initialTurnsPage", buildJsonObject {
                    put("data", buildJsonArray {
                        add(buildJsonObject {
                            put("id", "turn-page")
                            put("items", buildJsonArray { add(ownedItem) })
                        })
                    })
                })
                put("cwd", cwd.toString())
            })
        }.toString()

        val forwarded = Json.parseToJsonElement(
            (adapter.fromUpstream(response) as ProtocolRouting.ForwardDownstream).message,
        ).jsonObject
        val items = forwarded.getValue("result").jsonObject
            .getValue("thread").jsonObject
            .getValue("turns").jsonArray.single().jsonObject
            .getValue("items").jsonArray
        val projected = items.first().jsonObject
        assertEquals("mcpToolCall", projected.getValue("type").jsonPrimitive.content)
        assertEquals("call-1", projected.getValue("id").jsonPrimitive.content)
        assertEquals("echo", projected.getValue("server").jsonPrimitive.content)
        assertEquals("read", projected.getValue("tool").jsonPrimitive.content)
        assertEquals(arguments, projected.getValue("arguments"))
        assertEquals(12, projected.getValue("durationMs").jsonPrimitive.content.toLong())
        val result = projected.getValue("result").jsonObject
        assertEquals(structuredResult, result.getValue("structuredContent"))
        assertEquals(
            structuredResult.toString(),
            result.getValue("content").jsonArray.single().jsonObject
                .getValue("text").jsonPrimitive.content,
        )
        assertEquals(unownedItem, items.last())
        val pagedItem = forwarded.getValue("result").jsonObject
            .getValue("initialTurnsPage").jsonObject
            .getValue("data").jsonArray.single().jsonObject
            .getValue("items").jsonArray.single().jsonObject
        assertEquals("mcpToolCall", pagedItem.getValue("type").jsonPrimitive.content)
        assertEquals(arguments, pagedItem.getValue("arguments"))
        assertEquals(
            structuredResult,
            pagedItem.getValue("result").jsonObject.getValue("structuredContent"),
        )
    }

    @Test
    fun `paginated history projects owned dynamic calls for downstream observers`() = runBlocking {
        val adapter = CodexProtocolAdapter(
            echoBroker(),
            protocolContracts(),
            MemoryThreadCatalogStore(),
        )
        val arguments = Json.parseToJsonElement(
            """{"path":"cli/src/main/kotlin","depth":2}""",
        )
        val structuredResult = Json.parseToJsonElement(
            """{"entries":[{"name":"broker","kind":"directory"}]}""",
        )
        val ownedItem = Json.parseToJsonElement(
            """{"type":"dynamicToolCall","id":"call-1","tool":"read","namespace":"echo","arguments":$arguments,"status":"completed","contentItems":[{"type":"inputText","text":"{\"entries\":[{\"name\":\"broker\",\"kind\":\"directory\"}]}"}],"success":true,"durationMs":12}""",
        )
        val unownedItem = Json.parseToJsonElement(
            """{"type":"dynamicToolCall","id":"call-2","tool":"lookup","namespace":"external","arguments":{"query":"value"},"status":"completed","contentItems":[{"type":"inputText","text":"external result"}],"success":true,"durationMs":3}""",
        )

        assertInstanceOf(
            ProtocolRouting.ForwardUpstream::class.java,
            adapter.fromDownstream(
                """{"id":31,"method":"thread/turns/list","params":{"threadId":"thread-1","itemsView":"full"}}""",
            ),
        )
        val turnsResponse = buildJsonObject {
            put("id", 31)
            put("result", buildJsonObject {
                put("data", buildJsonArray {
                    add(buildJsonObject {
                        put("id", "turn-old")
                        put("items", buildJsonArray {
                            add(ownedItem)
                            add(unownedItem)
                        })
                    })
                })
                put("nextCursor", "turn-cursor")
            })
        }.toString()

        val turnsResult = Json.parseToJsonElement(
            (adapter.fromUpstream(turnsResponse) as ProtocolRouting.ForwardDownstream).message,
        ).jsonObject.getValue("result").jsonObject
        val turnItems = turnsResult.getValue("data").jsonArray.single().jsonObject
            .getValue("items").jsonArray
        assertProjectedToolCall(turnItems.first().jsonObject, arguments, structuredResult)
        assertEquals(unownedItem, turnItems.last())
        assertEquals("turn-cursor", turnsResult.getValue("nextCursor").jsonPrimitive.content)

        assertInstanceOf(
            ProtocolRouting.ForwardUpstream::class.java,
            adapter.fromDownstream(
                """{"id":32,"method":"thread/items/list","params":{"threadId":"thread-1","turnId":"turn-old"}}""",
            ),
        )
        val itemsResponse = buildJsonObject {
            put("id", 32)
            put("result", buildJsonObject {
                put("data", buildJsonArray {
                    add(buildJsonObject {
                        put("turnId", "turn-old")
                        put("item", ownedItem)
                    })
                    add(buildJsonObject {
                        put("turnId", "turn-old")
                        put("item", unownedItem)
                    })
                })
                put("nextCursor", "item-cursor")
            })
        }.toString()

        val itemsResult = Json.parseToJsonElement(
            (adapter.fromUpstream(itemsResponse) as ProtocolRouting.ForwardDownstream).message,
        ).jsonObject.getValue("result").jsonObject
        val entries = itemsResult.getValue("data").jsonArray
        assertProjectedToolCall(
            entries.first().jsonObject.getValue("item").jsonObject,
            arguments,
            structuredResult,
        )
        assertEquals(unownedItem, entries.last().jsonObject.getValue("item"))
        assertEquals("item-cursor", itemsResult.getValue("nextCursor").jsonPrimitive.content)
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

        val response =
            """{"id":9,"result":{"thread":{"id":"thread-2","turns":[]},"cwd":"$cwd"}}"""
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
        val response =
            """{"id":7,"result":{"thread":{"id":"thread-first","turns":[]},"cwd":"$cwd"}}"""
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

    private fun observerBroker(
        invocations: AtomicInteger,
        executedArguments: MutableList<JsonElement>,
    ): Broker {
        val openObjectSchema = schema("""{"type":"object"}""")
        val input = JsonDomainDefinition(
            openObjectSchema,
            RefinementDefinition<io.github.amichne.kast.cli.broker.schema.ValidatedJsonValue, ObserverInput, Nothing> { admitted ->
                Validation.validated(ObserverInput(admitted.element))
            },
        )
        val tool: BrokerTool<Unit, ObserverInput, ObserverOutput, Nothing> = BrokerTool(
            toolName("symbol_inspect"),
            ToolDescription.admit("Inspect one symbol.").refinedValue(),
            ToolLoading.DEFERRED,
            input,
            openObjectSchema,
            invoke = { _, inputValue, _ ->
                invocations.incrementAndGet()
                executedArguments += inputValue.arguments
                ProviderCall.Completed(
                    ObserverOutput(
                        buildJsonObject {
                            put("fingerprint", "sha256:model-only")
                            put("selector", "exact:v2:model-only")
                        },
                    ),
                )
            },
            encode = ObserverOutput::document,
            present = { output ->
                ToolPresentation.text(
                    output.document.toString(),
                    success = true,
                    observer = ObserverPresentation.Markdown(
                        ObserverMarkdown("**Kast · symbol**\n\nHuman observer projection"),
                    ),
                )
            },
        )
        val provider = ProviderRegistration.define(
            namespace("kast"),
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

    private fun protocolContractsWithoutAgentMessages(): CodexProtocolContracts {
        val objectSchema = Json.parseToJsonElement("""{"type":"object"}""").jsonObject
        val completedSchema = Json.parseToJsonElement(
            """
            {
              "type": "object",
              "required": ["threadId", "turnId", "item"],
              "properties": {
                "threadId": {"type": "string"},
                "turnId": {"type": "string"},
                "item": {
                  "type": "object",
                  "required": ["type"],
                  "properties": {
                    "type": {"enum": ["dynamicToolCall", "mcpToolCall"]}
                  }
                }
              }
            }
            """.trimIndent(),
        ).jsonObject
        return CodexProtocolContracts.define(
            CodexOwnedSchema.entries.associateWith { schema ->
                if (schema == CodexOwnedSchema.ITEM_COMPLETED_NOTIFICATION) completedSchema
                else objectSchema
            },
        ).validatedValue()
    }

    private fun schema(source: String): CompiledJsonSchema =
        NetworkntJsonSchemaCompiler.compile(Json.parseToJsonElement(source).jsonObject).refinedValue()

    private fun namespace(raw: String): ProviderNamespace =
        ProviderNamespace.admit(raw).refinedValue()

    private fun toolName(raw: String): ToolName = ToolName.admit(raw).refinedValue()

    private fun assertProjectedToolCall(
        item: JsonObject,
        arguments: kotlinx.serialization.json.JsonElement,
        structuredResult: kotlinx.serialization.json.JsonElement,
    ) {
        assertEquals("mcpToolCall", item.getValue("type").jsonPrimitive.content)
        assertEquals("call-1", item.getValue("id").jsonPrimitive.content)
        assertEquals("echo", item.getValue("server").jsonPrimitive.content)
        assertEquals("read", item.getValue("tool").jsonPrimitive.content)
        assertEquals(arguments, item.getValue("arguments"))
        val result = item.getValue("result").jsonObject
        assertEquals(structuredResult, result.getValue("structuredContent"))
        assertEquals(
            structuredResult.toString(),
            result.getValue("content").jsonArray.single().jsonObject
                .getValue("text").jsonPrimitive.content,
        )
    }

    private fun dynamicKastItem(
        arguments: JsonElement,
        result: String,
        completed: Boolean,
    ): JsonObject = buildJsonObject {
        put("type", "dynamicToolCall")
        put("id", "call-1")
        put("tool", "symbol_inspect")
        put("namespace", "kast")
        put("arguments", arguments)
        if (completed) {
            put("status", "completed")
            put("contentItems", buildJsonArray {
                add(buildJsonObject {
                    put("type", "inputText")
                    put("text", result)
                })
            })
            put("success", true)
            put("durationMs", 17)
        } else {
            put("status", "inProgress")
        }
    }

    private fun assertSanitizedKastArguments(arguments: JsonObject) {
        assertEquals("EventConsumer", arguments.getValue("query").jsonPrimitive.content)
        assertEquals("exact_name", arguments.getValue("match").jsonPrimitive.content)
        assertEquals(10, arguments.getValue("limit").jsonPrimitive.content.toInt())
        assertEquals("<candidate>", arguments.getValue("candidate").jsonPrimitive.content)
        assertEquals("<symbol>", arguments.getValue("selector").jsonPrimitive.content)
        assertEquals("<source>", arguments.getValue("anchor").jsonPrimitive.content)
        assertEquals("<continuation>", arguments.getValue("continuation").jsonPrimitive.content)
        assertEquals("<plan>", arguments.getValue("plan").jsonPrimitive.content)
        assertEquals("<symbol>", arguments.getValue("target").jsonPrimitive.content)
    }

    private fun JsonElement.objectWithId(id: String): JsonObject = when (this) {
        is JsonObject -> if (this["id"]?.jsonPrimitive?.content == id) {
            this
        } else {
            values.firstNotNullOfOrNull { value -> value.objectWithIdOrNull(id) }
                ?: throw AssertionError("No object with id=$id in $this")
        }
        is JsonArray -> firstNotNullOfOrNull { value -> value.objectWithIdOrNull(id) }
            ?: throw AssertionError("No object with id=$id in $this")
        else -> throw AssertionError("No object with id=$id in $this")
    }

    private fun JsonElement.objectWithIdOrNull(id: String): JsonObject? = when (this) {
        is JsonObject -> if (this["id"]?.jsonPrimitive?.content == id) {
            this
        } else {
            values.firstNotNullOfOrNull { value -> value.objectWithIdOrNull(id) }
        }
        is JsonArray -> firstNotNullOfOrNull { value -> value.objectWithIdOrNull(id) }
        else -> null
    }

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
    private data class ObserverInput(val arguments: JsonElement)
    private data class ObserverOutput(val document: JsonObject)
}
