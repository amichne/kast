package io.github.amichne.kast.appserver.protocol.codex

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject

private val fixtureJson = Json { encodeDefaults = true }

internal fun kastToolCall(arguments: JsonElement): String =
    fixtureJson.encodeToString(ToolCallRequest(ToolCallParameters(arguments)))

internal fun kastDynamicItem(arguments: JsonElement, result: String, completed: Boolean): JsonObject =
    if (completed) {
        fixtureJson.encodeToJsonElement(CompletedToolItem(arguments, listOf(ToolText(result)))).jsonObject
    } else {
        fixtureJson.encodeToJsonElement(StartedToolItem(arguments)).jsonObject
    }

internal fun kastToolCompleted(arguments: JsonElement, result: String): String =
    fixtureJson.encodeToString(
        ToolCompletedNotification(ToolCompletedParameters(kastDynamicItem(arguments, result, true)))
    )

@Serializable
private data class ToolCallRequest(
    val params: ToolCallParameters,
    val id: Int = 9,
    val method: String = "item/tool/call",
)

@Serializable
private data class ToolCallParameters(
    val arguments: JsonElement,
    val threadId: String = "thread-1",
    val turnId: String = "turn-1",
    val callId: String = "call-1",
    val namespace: String = "kast",
    val tool: String = "query_symbols",
)

@Serializable
private data class StartedToolItem(
    val arguments: JsonElement,
    val type: String = "dynamicToolCall",
    val id: String = "call-1",
    val tool: String = "query_symbols",
    val namespace: String = "kast",
    val status: String = "inProgress",
)

@Serializable
private data class CompletedToolItem(
    val arguments: JsonElement,
    val contentItems: List<ToolText>,
    val type: String = "dynamicToolCall",
    val id: String = "call-1",
    val tool: String = "query_symbols",
    val namespace: String = "kast",
    val status: String = "completed",
    val success: Boolean = true,
    val durationMs: Int = 17,
)

@Serializable private data class ToolText(val text: String, val type: String = "inputText")

@Serializable
private data class ToolCompletedNotification(val params: ToolCompletedParameters, val method: String = "item/completed")

@Serializable
private data class ToolCompletedParameters(
    val item: JsonObject,
    val threadId: String = "thread-1",
    val turnId: String = "turn-1",
    val completedAtMs: Int = 20,
)
