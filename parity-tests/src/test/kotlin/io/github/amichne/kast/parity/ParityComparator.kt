package io.github.amichne.kast.parity

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Strategy for comparing two JSON-RPC responses from different backends.
 */
sealed interface ParityComparator {

    /**
     * Compare [left] and [right] responses.
     * Returns null on match, or a human-readable diff description on mismatch.
     */
    fun compare(left: JsonElement, right: JsonElement): String?

    /** Byte-exact JSON equality. */
    data object Exact : ParityComparator {
        override fun compare(left: JsonElement, right: JsonElement): String? =
            if (left == right) null
            else "Exact mismatch:\n  left:  $left\n  right: $right"
    }

    /**
     * Compares JSON objects field-by-field, treating array fields in [unorderedArrayKeys]
     * as unordered sets (sorted before comparison). All other fields are compared exactly.
     */
    data class Unordered(
        private val unorderedArrayKeys: Set<String>,
    ) : ParityComparator {
        override fun compare(left: JsonElement, right: JsonElement): String? =
            compareElements(left, right, path = "$")

        private fun compareElements(a: JsonElement, b: JsonElement, path: String): String? {
            if (a::class != b::class) {
                return "Type mismatch at $path: ${a::class.simpleName} vs ${b::class.simpleName}"
            }
            return when {
                a is JsonNull -> null
                a is JsonPrimitive && b is JsonPrimitive ->
                    if (a == b) null else "Value mismatch at $path: $a vs $b"

                a is JsonArray && b is JsonArray -> {
                    val sortedA = a.sortedBy { it.toString() }
                    val sortedB = b.sortedBy { it.toString() }
                    if (sortedA.size != sortedB.size) {
                        return "Array size mismatch at $path: ${sortedA.size} vs ${sortedB.size}"
                    }
                    sortedA.zip(sortedB).forEachIndexed { i, (ea, eb) ->
                        compareElements(ea, eb, "$path[$i]")?.let { return it }
                    }
                    null
                }

                a is JsonObject && b is JsonObject -> {
                    val allKeys = a.keys + b.keys
                    for (key in allKeys) {
                        val va = a[key]
                        val vb = b[key]
                        if (va == null) return "Missing key in left at $path.$key"
                        if (vb == null) return "Missing key in right at $path.$key"
                        if (key in unorderedArrayKeys && va is JsonArray && vb is JsonArray) {
                            compareElements(va, vb, "$path.$key")?.let { return it }
                        } else {
                            if (va != vb) return "Value mismatch at $path.$key:\n  left:  $va\n  right: $vb"
                        }
                    }
                    null
                }

                else -> if (a == b) null else "Unknown mismatch at $path"
            }
        }
    }

    /**
     * Structural comparison that ignores volatile fields (e.g., schemaVersion, timing-dependent
     * counters in searchScope) while comparing the semantic content exactly.
     */
    data class Structural(
        private val ignoredKeys: Set<String> = DEFAULT_IGNORED_KEYS,
        private val unorderedArrayKeys: Set<String> = emptySet(),
    ) : ParityComparator {
        override fun compare(left: JsonElement, right: JsonElement): String? =
            compareElements(strip(left), strip(right), path = "$")

        private fun strip(element: JsonElement): JsonElement = when (element) {
            is JsonNull -> element
            is JsonPrimitive -> element
            is JsonArray -> JsonArray(element.map(::strip))
            is JsonObject -> JsonObject(
                element.filterKeys { it !in ignoredKeys }.mapValues { (_, v) -> strip(v) },
            )
        }

        private fun compareElements(a: JsonElement, b: JsonElement, path: String): String? {
            if (a::class != b::class) {
                return "Type mismatch at $path: ${a::class.simpleName} vs ${b::class.simpleName}"
            }
            return when {
                a is JsonNull -> null
                a is JsonPrimitive && b is JsonPrimitive ->
                    if (a == b) null else "Value mismatch at $path: $a vs $b"

                a is JsonArray && b is JsonArray -> {
                    val listA = if (unorderedArrayKeys.isEmpty()) a.toList() else a.sortedBy { it.toString() }
                    val listB = if (unorderedArrayKeys.isEmpty()) b.toList() else b.sortedBy { it.toString() }
                    if (listA.size != listB.size) {
                        return "Array size mismatch at $path: ${listA.size} vs ${listB.size}"
                    }
                    listA.zip(listB).forEachIndexed { i, (ea, eb) ->
                        compareElements(ea, eb, "$path[$i]")?.let { return it }
                    }
                    null
                }

                a is JsonObject && b is JsonObject -> {
                    val allKeys = a.keys + b.keys
                    for (key in allKeys) {
                        val va = a[key]
                        val vb = b[key]
                        if (va == null) return "Missing key in left at $path.$key"
                        if (vb == null) return "Missing key in right at $path.$key"
                        compareElements(va, vb, "$path.$key")?.let { return it }
                    }
                    null
                }

                else -> if (a == b) null else "Unknown mismatch at $path"
            }
        }

        companion object {
            val DEFAULT_IGNORED_KEYS = setOf(
                "schemaVersion",
                "searchScope",
                "backendVersion",
                "backendName",
            )
        }
    }
}
