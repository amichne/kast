package io.amichne.kontracts.value

import io.amichne.kontracts.schema.JsonSchema
import io.amichne.kontracts.schema.BooleanSchema
import io.amichne.kontracts.schema.ValidationResult

/**
 * JSON boolean value.
 */
@ConsistentCopyVisibility
data class JsonBoolean internal constructor(val value: Boolean) : JsonValue {
    override fun validate(schema: JsonSchema<*>): ValidationResult =
        if (schema is BooleanSchema) {
            ValidationResult.Valid
        } else {
            ValidationResult.Invalid("Expected ${schema}, but got Boolean")
        }

    override fun toString() = value.toString()
}
