package io.github.amichne.kast.distribution.output

import dev.toonformat.jtoon.JToon
import io.github.amichne.kast.distribution.contract.configuration.CliOutputFailure
import io.github.amichne.kast.distribution.contract.configuration.CliOutputFormat
import io.github.amichne.kast.kernel.Refinement
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

@JvmInline value class LosslessCliOutput internal constructor(val text: String)

/** User projections are dynamic JSON; TOON must preserve their values, including exact decimal numbers. */
fun renderLosslessCliOutput(
    document: String,
    format: CliOutputFormat,
): Refinement<LosslessCliOutput, CliOutputFailure> =
    when (val admitted = JsonProjectionDocument.parse(document)) {
        is Refinement.Rejected -> admitted
        is Refinement.Refined -> renderAdmittedProjection(admitted.value, format)
    }

private fun renderAdmittedProjection(
    document: JsonProjectionDocument,
    format: CliOutputFormat,
): Refinement<LosslessCliOutput, CliOutputFailure> {
    if (format == CliOutputFormat.JSON) return Refinement.Refined(LosslessCliOutput(document.text))
    if (document.depth > MAX_TOON_PROJECTION_DEPTH)
        return Refinement.Rejected(CliOutputFailure.OUTPUT_ENCODING_REJECTED)
    return renderToonProjection(document)
}

private fun renderToonProjection(document: JsonProjectionDocument): Refinement<LosslessCliOutput, CliOutputFailure> =
    try {
        val encoded = JToon.encodeJson(document.text)
        when (val decoded = JsonProjectionDocument.parse(JToon.decodeToJson(encoded))) {
            is Refinement.Rejected -> decoded
            is Refinement.Refined ->
                if (sameJsonValues(document.tree, decoded.value.tree)) Refinement.Refined(LosslessCliOutput(encoded))
                else Refinement.Rejected(CliOutputFailure.OUTPUT_VALUE_LOSS)
        }
    } catch (_: IllegalArgumentException) {
        Refinement.Rejected(CliOutputFailure.OUTPUT_ENCODING_REJECTED)
    }

private const val MAX_TOON_PROJECTION_DEPTH = 128

/** Iteration avoids making projection depth a second recursive call-stack limit. */
internal fun sameJsonValues(original: JsonElement, decoded: JsonElement): Boolean {
    val pending = ArrayDeque<Pair<JsonElement, JsonElement>>()
    pending.add(original to decoded)
    while (pending.isNotEmpty()) {
        val (left, right) = pending.removeLast()
        if (!enqueueMatchingValues(left, right, pending)) return false
    }
    return true
}

private fun enqueueMatchingValues(
    left: JsonElement,
    right: JsonElement,
    pending: ArrayDeque<Pair<JsonElement, JsonElement>>,
): Boolean =
    when (left) {
        is JsonObject ->
            if (right is JsonObject && left.keys == right.keys) {
                left.forEach { (key, value) -> pending.add(value to right.getValue(key)) }
                true
            } else false
        is JsonArray ->
            if (right is JsonArray && left.size == right.size) {
                left.indices.forEach { pending.add(left[it] to right[it]) }
                true
            } else false
        is JsonPrimitive -> right is JsonPrimitive && samePrimitive(left, right)
    }

private fun samePrimitive(left: JsonPrimitive, right: JsonPrimitive): Boolean =
    when {
        left.isString || right.isString -> left.isString == right.isString && left.content == right.content
        left == JsonNull || right == JsonNull -> left == right
        left.content in listOf("true", "false") || right.content in listOf("true", "false") -> left == right
        else -> sameNumber(left.content, right.content)
    }

private fun sameNumber(left: String, right: String): Boolean {
    val number = left.toBigDecimalOrNull() ?: return false
    val other = right.toBigDecimalOrNull() ?: return false
    if (number.compareTo(other) != 0) return false
    return number.signum() != 0 || left.startsWith('-') == right.startsWith('-')
}
