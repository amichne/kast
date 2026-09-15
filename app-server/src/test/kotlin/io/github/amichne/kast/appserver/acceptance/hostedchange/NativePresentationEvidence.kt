package io.github.amichne.kast.appserver.acceptance.hostedchange

import io.github.amichne.kast.appserver.core.ToolPresentation
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

@Serializable
internal data class NativePresentationEvidence(
    val items: Int,
    val textUtf8Bytes: Long,
    val sourcePlacement: NativeSourcePlacement,
)

@Serializable
internal enum class NativeSourcePlacement {
    NOT_REQUESTED,
    VERIFIED,
    MISMATCH,
}

internal fun nativePresentationEvidence(presentation: ToolPresentation): NativePresentationEvidence {
    val envelope = Json.parseToJsonElement(presentation.content.last().text) as? JsonObject
    val payload = envelope?.get("document") as? JsonObject
    val sections = payload?.get("content") as? JsonArray
    val first = sections?.firstOrNull() as? JsonObject
    val text = first?.get("text") as? JsonObject
    val requested = payload?.get("format") == JsonPrimitive("compact") && text?.get("type") == JsonPrimitive("returned")
    val placement =
        when {
            !requested -> NativeSourcePlacement.NOT_REQUESTED
            presentation.content.size == 2 && JsonPrimitive(presentation.content.first().text) == text?.get("text") ->
                NativeSourcePlacement.VERIFIED
            else -> NativeSourcePlacement.MISMATCH
        }
    return NativePresentationEvidence(
        presentation.content.size,
        presentation.content.sumOf { it.text.toByteArray(Charsets.UTF_8).size.toLong() },
        placement,
    )
}
