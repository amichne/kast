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
    fun `thread reload derives the same companion from canonical history without pending state`(
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
            assertEquals(2, items.size)
            val companion = items.last().jsonObject
            assertEquals("agentMessage", companion.getValue("type").jsonPrimitive.content)
            assertEquals(live.source.value, companion.getValue("text").jsonPrimitive.content)
            assertTrue("sha256:" !in companion.getValue("text").jsonPrimitive.content)
            assertEquals(history, Json.parseToJsonElement(history.toString()))
            assertEquals(projected, CodexThreadHistoryProjector.project(history, setOf(namespace)))
            assertEquals(CodexThreadHistoryProjection.Unchanged,
                CodexThreadHistoryProjector.project(projected.result, setOf(namespace)))
        }
    }
}
