package io.github.amichne.kast.appserver.provider

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Closed inspection acquisition labels; missing or unknown evidence has no observer presentation. */
internal enum class ObserverSymbolAcquisition(val label: String) {
    STRICT("compiler-confirmed"),
    REACQUIRED("freshly reacquired");

    companion object {
        fun from(document: JsonObject): ObserverSymbolAcquisition? {
            val field = document["acquisition"] as? JsonPrimitive ?: return null
            if (!field.isString) return null
            return when (field.content) {
                "strict" -> STRICT
                "reacquired" -> REACQUIRED
                else -> null
            }
        }
    }
}
