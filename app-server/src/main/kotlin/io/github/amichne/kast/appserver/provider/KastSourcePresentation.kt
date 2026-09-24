package io.github.amichne.kast.appserver.provider

import io.github.amichne.kast.appserver.core.ToolPresentation
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/** Reads only the already schema-admitted source result; source bytes are never reformatted. */
internal fun presentKastSourceOrOutcome(document: JsonObject, success: Boolean): ToolPresentation {
    fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
    val payload = document["document"] as? JsonObject ?: return ToolPresentation.outcome(document, success)
    if (payload.string("operation") == "query.run") {
        val summary = querySummary(payload)
        return if (summary != null) ToolPresentation.summary(summary, document, success)
        else ToolPresentation.outcome(document, success)
    }
    if (payload.string("operation") == "diagnostic.check") {
        val summary = diagnosticSummary(payload)
        return if (summary != null) ToolPresentation.summary(summary, document, success)
        else ToolPresentation.outcome(document, success)
    }
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

private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun querySummary(payload: JsonObject): String? {
    val status = payload.string("status")
    if (status != "complete" && status != "qualified") return null
    val items = payload["items"] as? JsonArray ?: return null
    val failures = payload["failures"] as? JsonArray ?: return null
    val lines =
        items.take(10).mapNotNull { value ->
            val item = value as? JsonObject ?: return@mapNotNull null
            val name = item.string("name") ?: return@mapNotNull null
            val kind = item.string("kind") ?: return@mapNotNull null
            val location = item["location"] as? JsonObject
            val file = location?.string("file")
            val offset =
                (location?.get("offset") as? JsonPrimitive)?.intOrNull
                    ?: ((location?.get("range") as? JsonObject)?.get("startInclusive") as? JsonPrimitive)?.intOrNull
            val qualifiedIdentity = (item["signature"] as? JsonObject)?.string("qualifiedIdentity")
            buildString {
                append(name).append(" — ").append(kind)
                if (file != null) {
                    append("\n").append(file)
                    if (offset != null) append(" @ offset ").append(offset)
                }
                if (qualifiedIdentity != null) append("\n").append(qualifiedIdentity)
            }
        }
    val completion = if (status == "complete") "requested scope exhausted" else "partial; absence unverified"
    val failureNote = if (failures.isEmpty()) "" else "; ${failures.size} item failures"
    val count = "${items.size} ${if (items.size == 1) "result" else "results"}; $completion$failureNote"
    return (lines + count).joinToString("\n").take(4_000)
}

private fun diagnosticSummary(payload: JsonObject): String? {
    val status = payload.string("status")
    if (status != "complete" && status != "qualified") return null
    val diagnostics = payload["diagnostics"] as? JsonArray ?: return null
    val progress = payload["progress"] as? JsonObject
    val analyzed = (progress?.get("analyzedFiles") as? JsonArray)?.size
    val inventory = progress?.get("inventory") as? JsonObject
    val discovered = (inventory?.get("totalFiles") as? JsonPrimitive)?.intOrNull
    val coverage =
        if (analyzed != null && discovered != null) "$analyzed of $discovered analyzed files"
        else if (analyzed != null) "$analyzed analyzed files; discovery incomplete" else "coverage unavailable"
    val clean = status == "complete" && diagnostics.isEmpty() && analyzed != null && analyzed == discovered
    val opening =
        if (clean) "No diagnostics in $coverage."
        else
            "${diagnostics.size} diagnostics returned; $coverage${if (status == "qualified") "; scan incomplete" else ""}."
    return "$opening IDE file diagnostics; project build not run."
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
