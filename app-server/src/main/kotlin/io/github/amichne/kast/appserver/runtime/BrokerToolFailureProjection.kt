package io.github.amichne.kast.appserver.runtime

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal fun toolFailure(doc: JsonObject, reason: String) = buildJsonObject {
    put("id", doc["id"] ?: JsonNull)
    put(
        "result",
        buildJsonObject {
            put("success", false)
            put(
                "contentItems",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("type", "inputText")
                            put(
                                "text",
                                buildJsonObject {
                                    put("status", "rejected")
                                    put("failure", reason)
                                }
                                    .toString(),
                            )
                        }
                    )
                },
            )
        },
    )
}
    .toString()
