package io.github.amichne.kast.distribution.output

import io.github.amichne.kast.distribution.contract.configuration.CliOutputFailure
import io.github.amichne.kast.kernel.Refinement
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** One strict JSON value at the dynamic user-projection boundary; no canonical response schema is implied. */
class JsonProjectionDocument
private constructor(val text: String, internal val tree: JsonElement, internal val depth: Int) {
    companion object {
        fun parse(text: String): Refinement<JsonProjectionDocument, CliOutputFailure> =
            try {
                val tree = Json.parseToJsonElement(text)
                when (val shape = inspectProjectionTree(tree)) {
                    is Refinement.Refined -> Refinement.Refined(JsonProjectionDocument(text, tree, shape.value.depth))
                    is Refinement.Rejected -> shape
                }
            } catch (_: IllegalArgumentException) {
                Refinement.Rejected(CliOutputFailure.OUTPUT_ENCODING_REJECTED)
            }
    }
}

private data class ProjectionShape(val depth: Int)

private fun inspectProjectionTree(tree: JsonElement): Refinement<ProjectionShape, CliOutputFailure> {
    val pending = ArrayDeque<Pair<JsonElement, Int>>()
    pending.add(tree to 0)
    var depth = 0
    while (pending.isNotEmpty()) {
        val (value, level) = pending.removeLast()
        depth = maxOf(depth, level)
        when (value) {
            is JsonObject -> value.values.forEach { pending.add(it to level + 1) }
            is JsonArray -> value.forEach { pending.add(it to level + 1) }
            is JsonPrimitive ->
                if (!strictPrimitive(value)) return Refinement.Rejected(CliOutputFailure.OUTPUT_ENCODING_REJECTED)
        }
    }
    return Refinement.Refined(ProjectionShape(depth))
}

private fun strictPrimitive(value: JsonPrimitive): Boolean =
    value.isString || value.content in listOf("true", "false", "null") || jsonNumber.matches(value.content)

private val jsonNumber = Regex("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?")
