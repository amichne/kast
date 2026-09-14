package io.github.amichne.kast.appserver.protocol.codex

import io.github.amichne.kast.appserver.core.ProviderNamespace
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement

internal fun encodeStructuredToolDisplayResult(
    texts: List<String>,
    items: JsonArray,
    namespace: ProviderNamespace,
): JsonElement =
    displayJson.encodeToJsonElement(
        CodexToolDisplayResultDocument(
            content = texts.map(::CodexToolDisplayTextDocument),
            structuredContent =
                when (val structured = admitStructuredResult(items, namespace)) {
                    is StructuredToolResult.Admitted -> structured.document
                    is StructuredToolResult.Unavailable -> null
                },
        )
    )

/** Syntax projection of one owned machine payload; original content remains the display authority. */
private fun admitStructuredResult(items: JsonArray, namespace: ProviderNamespace): StructuredToolResult {
    if (namespace.value != "kast") return StructuredToolResult.Unavailable(StructuredToolResultUnavailable.NOT_KAST)
    val item =
        items.singleOrNull() as? JsonObject
            ?: return StructuredToolResult.Unavailable(StructuredToolResultUnavailable.NOT_SINGLE_TEXT)
    if (item.displayString("type") != "inputText")
        return StructuredToolResult.Unavailable(StructuredToolResultUnavailable.NOT_SINGLE_TEXT)
    val text =
        item.displayString("text")
            ?: return StructuredToolResult.Unavailable(StructuredToolResultUnavailable.NOT_SINGLE_TEXT)
    val parsed =
        try {
            Json.parseToJsonElement(text)
        } catch (_: SerializationException) {
            return StructuredToolResult.Unavailable(StructuredToolResultUnavailable.INVALID_JSON)
        } catch (_: IllegalArgumentException) {
            return StructuredToolResult.Unavailable(StructuredToolResultUnavailable.INVALID_JSON)
        }
    return when (parsed) {
        is JsonObject -> StructuredToolResult.Admitted(parsed)
        else -> StructuredToolResult.Unavailable(StructuredToolResultUnavailable.NOT_OBJECT)
    }
}

private sealed interface StructuredToolResult {
    data class Admitted(val document: JsonObject) : StructuredToolResult

    data class Unavailable(val cause: StructuredToolResultUnavailable) : StructuredToolResult
}

private enum class StructuredToolResultUnavailable {
    NOT_KAST,
    NOT_SINGLE_TEXT,
    INVALID_JSON,
    NOT_OBJECT,
}

private val displayJson = Json {
    encodeDefaults = true
    explicitNulls = false
}

/** `structuredContent` is the upstream schema's opaque JSON value, not new semantic authority. */
@Serializable
private data class CodexToolDisplayResultDocument(
    val content: List<CodexToolDisplayTextDocument>,
    val structuredContent: JsonObject? = null,
)

@Serializable private data class CodexToolDisplayTextDocument(val text: String, val type: String = "text")

private fun JsonObject.displayString(name: String): String? =
    (get(name) as? JsonPrimitive)?.takeIf { it.isString }?.content
