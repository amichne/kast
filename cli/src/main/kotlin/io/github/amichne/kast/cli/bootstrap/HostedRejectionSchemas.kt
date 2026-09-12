package io.github.amichne.kast.cli.bootstrap

import io.github.amichne.kast.protocol.contract.CanonicalOperation
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/** Packaged rejection schemas and their definition authority; legacy hosted successes are not canonical reads. */
internal object HostedRejectionSchemas {
    private val endpointDocument: JsonObject by lazy { packaged("hosted-endpoint") }

    private val readDocument: JsonObject by lazy { packaged("hosted-query") }

    val endpoint: JsonObject by lazy {
        endpointDocument.getValue("\$defs").jsonObject.getValue("rejected").jsonObject
    }

    private val transport: JsonObject by lazy {
        endpointDocument.getValue("\$defs").jsonObject.getValue("transportRejected").jsonObject
    }

    fun forOperation(operation: CanonicalOperation): JsonObject =
        when (operation) {
            CanonicalOperation.CHANGE_PLAN,
            CanonicalOperation.CHANGE_APPLY,
            CanonicalOperation.CHANGE_RECOVER -> endpoint
            else -> transport
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

    val endpointDefinitions: JsonObject by lazy { endpointDocument.getValue("\$defs").jsonObject }

    val readDefinitions: JsonObject by lazy { readDocument.getValue("\$defs").jsonObject }

    private fun packaged(name: String): JsonObject =
        requireNotNull(CanonicalOperation::class.java.getResourceAsStream("/ide-hosted/$name.schema.json")) {
                "Missing packaged hosted schema: $name"
            }
            .bufferedReader()
            .use { Json.parseToJsonElement(it.readText()).jsonObject }
}
