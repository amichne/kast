package io.github.amichne.kast.appserver.acceptance.hostedchange

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal class NativeChangeRead(private val peer: NativeChangePeer) {
    suspend fun searchClass(): JsonObject =
        search(tool = "search_classes", field = "class_name", name = "NativeChangeTarget", count = 1)

    suspend fun searchFunction(name: String, count: Int): JsonObject =
        search(tool = "search_functions", field = "function_name", name = name, count = count)

    suspend fun search(tool: String, field: String, name: String, count: Int): JsonObject {
        val result =
            peer.call(
                tool,
                buildJsonObject {
                    put(field, name)
                    put("name_match", JsonNull)
                    put("scope", JsonNull)
                },
            )
        val payload = result.document()
        demand(!result.rejected() && payload["status"] == JsonPrimitive("complete"), NativeFailure.PROVIDER_REJECTED)
        demand((payload["items"] as? JsonArray)?.size == count, NativeFailure.RESULT_SHAPE_REJECTED)
        demand(
            payload.objectAt("live")["contentView"] == JsonPrimitive("SAVED_PSI_COMMITTED"),
            NativeFailure.PROVIDER_REJECTED,
        )
        return payload
    }
}
