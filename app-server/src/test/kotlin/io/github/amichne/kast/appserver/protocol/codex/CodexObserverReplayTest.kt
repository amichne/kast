package io.github.amichne.kast.appserver.protocol.codex

import io.github.amichne.kast.appserver.core.CanonicalBrokerDirectory
import io.github.amichne.kast.appserver.core.ObserverPresentation
import io.github.amichne.kast.appserver.core.ProviderNamespace
import io.github.amichne.kast.appserver.provider.KastInvocationOutput
import io.github.amichne.kast.appserver.provider.KastObserverFixtures
import io.github.amichne.kast.appserver.provider.KastObserverProjector
import io.github.amichne.kast.appserver.provider.KastOperationId
import io.github.amichne.kast.kernel.Refinement
import java.nio.file.Path
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class CodexObserverReplayTest {
    @Test
    fun `applied Kast change retains raw evidence in the expandable tool display`(@TempDir temporary: Path) {
        val namespace =
            when (val admitted = ProviderNamespace.admit("kast")) {
                is Refinement.Refined -> admitted.value
                is Refinement.Rejected -> error("Static namespace rejected")
            }
        val fixture = KastObserverFixtures.changeApply
        val history = buildJsonObject {
            put("cwd", temporary.toRealPath().toString())
            put(
                "thread",
                buildJsonObject {
                    put("id", "thread-1")
                    put(
                        "turns",
                        buildJsonArray {
                            add(
                                buildJsonObject {
                                    put("id", "turn-1")
                                    put(
                                        "items",
                                        buildJsonArray {
                                            add(
                                                buildJsonObject {
                                                    put("type", "dynamicToolCall")
                                                    put("id", "call-1")
                                                    put("namespace", "kast")
                                                    put("tool", "change_apply")
                                                    put("arguments", buildJsonObject {})
                                                    put("status", "completed")
                                                    put("success", true)
                                                    put(
                                                        "contentItems",
                                                        buildJsonArray {
                                                            add(
                                                                buildJsonObject {
                                                                    put("type", "inputText")
                                                                    put("text", fixture)
                                                                }
                                                            )
                                                        },
                                                    )
                                                }
                                            )
                                        },
                                    )
                                }
                            )
                        },
                    )
                },
            )
        }

        val projected = CodexThreadHistoryProjector.project(history, setOf(namespace))
        check(projected is CodexThreadHistoryProjection.Projected)
        assertRawToolDisplay(toolItem(history), toolItem(projected.result))

        val escaped =
            Json.parseToJsonElement(
                    history
                        .toString()
                        .replace(
                            "cli/src/main/kotlin/sample/EventConsumer.kt",
                            "../outside.kt",
                        )
                )
                .jsonObject
        val rejected = CodexThreadHistoryProjector.project(escaped, setOf(namespace))
        check(rejected is CodexThreadHistoryProjection.Projected)
        assertRawToolDisplay(toolItem(escaped), toolItem(rejected.result))
    }

    @Test
    fun `thread reload derives the same expandable native result without pending state`(@TempDir temporary: Path) {
        val namespace =
            when (val admitted = ProviderNamespace.admit("kast")) {
                is Refinement.Refined -> admitted.value
                is Refinement.Rejected -> error("Static namespace rejected")
            }
        val directory = checkNotNull(CanonicalBrokerDirectory.admit(temporary.toRealPath()))
        for ((operation, fixture) in
            listOf(
                "source.read" to KastObserverFixtures.sourceRead,
                "symbol.inspect" to KastObserverFixtures.symbolInspection,
                "symbol.discover" to KastObserverFixtures.symbolDiscovery,
            )) {
            val output = KastInvocationOutput(Json.parseToJsonElement(fixture).jsonObject, true, directory)
            val live = KastObserverProjector.project(checkNotNull(KastOperationId.admit(operation)), output)
            check(live is ObserverPresentation.Markdown)
            val history = buildJsonObject {
                put("cwd", directory.path.toString())
                put(
                    "thread",
                    buildJsonObject {
                        put("id", "thread-1")
                        put(
                            "turns",
                            buildJsonArray {
                                add(
                                    buildJsonObject {
                                        put("id", "turn-1")
                                        put(
                                            "items",
                                            buildJsonArray {
                                                add(
                                                    buildJsonObject {
                                                        put("type", "dynamicToolCall")
                                                        put("id", "call-1")
                                                        put("namespace", "kast")
                                                        put("tool", "source_read")
                                                        put("arguments", buildJsonObject {})
                                                        put("status", "completed")
                                                        put("success", true)
                                                        put("durationMs", 10)
                                                        put(
                                                            "contentItems",
                                                            buildJsonArray {
                                                                add(
                                                                    buildJsonObject {
                                                                        put("type", "inputText")
                                                                        put("text", fixture)
                                                                    }
                                                                )
                                                            },
                                                        )
                                                    }
                                                )
                                            },
                                        )
                                    }
                                )
                            },
                        )
                    },
                )
            }
            val projected = CodexThreadHistoryProjector.project(history, setOf(namespace))
            check(projected is CodexThreadHistoryProjection.Projected)
            assertRawToolDisplay(toolItem(history), toolItem(projected.result))
            assertEquals(
                CodexThreadHistoryProjection.Unchanged,
                CodexThreadHistoryProjector.project(projected.result, setOf(namespace)),
            )
        }
    }

    private fun toolItem(history: JsonObject): JsonObject =
        history
            .getValue("thread")
            .jsonObject
            .getValue("turns")
            .jsonArray
            .single()
            .jsonObject
            .getValue("items")
            .jsonArray
            .single()
            .jsonObject
}
