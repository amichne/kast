package io.github.amichne.kast.standalone.telemetry

import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.trace.Span

internal fun applyAttributes(span: Span, attributes: Map<String, Any?>) {
    attributes.forEach { (key, value) ->
        if (value != null) {
            setAttribute(span, key, value)
        }
    }
}

internal fun attributesOf(attributes: Map<String, Any?>): Attributes {
    val builder = Attributes.builder()
    attributes.forEach { (key, value) ->
        when (value) {
            null -> Unit
            is Boolean -> builder.put(AttributeKey.booleanKey(key), value)
            is Double -> builder.put(AttributeKey.doubleKey(key), value)
            is Int -> builder.put(AttributeKey.longKey(key), value.toLong())
            is Long -> builder.put(AttributeKey.longKey(key), value)
            else -> builder.put(AttributeKey.stringKey(key), value.toString())
        }
    }
    return builder.build()
}

internal fun setAttribute(span: Span, key: String, value: Any) {
    when (value) {
        is Boolean -> span.setAttribute(AttributeKey.booleanKey(key), value)
        is Double -> span.setAttribute(AttributeKey.doubleKey(key), value)
        is Int -> span.setAttribute(AttributeKey.longKey(key), value.toLong())
        is Long -> span.setAttribute(AttributeKey.longKey(key), value)
        else -> span.setAttribute(AttributeKey.stringKey(key), value.toString())
    }
}

internal fun String?.isTruthy(): Boolean = when (this?.trim()?.lowercase()) {
    "1", "true", "yes", "on" -> true
    else -> false
}
