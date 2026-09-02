package io.github.amichne.kast.cli.broker.schema

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** Deterministic JSON used only for contract identity and bounded process exchange. */
internal fun canonicalJson(element: JsonElement): String = when (element) {
    is JsonObject -> element.entries
        .sortedBy(Map.Entry<String, JsonElement>::key)
        .joinToString(prefix = "{", postfix = "}", separator = ",") { (key, value) ->
            "${kotlinx.serialization.json.JsonPrimitive(key)}:${canonicalJson(value)}"
        }

    is JsonArray -> element.joinToString(prefix = "[", postfix = "]", separator = ",", transform = ::canonicalJson)
    else -> element.toString()
}
