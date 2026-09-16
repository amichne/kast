package io.github.amichne.kast.appserver.protocol.codex

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject

internal fun completedWithoutAgentMessagesSchema() = Json {
    encodeDefaults = true
}
    .encodeToJsonElement(CompletedSchema(listOf("threadId", "turnId", "item"), CompletedProperties()))
    .jsonObject

@Serializable
private data class CompletedSchema<P>(val required: List<String>, val properties: P, val type: String = "object")

@Serializable
private data class CompletedProperties(
    val threadId: StringSchema = StringSchema(),
    val turnId: StringSchema = StringSchema(),
    val item: CompletedSchema<ItemProperties> = CompletedSchema(listOf("type"), ItemProperties()),
)

@Serializable private data class StringSchema(val type: String = "string")

@Serializable private data class ItemProperties(val type: ItemTypeSchema = ItemTypeSchema())

@Serializable
private data class ItemTypeSchema(
    val enum: List<String> = listOf("dynamicToolCall", "mcpToolCall", "fileChange", "commandExecution")
)
