package io.github.amichne.kast.appserver

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Independent malformed-input corpus; these cases must fail before a semantic invocation. */
internal fun publicToolCase(id: String): JsonElement =
    requireNotNull(object {}.javaClass.getResourceAsStream("/public-tools/schema-cases.json"))
        .bufferedReader()
        .use { Json.parseToJsonElement(it.readText()).jsonArray }
        .single { it.jsonObject.getValue("id").jsonPrimitive.content == id }
        .jsonObject
        .getValue("arguments")
