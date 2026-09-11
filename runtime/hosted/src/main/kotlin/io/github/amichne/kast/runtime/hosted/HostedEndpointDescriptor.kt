package io.github.amichne.kast.runtime.hosted

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import io.github.amichne.kast.kernel.Refinement

internal object HostedEndpointDescriptor {
    fun parse(raw: String): Refinement<JsonObject, HostedEndpointFailure> {
        val result = JsonObject()
        JsonReader(java.io.StringReader(raw)).use { reader ->
            reader.isLenient = false
            reader.beginObject()
            while (reader.hasNext()) {
                val key = reader.nextName()
                if (result.has(key)) return rejected()
                when (val field = field(key, reader)) {
                    is Refinement.Refined -> result.add(key, field.value)
                    is Refinement.Rejected -> return field
                }
            }
            reader.endObject()
            if (reader.peek() != JsonToken.END_DOCUMENT) return rejected()
        }
        return Refinement.Refined(result)
    }

    private fun field(key: String, reader: JsonReader): Refinement<JsonElement, HostedEndpointFailure> =
        when (key) {
            "protocol",
            "hostPid" -> number(reader)
            "type",
            "root",
            "socket",
            "host",
            "querySchema" ->
                if (reader.peek() == JsonToken.STRING) Refinement.Refined(JsonPrimitive(reader.nextString()))
                else rejected()
            "operations" -> operations(reader)
            else -> rejected()
        }

    private fun number(reader: JsonReader): Refinement<JsonElement, HostedEndpointFailure> {
        if (reader.peek() != JsonToken.NUMBER) return rejected()
        val number = reader.nextString()
        if (!number.matches(Regex("[1-9][0-9]{0,18}"))) return rejected()
        return Refinement.Refined(JsonPrimitive(number.toLong()))
    }

    private fun operations(reader: JsonReader): Refinement<JsonElement, HostedEndpointFailure> {
        val operations = JsonArray()
        reader.beginArray()
        while (reader.hasNext()) {
            if (reader.peek() != JsonToken.STRING || operations.size() >= MAX_ENDPOINT_OPERATIONS) return rejected()
            operations.add(reader.nextString())
        }
        reader.endArray()
        return Refinement.Refined(operations)
    }

    private fun rejected() = Refinement.Rejected(HostedEndpointFailure.OWNERSHIP_CONFLICT)
}

private const val MAX_ENDPOINT_OPERATIONS = 32
