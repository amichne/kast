package io.github.amichne.kast.runtime.hosted

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import io.github.amichne.kast.kernel.Refinement

internal object HostedEndpointDescriptor {
    fun parse(raw: String): Refinement<JsonObject, HostedEndpointFailure> {
        JsonReader(java.io.StringReader(raw)).use { reader ->
            reader.isLenient = false
            return readDocument(reader)
        }
    }

    private fun readDocument(reader: JsonReader): Refinement<JsonObject, HostedEndpointFailure> {
        val result = JsonObject()
        val names = mutableSetOf<String>()
        reader.beginObject()
        while (reader.hasNext()) {
            val key = reader.nextName()
            if (!names.add(key)) return rejected()
            when (val value = field(key, reader)) {
                null -> Unit
                is Refinement.Refined -> result.add(key, value.value)
                is Refinement.Rejected -> return value
            }
        }
        reader.endObject()
        return if (reader.peek() == JsonToken.END_DOCUMENT) Refinement.Refined(result) else rejected()
    }

    private fun field(key: String, reader: JsonReader): Refinement<JsonElement, HostedEndpointFailure>? =
        when (key) {
            "hostPid" -> number(reader)
            "type",
            "root",
            "socket",
            "host" ->
                if (reader.peek() == JsonToken.STRING) Refinement.Refined(JsonPrimitive(reader.nextString()))
                else rejected()
            else -> {
                reader.skipValue()
                null
            }
        }

    private fun number(reader: JsonReader): Refinement<JsonElement, HostedEndpointFailure> {
        if (reader.peek() != JsonToken.NUMBER) return rejected()
        val number = reader.nextString()
        if (!number.matches(Regex("[1-9][0-9]{0,18}"))) return rejected()
        return Refinement.Refined(JsonPrimitive(number.toLong()))
    }

    private fun rejected() = Refinement.Rejected(HostedEndpointFailure.OWNERSHIP_CONFLICT)
}
