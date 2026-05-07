package io.amichne.konditional.internal.serialization.adapters

import com.squareup.moshi.JsonDataException
import com.squareup.moshi.JsonReader
import com.squareup.moshi.JsonWriter
import io.amichne.konditional.api.KonditionalInternalApi

@KonditionalInternalApi
internal fun serializeMap(
    writer: JsonWriter,
    map: Map<String, Any?>,
) {
    writer.beginObject()
    map.forEach { (key, value) ->
        writer.name(key)
        serializeValue(writer, value)
    }
    writer.endObject()
}

@KonditionalInternalApi
internal fun serializeValue(
    writer: JsonWriter,
    value: Any?,
) {
    when (value) {
        null -> writer.nullValue()
        is Boolean -> writer.value(value)
        is String -> writer.value(value)
        is Number -> writer.value(value)
        is Map<*, *> -> {
            @Suppress("UNCHECKED_CAST")
            serializeMap(writer, value as Map<String, Any?>)
        }
        is List<*> -> {
            writer.beginArray()
            value.forEach { serializeValue(writer, it) }
            writer.endArray()
        }
        else -> throw JsonDataException("Unsupported value type: ${value::class.simpleName}")
    }
}

@KonditionalInternalApi
internal fun deserializeMap(reader: JsonReader): Map<String, Any?> {
    val map = mutableMapOf<String, Any?>()
    reader.beginObject()
    while (reader.hasNext()) {
        val key = reader.nextName()
        val value = deserializeValue(reader)
        map[key] = value
    }
    reader.endObject()
    return map
}

@KonditionalInternalApi
internal fun deserializeValue(reader: JsonReader): Any? =
    when (reader.peek()) {
        JsonReader.Token.NULL -> {
            reader.nextNull<Any?>()
            null
        }
        JsonReader.Token.BOOLEAN -> reader.nextBoolean()
        JsonReader.Token.STRING -> reader.nextString()
        JsonReader.Token.NUMBER -> {
            val numStr = reader.nextString()
            if (numStr.contains('.')) numStr.toDouble() else numStr.toInt()
        }
        JsonReader.Token.BEGIN_OBJECT -> deserializeMap(reader)
        JsonReader.Token.BEGIN_ARRAY -> {
            val list = mutableListOf<Any?>()
            reader.beginArray()
            while (reader.hasNext()) {
                list.add(deserializeValue(reader))
            }
            reader.endArray()
            list
        }
        else -> {
            reader.skipValue()
            null
        }
    }
