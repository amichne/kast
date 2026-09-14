package io.github.amichne.kast.appserver.protocol.codex

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

internal fun JsonElement.objectWithId(id: String): JsonObject =
    when (this) {
        is JsonObject ->
            if (this["id"]?.jsonPrimitive?.content == id) {
                this
            } else {
                values.firstNotNullOfOrNull { value -> value.objectWithIdOrNull(id) }
                    ?: throw AssertionError("No object with id=$id in $this")
            }
        is JsonArray ->
            firstNotNullOfOrNull { value -> value.objectWithIdOrNull(id) }
                ?: throw AssertionError("No object with id=$id in $this")
        else -> throw AssertionError("No object with id=$id in $this")
    }

private fun JsonElement.objectWithIdOrNull(id: String): JsonObject? =
    when (this) {
        is JsonObject ->
            if (this["id"]?.jsonPrimitive?.content == id) {
                this
            } else {
                values.firstNotNullOfOrNull { value -> value.objectWithIdOrNull(id) }
            }
        is JsonArray -> firstNotNullOfOrNull { value -> value.objectWithIdOrNull(id) }
        else -> null
    }
