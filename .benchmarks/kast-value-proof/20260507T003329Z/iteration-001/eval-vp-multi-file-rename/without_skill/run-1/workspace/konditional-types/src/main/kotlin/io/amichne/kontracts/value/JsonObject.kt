package io.amichne.kontracts.value

import io.amichne.kontracts.schema.JsonSchema
import io.amichne.kontracts.schema.ObjectSchema
import io.amichne.kontracts.schema.ValidationResult
import io.amichne.kontracts.schema.ValidationResult.Invalid

/**
 * JSON object value with typed fields.
 *
 * @param fields Map of field names to their values
 * @param schema Optional schema for validation
 */
@ConsistentCopyVisibility
data class JsonObject internal constructor(
    val fields: Map<String, JsonValue>,
    val schema: ObjectSchema? = null,
) : JsonValue {

    init {
        schema?.let { s ->
            val result = validate(s)
            if (result.isInvalid) {
                throw IllegalArgumentException(
                    "JsonObject does not match schema: ${result.getErrorMessage()}"
                )
            }
        }
    }

    override fun validate(schema: JsonSchema<*>): ValidationResult {
        if (schema !is ObjectSchema) {
            return Invalid("Expected ${schema}, but got JsonObject")
        }

        val req = schema.required ?: schema.fields.filter { it.value.required }.keys
        val missing = req - fields.keys
        val requiredValidation = if (missing.isEmpty()) {
            ValidationResult.Valid
        } else {
            Invalid("Missing required fields: $missing")
        }
        if (requiredValidation.isInvalid) {
            return requiredValidation
        }
        for ((key, value) in fields) {
            val fieldSchema = schema.fields[key] ?: return Invalid("Unknown field '$key' in object")

            val fieldValidation = value.validate(fieldSchema.schema)
            if (fieldValidation.isInvalid) {
                return Invalid(
                    "Field '$key': ${fieldValidation.getErrorMessage()}"
                )
            }
        }

        return ValidationResult.Valid
    }

    /**
     * Gets a field value by name.
     */
    operator fun get(key: String): JsonValue? = fields[key]

    /**
     * Gets a typed value from a field.
     */
    inline fun <reified T> getTyped(key: String): T? {
        return when (val value = fields[key]) {
            is JsonBoolean -> if (T::class == Boolean::class) value.value as T else null
            is JsonString -> if (T::class == String::class) value.value as T else null
            is JsonNumber -> when (T::class) {
                Int::class -> value.toInt() as T
                Double::class -> value.toDouble() as T
                else -> null
            }
            is JsonObject -> if (T::class == JsonObject::class) value as T else null
            is JsonArray -> if (T::class == JsonArray::class) value as T else null
            is JsonNull -> null
            null -> null
        }
    }

    override fun toString(): String {
        val fieldsStr = fields.entries.joinToString(", ") { (k, v) -> "\"$k\": $v" }
        return "{$fieldsStr}"
    }
}
