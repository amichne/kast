package io.github.amichne.kast.cli.bootstrap

import io.github.amichne.kast.protocol.contract.CanonicalOperation
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/** Packaged rejection schemas and their definition authority; legacy hosted successes are not canonical reads. */
internal object HostedRejectionSchemas {
    private val readDocument: JsonObject by lazy { packaged("hosted-query") }

    val endpoint: JsonObject by lazy {
        packaged("hosted-endpoint").getValue("\$defs").jsonObject.getValue("rejected").jsonObject
    }

    val read: JsonObject by lazy {
        readDocument
            .getValue("oneOf")
            .jsonArray
            .single { variant ->
                variant.jsonObject.getValue("properties").jsonObject.getValue("outcome").jsonObject["const"] ==
                    JsonPrimitive("rejected")
            }
            .jsonObject
    }

    val readDefinitions: JsonObject by lazy { readDocument.getValue("\$defs").jsonObject }

    private fun packaged(name: String): JsonObject =
        requireNotNull(CanonicalOperation::class.java.getResourceAsStream("/ide-hosted/$name.schema.json")) {
                "Missing packaged hosted schema: $name"
            }
            .bufferedReader()
            .use { Json.parseToJsonElement(it.readText()).jsonObject }
}
