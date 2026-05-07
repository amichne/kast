package io.amichne.kontracts.value

import io.amichne.kontracts.schema.JsonSchema
import io.amichne.kontracts.schema.NullSchema
import io.amichne.kontracts.schema.ValidationResult

/**
 * JSON null value.
 */
object JsonNull : JsonValue {
    override fun validate(schema: JsonSchema<*>): ValidationResult =
        if (schema is NullSchema) {
            ValidationResult.Valid
        } else {
            ValidationResult.Invalid("Expected ${schema}, but got Null")
        }

    override fun toString() = "null"
}
