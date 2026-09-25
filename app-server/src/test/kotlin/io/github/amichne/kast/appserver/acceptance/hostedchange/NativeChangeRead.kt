package io.github.amichne.kast.appserver.acceptance.hostedchange

import io.github.amichne.kast.appserver.publicNameQuery
import io.github.amichne.kast.appserver.query.PublicToolDeclarationKinds
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

internal class NativeChangeRead(private val peer: NativeChangePeer) {
    suspend fun searchClass(): JsonObject = searchClass("NativeChangeTarget", 1)

    suspend fun searchClass(name: String, count: Int): JsonObject =
        search(name = name, kind = PublicToolDeclarationKinds.CLASS, count = count)

    suspend fun searchFunction(name: String, count: Int): JsonObject =
        search(name = name, kind = PublicToolDeclarationKinds.FUNCTION, count = count)

    suspend fun search(name: String, kind: PublicToolDeclarationKinds, count: Int): JsonObject {
        val result =
            peer.call(
                "query_symbols",
                publicNameQuery(name, listOf(kind)).jsonObject,
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
