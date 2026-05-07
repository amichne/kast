@file:OptIn(KonditionalInternalApi::class)

package io.amichne.konditional.internal.serialization.adapters

import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.JsonDataException
import com.squareup.moshi.JsonReader
import com.squareup.moshi.JsonWriter
import com.squareup.moshi.Moshi
import io.amichne.konditional.api.KonditionalInternalApi
import io.amichne.konditional.internal.serialization.models.FlagValue
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type

/**
 * Custom Moshi adapter for the [FlagValue] sealed class.
 *
 * Serializes [FlagValue] subclasses with a `type` discriminator field for type-safe
 * round-trip deserialization. Parse-don't-validate: deserialization constructs typed
 * domain objects at the boundary.
 *
 * Supported discriminator values:
 * - `"BOOLEAN"` → [FlagValue.BooleanValue]
 * - `"STRING"` → [FlagValue.StringValue]
 * - `"INT"` → [FlagValue.IntValue]
 * - `"DOUBLE"` → [FlagValue.DoubleValue]
 * - `"ENUM"` → [FlagValue.EnumValue]
 * - `"DATA_CLASS"` → [FlagValue.DataClassValue]
 * - `"KONSTRAINED_PRIMITIVE"` → [FlagValue.KonstrainedPrimitive]
 */
@KonditionalInternalApi
internal class FlagValueAdapter : JsonAdapter<FlagValue<*>>() {
    override fun toJson(
        writer: JsonWriter,
        value: FlagValue<*>?,
    ) {
        if (value == null) {
            writer.nullValue()
            return
        }

        writer.beginObject()
        when (value) {
            is FlagValue.BooleanValue -> {
                writer.name("type").value("BOOLEAN")
                writer.name("value").value(value.value)
            }
            is FlagValue.StringValue -> {
                writer.name("type").value("STRING")
                writer.name("value").value(value.value)
            }
            is FlagValue.IntValue -> {
                writer.name("type").value("INT")
                writer.name("value").value(value.value)
            }
            is FlagValue.DoubleValue -> {
                writer.name("type").value("DOUBLE")
                writer.name("value").value(value.value)
            }
            is FlagValue.EnumValue -> {
                writer.name("type").value("ENUM")
                writer.name("value").value(value.value)
                writer.name("enumClassName").value(value.enumClassName)
            }
            is FlagValue.DataClassValue -> {
                writer.name("type").value("DATA_CLASS")
                writer.name("dataClassName").value(value.dataClassName)
                writer.name("value")
                serializeMap(writer, value.value)
            }
            is FlagValue.KonstrainedPrimitive -> {
                writer.name("type").value("KONSTRAINED_PRIMITIVE")
                writer.name("konstrainedClassName").value(value.konstrainedClassName)
                writer.name("value")
                serializeValue(writer, value.value)
            }
        }
        writer.endObject()
    }

    override fun fromJson(reader: JsonReader): FlagValue<*> =
        readFlagValueParts(reader).let { parsed ->
            // Parse at the boundary: construct typed domain objects with validation
            when (val type = parsed.type) {
                "BOOLEAN" -> FlagValue.BooleanValue(requireBoolean(parsed.value, type = type))
                "STRING" -> FlagValue.StringValue(requireString(parsed.value, type = type))
                "INT" -> FlagValue.IntValue(requireInt(parsed.value, type = type))
                "DOUBLE" -> FlagValue.DoubleValue(requireDouble(parsed.value, type = type))
                "ENUM" ->
                    FlagValue.EnumValue(
                        value = requireString(parsed.value, type = type, hint = "enum name"),
                        enumClassName = requireString(parsed.enumClassName, type = type, hint = "enumClassName field"),
                    )
                "DATA_CLASS" ->
                    FlagValue.DataClassValue(
                        value = requireMap(parsed.value, type = type),
                        dataClassName = requireString(parsed.dataClassName, type = type, hint = "dataClassName field"),
                    )
                "KONSTRAINED_PRIMITIVE" ->
                    FlagValue.KonstrainedPrimitive(
                        value = parsed.value
                            ?: invalid("KONSTRAINED_PRIMITIVE type requires a value"),
                        konstrainedClassName = requireString(
                            parsed.konstrainedClassName,
                            type = type,
                            hint = "konstrainedClassName field",
                        ),
                    )
                null -> invalid("Missing required 'type' field")
                else -> invalid("Unknown FlagValue type: $type")
            }
        }
}

@KonditionalInternalApi
internal object FlagValueAdapterFactory : JsonAdapter.Factory {
    private val flagValueAdapter = FlagValueAdapter()

    override fun create(
        type: Type,
        annotations: Set<Annotation?>,
        moshi: Moshi,
    ): JsonAdapter<*>? = flagValueAdapter.takeIf { getRawType(type) == FlagValue::class.java }

    private fun getRawType(type: Type): Class<*> = when (type) {
        is Class<*> -> type
        is ParameterizedType -> getRawType(type.rawType)
        else -> Any::class.java
    }
}

private data class FlagValueParts(
    val type: String?,
    val value: Any?,
    val enumClassName: String?,
    val dataClassName: String?,
    val konstrainedClassName: String?,
)

private fun readFlagValueParts(reader: JsonReader): FlagValueParts {
    var type: String? = null
    var value: Any? = null
    var enumClassName: String? = null
    var dataClassName: String? = null
    var konstrainedClassName: String? = null

    reader.beginObject()
    while (reader.hasNext()) {
        when (reader.nextName()) {
            "type" -> type = reader.nextString()
            "value" -> value = deserializeValue(reader)
            "enumClassName" -> enumClassName = reader.nextString()
            "dataClassName" -> dataClassName = reader.nextString()
            "konstrainedClassName" -> konstrainedClassName = reader.nextString()
            else -> reader.skipValue()
        }
    }
    reader.endObject()

    return FlagValueParts(
        type = type,
        value = value,
        enumClassName = enumClassName,
        dataClassName = dataClassName,
        konstrainedClassName = konstrainedClassName,
    )
}

private fun requireBoolean(value: Any?, type: String): Boolean =
    value as? Boolean ?: invalid("$type type requires boolean value")

private fun requireString(value: Any?, type: String, hint: String = "string value"): String =
    value as? String ?: invalid("$type type requires $hint")

private fun requireInt(value: Any?, type: String): Int =
    when (value) {
        is Int -> value
        is Double ->
            value
                .takeIf { it.isFinite() && it % 1.0 == 0.0 }
                ?.toInt()
                ?: invalid("$type type requires int value")
        is String ->
            value
                .toIntOrNull()
                ?: invalid("$type type requires int value")
        else -> invalid("$type type requires int value")
    }

private fun requireDouble(value: Any?, type: String): Double =
    when (value) {
        is Double -> value
        is Int -> value.toDouble()
        is String ->
            value
                .toDoubleOrNull()
                ?: invalid("$type type requires double value")
        else -> invalid("$type type requires double value")
    }

private fun requireMap(value: Any?, type: String): Map<String, Any?> {
    val map = value as? Map<*, *> ?: invalid("$type type requires object value")
    if (map.keys.any { it !is String }) invalid("$type type requires string keys")
    return map.entries.associate { (key, entryValue) ->
        key as String to entryValue
    }
}

private fun invalid(message: String): Nothing = throw JsonDataException(message)
