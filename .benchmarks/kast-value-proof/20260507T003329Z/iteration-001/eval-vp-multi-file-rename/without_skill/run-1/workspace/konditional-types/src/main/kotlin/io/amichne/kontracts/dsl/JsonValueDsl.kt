package io.amichne.kontracts.dsl

import io.amichne.kontracts.schema.JsonSchema
import io.amichne.kontracts.schema.ObjectSchema
import io.amichne.kontracts.value.JsonArray
import io.amichne.kontracts.value.JsonBoolean
import io.amichne.kontracts.value.JsonNull
import io.amichne.kontracts.value.JsonNumber
import io.amichne.kontracts.value.JsonObject
import io.amichne.kontracts.value.JsonString
import io.amichne.kontracts.value.JsonValue

@DslMarker
annotation class JsonValueDsl

@JsonValueDsl
object JsonValueScope {
    fun boolean(value: Boolean): JsonBoolean = JsonValue.from(value)

    fun string(value: String): JsonString = JsonValue.from(value)

    fun number(value: Int): JsonNumber = JsonValue.from(value)

    fun number(value: Double): JsonNumber = JsonValue.from(value)

    fun nullValue(): JsonNull = JsonNull

    fun obj(builder: JsonObjectBuilder.() -> Unit): JsonObject = jsonObject(builder)

    fun array(builder: JsonArrayBuilder.() -> Unit): JsonArray = jsonArray(builder)
}

fun jsonValue(builder: JsonValueScope.() -> JsonValue): JsonValue = JsonValueScope.builder()

@JsonValueDsl
class JsonObjectBuilder {
    var schema: ObjectSchema? = null
    private val fields: MutableMap<String, JsonValue> = linkedMapOf()

    fun field(name: String, value: JsonValue) {
        fields[name] = value
    }

    fun field(name: String, builder: JsonValueScope.() -> JsonValue) {
        fields[name] = JsonValueScope.builder()
    }

    fun fields(values: Map<String, JsonValue>) {
        fields.putAll(values)
    }

    internal fun build(): JsonObject = JsonValue.obj(fields.toMap(), schema)
}

fun jsonObject(builder: JsonObjectBuilder.() -> Unit): JsonObject =
    JsonObjectBuilder().apply(builder).build()

@JsonValueDsl
class JsonArrayBuilder {
    var elementSchema: JsonSchema<Any>? = null
    private val elements: MutableList<JsonValue> = mutableListOf()

    fun element(value: JsonValue) {
        elements += value
    }

    fun element(builder: JsonValueScope.() -> JsonValue) {
        elements += JsonValueScope.builder()
    }

    fun elements(values: List<JsonValue>) {
        elements += values
    }

    internal fun build(): JsonArray = JsonValue.array(elements.toList(), elementSchema)
}

fun jsonArray(builder: JsonArrayBuilder.() -> Unit): JsonArray =
    JsonArrayBuilder().apply(builder).build()
