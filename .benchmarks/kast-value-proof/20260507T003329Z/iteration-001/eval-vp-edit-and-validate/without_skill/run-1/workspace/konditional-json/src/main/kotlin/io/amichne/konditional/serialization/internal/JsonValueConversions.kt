@file:OptIn(KonditionalInternalApi::class)

package io.amichne.konditional.serialization.internal

import io.amichne.konditional.api.KonditionalInternalApi
import io.amichne.konditional.core.types.Konstrained
import io.amichne.konditional.serialization.SchemaValueCodec
import io.amichne.kontracts.dsl.jsonArray
import io.amichne.kontracts.dsl.jsonObject
import io.amichne.kontracts.dsl.jsonValue
import io.amichne.kontracts.schema.DoubleSchema
import io.amichne.kontracts.schema.IntSchema
import io.amichne.kontracts.value.JsonArray
import io.amichne.kontracts.value.JsonBoolean
import io.amichne.kontracts.value.JsonNull
import io.amichne.kontracts.value.JsonNumber
import io.amichne.kontracts.value.JsonObject
import io.amichne.kontracts.value.JsonString
import io.amichne.kontracts.value.JsonValue

@KonditionalInternalApi
internal fun Any?.toJsonValue(): JsonValue =
    when (this) {
        null -> JsonNull
        is Boolean -> jsonValue { boolean(this@toJsonValue) }
        is String -> jsonValue { string(this@toJsonValue) }
        is Int -> jsonValue { number(this@toJsonValue) }
        is Double -> jsonValue { number(this@toJsonValue) }
        is Enum<*> -> jsonValue { string(this@toJsonValue.name) }
        is Map<*, *> ->
            jsonObject {
                fields(
                    entries.associate { (rawKey, rawValue) ->
                        val key =
                            rawKey as? String
                                ?: error("JsonObject keys must be strings, got ${rawKey?.let { it::class.simpleName }}")
                        key to rawValue.toJsonValue()
                    },
                )
            }

        is Konstrained -> SchemaValueCodec.encodeKonstrained(this)
        is JsonValue -> this
        is List<*> ->
            jsonArray {
                elements(this@toJsonValue.map { it.toJsonValue() })
            }
        else -> throw IllegalArgumentException("Unsupported type for JSON conversion: ${this::class.simpleName}")
    }

@KonditionalInternalApi
internal fun JsonValue.toPrimitiveValue(): Any? =
    when (this) {
        is JsonNull -> null
        is JsonBoolean -> value
        is JsonString -> value
        is JsonNumber -> value
        is JsonObject ->
            schema?.let { s ->
                fields.mapValues { (k, v) ->
                    val fieldSchema = s.fields[k]?.schema
                    when {
                        v is JsonNumber && fieldSchema is IntSchema -> v.toInt()
                        v is JsonNumber && fieldSchema is DoubleSchema -> v.toDouble()
                        else -> v.toPrimitiveValue()
                    }
                }
            } ?: fields.mapValues { (_, v) -> v.toPrimitiveValue() }

        is JsonArray -> elements.map { it.toPrimitiveValue() }
    }
