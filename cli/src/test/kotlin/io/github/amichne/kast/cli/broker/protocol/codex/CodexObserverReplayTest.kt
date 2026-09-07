package io.github.amichne.kast.cli.broker.protocol.codex

import io.github.amichne.kast.cli.broker.core.CanonicalBrokerDirectory
import io.github.amichne.kast.cli.broker.core.ObserverPresentation
import io.github.amichne.kast.cli.broker.core.ProviderNamespace
import io.github.amichne.kast.cli.broker.provider.KastInvocationOutput
import io.github.amichne.kast.cli.broker.provider.KastObserverFixtures
import io.github.amichne.kast.cli.broker.provider.KastObserverProjector
import io.github.amichne.kast.cli.broker.provider.KastOperationId
import io.github.amichne.kast.kernel.Refinement
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class CodexObserverReplayTest {
    @Test
    fun `applied Kast change replays as one native file change item`(
        @TempDir temporary: Path,
    ) {
        val namespace = when (val admitted = ProviderNamespace.admit("kast")) {
            is Refinement.Refined -> admitted.value
            is Refinement.Rejected -> error("Static namespace rejected")
        }
        val fixture = KastObserverFixtures.changeApply
        val history = buildJsonObject {
            put("cwd", temporary.toRealPath().toString())
            put("thread", buildJsonObject {
                put("id", "thread-1")
                put("turns", buildJsonArray {
                    add(buildJsonObject {
                        put("id", "turn-1")
                        put("items", buildJsonArray {
                            add(buildJsonObject {
                                put("type", "dynamicToolCall"); put("id", "call-1")
                                put("namespace", "kast"); put("tool", "change_apply")
                                put("arguments", buildJsonObject {})
                                put("status", "completed"); put("success", true)
                                put("contentItems", buildJsonArray {
                                    add(buildJsonObject { put("type", "inputText"); put("text", fixture) })
                                })
                            })
                        })
                    })
                })
            })
        }

        val projected = CodexThreadHistoryProjector.project(history, setOf(namespace))
        check(projected is CodexThreadHistoryProjection.Projected)
        val item = projected.result.getValue("thread").jsonObject.getValue("turns")
            .jsonArray.single().jsonObject.getValue("items").jsonArray.single().jsonObject
        assertEquals("fileChange", item.getValue("type").jsonPrimitive.content)
        assertEquals("call-1", item.getValue("id").jsonPrimitive.content)
        assertEquals("completed", item.getValue("status").jsonPrimitive.content)
        val change = item.getValue("changes").jsonArray.single().jsonObject
        assertEquals(
            "cli/src/main/kotlin/sample/EventConsumer.kt",
            change.getValue("path").jsonPrimitive.content,
        )
        assertEquals("update", change.getValue("kind").jsonObject.getValue("type").jsonPrimitive.content)
        assertEquals(
            "@@ class EventConsumer @@\n-    fun consume() = old()\n+    fun consume() = new()",
            change.getValue("diff").jsonPrimitive.content,
        )

        val escaped = Json.parseToJsonElement(
            history.toString().replace(
                "cli/src/main/kotlin/sample/EventConsumer.kt",
                "../outside.kt",
            ),
        ).jsonObject
        val rejected = CodexThreadHistoryProjector.project(escaped, setOf(namespace))
        check(rejected is CodexThreadHistoryProjection.Projected)
        val fallback = rejected.result.getValue("thread").jsonObject.getValue("turns")
            .jsonArray.single().jsonObject.getValue("items").jsonArray.single().jsonObject
        assertEquals("mcpToolCall", fallback.getValue("type").jsonPrimitive.content)
    }

    @Test
    fun `thread reload derives the same expandable native result without pending state`(
        @TempDir temporary: Path,
    ) {
        val namespace = when (val admitted = ProviderNamespace.admit("kast")) {
            is Refinement.Refined -> admitted.value
            is Refinement.Rejected -> error("Static namespace rejected")
        }
        val directory = checkNotNull(CanonicalBrokerDirectory.admit(temporary.toRealPath()))
        for ((operation, fixture) in listOf(
            "source.read" to KastObserverFixtures.sourceRead,
            "symbol.inspect" to KastObserverFixtures.symbolInspection,
            "symbol.discover" to KastObserverFixtures.symbolDiscovery,
        )) {
            val output = KastInvocationOutput(Json.parseToJsonElement(fixture).jsonObject, true, directory)
            val live = KastObserverProjector.project(checkNotNull(KastOperationId.admit(operation)), output)
            check(live is ObserverPresentation.Markdown)
            val history = buildJsonObject {
                put("cwd", directory.path.toString())
                put("thread", buildJsonObject {
                    put("id", "thread-1")
                    put("turns", buildJsonArray {
                        add(buildJsonObject {
                            put("id", "turn-1")
                            put("items", buildJsonArray {
                                add(buildJsonObject {
                                    put("type", "dynamicToolCall"); put("id", "call-1")
                                    put("namespace", "kast"); put("tool", "source_read")
                                    put("arguments", buildJsonObject {})
                                    put("status", "completed"); put("success", true)
                                    put("durationMs", 10)
                                    put("contentItems", buildJsonArray {
                                        add(buildJsonObject { put("type", "inputText"); put("text", fixture) })
                                    })
                                })
                            })
                        })
                    })
                })
            }
            val projected = CodexThreadHistoryProjector.project(history, setOf(namespace))
            check(projected is CodexThreadHistoryProjection.Projected)
            val items = projected.result.getValue("thread").jsonObject.getValue("turns")
                .jsonArray.single().jsonObject.getValue("items").jsonArray
            assertEquals(1, items.size)
            val native = items.single().jsonObject
            assertEquals("mcpToolCall", native.getValue("type").jsonPrimitive.content)
            val content = native.getValue("result").jsonObject.getValue("content").jsonArray
            assertEquals(live.source.value, content.single().jsonObject.getValue("text").jsonPrimitive.content)
            assertTrue("sha256:" !in native.toString())
            assertEquals(history, Json.parseToJsonElement(history.toString()))
            assertEquals(projected, CodexThreadHistoryProjector.project(history, setOf(namespace)))
            assertEquals(CodexThreadHistoryProjection.Unchanged,
                CodexThreadHistoryProjector.project(projected.result, setOf(namespace)))
        }
    }
}
