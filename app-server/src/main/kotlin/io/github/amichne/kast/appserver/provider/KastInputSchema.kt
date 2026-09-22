package io.github.amichne.kast.appserver.provider

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** A generated request is one closed object or a nonempty union whose every branch is a closed object. */
internal fun closedKastInputSchema(document: JsonObject): Boolean =
    closedObject(document) || closedAlternatives(document["anyOf"]) || closedAlternatives(document["oneOf"])

private fun closedAlternatives(element: JsonElement?): Boolean =
    element is JsonArray && element.isNotEmpty() && element.all { it is JsonObject && closedObject(it) }

private fun closedObject(document: JsonObject): Boolean =
    document["type"] == JsonPrimitive("object") && document["additionalProperties"] == JsonPrimitive(false)
