package io.amichne.kontracts.value

import io.amichne.kontracts.schema.JsonSchema
import io.amichne.kontracts.schema.ObjectSchema
import io.amichne.kontracts.schema.ValidationResult

/**
 * Sealed class representing runtime JSON values.
 *
 * JsonValue provides a type-safe representation of JSON data that can be validated
 * against JsonSchema definitions. All JsonValue instances are immutable.
 *
 * Supported value types:
 * - Primitives: Boolean, String, Number, Null
 * - Objects: Structured key-value pairs
 * - Arrays: Lists of homogeneous values
 */
sealed interface JsonValue {

    /**
     * Validates this value against a schema.
     */
    fun validate(schema: JsonSchema<*>): ValidationResult

    companion object {
        /**
         * Creates a JsonBoolean from a Boolean.
         */
        internal fun from(value: Boolean): JsonBoolean = JsonBoolean(value)

        /**
         * Creates a JsonString from a String.
         */
        internal fun from(value: String): JsonString = JsonString(value)

        /**
         * Creates a JsonNumber from an Int.
         */
        internal fun from(value: Int): JsonNumber = JsonNumber(value.toDouble())

        /**
         * Creates a JsonNumber from a Double.
         */
        internal fun from(value: Double): JsonNumber = JsonNumber(value)

        /**
         * Creates a JsonObject from a map.
         */
        internal fun obj(
            fields: Map<String, JsonValue>,
            schema: ObjectSchema? = null
        ): JsonObject = JsonObject(fields, schema)

        /**
         * Creates a JsonArray from a list.
         */
        internal fun array(
            elements: List<JsonValue>,
            elementSchema: JsonSchema<Any>? = null
        ): JsonArray = JsonArray(elements, elementSchema)
    }
}
