package io.github.amichne.kast.appserver.provider

import io.github.amichne.kast.appserver.core.ToolPresentation
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Reads only the already schema-admitted source result; source bytes are never reformatted. */
internal fun presentKastSourceOrOutcome(document: JsonObject, success: Boolean): ToolPresentation {
    fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
    val payload = document["document"] as? JsonObject ?: return ToolPresentation.outcome(document, success)
    if (payload.string("operation") != "source.read" || payload.string("format") != "compact")
        return ToolPresentation.outcome(document, success)
    val sections = payload["content"] as? JsonArray ?: return ToolPresentation.outcome(document, success)
    val first = sections.firstOrNull() as? JsonObject ?: return ToolPresentation.outcome(document, success)
    val text = first["text"] as? JsonObject ?: return ToolPresentation.outcome(document, success)
    val source =
        if (first.string("type") == "source" && text.string("type") == "returned") text.string("text") else null
    return if (source != null) ToolPresentation.source(source, document, success)
    else ToolPresentation.outcome(document, success)
}

/** Dynamic members of the source schema after the provider has admitted its result envelope. */
internal data class SourcePresentationParts(val structure: JsonObject, val text: JsonObject)

internal fun sourcePresentationParts(document: JsonObject): SourcePresentationParts? {
    val format = (document["format"] as? JsonPrimitive)?.content
    if (format != "compact") return (document["text"] as? JsonObject)?.let { SourcePresentationParts(document, it) }
    val sections = document["content"] as? JsonArray ?: return null
    if (sections.size != 2) return null
    val source = sections[0] as? JsonObject ?: return null
    val structure = sections[1] as? JsonObject ?: return null
    if (structure["region"] !is JsonObject || structure["entities"] !is JsonArray) return null
    if (
        (source["type"] as? JsonPrimitive)?.content != "source" ||
            (structure["type"] as? JsonPrimitive)?.content != "structure"
    )
        return null
    return (source["text"] as? JsonObject)?.let { SourcePresentationParts(structure, it) }
}
