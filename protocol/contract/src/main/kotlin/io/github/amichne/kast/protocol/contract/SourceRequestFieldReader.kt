package io.github.amichne.kast.protocol.contract

import io.github.amichne.kast.kernel.Refinement
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

internal fun sourceObjectAt(value: JsonElement, path: SourceRequestPath, index: Int? = null): JsonObject =
    value as? JsonObject ?: rejectSourceField(path, SourceRequestRule.OBJECT_REQUIRED, index)

internal fun sourceFields(value: JsonObject, path: SourceRequestPath, allowed: Set<String>, index: Int? = null) {
    if (value.keys.any { it !in allowed }) rejectSourceField(path, SourceRequestRule.UNKNOWN_FIELD, index)
}

internal fun sourceRequired(value: JsonObject, key: String, path: SourceRequestPath, index: Int? = null): JsonElement =
    value[key] ?: rejectSourceField(path, SourceRequestRule.REQUIRED, index)

internal fun sourceString(
    value: JsonElement,
    path: SourceRequestPath,
    index: Int? = null,
    elementIndex: Int? = null,
): String =
    (value as? JsonPrimitive)?.takeIf { it.isString }?.content
        ?: rejectSourceField(path, SourceRequestRule.STRING_REQUIRED, index, elementIndex)

internal fun sourceArray(value: JsonElement, path: SourceRequestPath, index: Int? = null): JsonArray =
    value as? JsonArray ?: rejectSourceField(path, SourceRequestRule.ARRAY_REQUIRED, index)

internal fun sourceChoice(
    value: JsonObject,
    key: String,
    path: SourceRequestPath,
    rule: SourceRequestRule,
    allowed: List<String>,
    index: Int? = null,
): String {
    val text = sourceString(sourceRequired(value, key, path, index), path, index)
    if (text !in allowed) rejectSourceField(path, rule, index)
    return text
}

internal fun sourceChoices(
    value: JsonObject,
    key: String,
    path: SourceRequestPath,
    rule: SourceRequestRule,
    allowed: List<String>,
    index: Int,
) {
    val values = sourceArray(sourceRequired(value, key, path, index), path, index)
    if (values.isEmpty() || values.size > allowed.size || values.distinct().size != values.size)
        rejectSourceField(path, SourceRequestRule.NONEMPTY_UNIQUE_VALUES, index)
    values.forEachIndexed { elementIndex, item ->
        if (sourceString(item, path, index, elementIndex) !in allowed)
            rejectSourceField(path, rule, index, elementIndex)
    }
}

internal fun sourceNumber(
    value: JsonObject,
    key: String,
    path: SourceRequestPath,
    min: Long,
    max: Long,
    rule: SourceRequestRule,
) {
    val raw = sourceRequired(value, key, path)
    val primitive = raw as? JsonPrimitive ?: rejectSourceField(path, SourceRequestRule.INTEGER_REQUIRED)
    if (primitive.isString) rejectSourceField(path, SourceRequestRule.INTEGER_REQUIRED)
    val number = primitive.longOrNull ?: rejectSourceField(path, SourceRequestRule.INTEGER_REQUIRED)
    if (number !in min..max) rejectSourceField(path, rule)
}

internal fun rejectSourceField(
    path: SourceRequestPath,
    rule: SourceRequestRule,
    index: Int? = null,
    elementIndex: Int? = null,
): Nothing {
    val admittedIndex = index?.let(::sourceFilterIndex)
    val admittedElement = elementIndex?.let {
        when (val admitted = SourceValueIndex.parse(it)) {
            is Refinement.Refined -> admitted.value
            is Refinement.Rejected ->
                throw SourceRequestSerializationException(
                    SourceReadFailureDetail.RequestRejected(
                        SourceRequestField(path, admittedIndex),
                        admitted.failure,
                    )
                )
        }
    }
    throw SourceRequestSerializationException(
        SourceReadFailureDetail.RequestRejected(SourceRequestField(path, admittedIndex, admittedElement), rule)
    )
}

private fun sourceFilterIndex(index: Int): SourceFilterIndex =
    when (val admitted = SourceFilterIndex.parse(index)) {
        is Refinement.Refined -> admitted.value
        is Refinement.Rejected ->
            throw SourceRequestSerializationException(
                SourceReadFailureDetail.RequestRejected(SourceRequestField(SourceRequestPath.FILTERS), admitted.failure)
            )
    }
