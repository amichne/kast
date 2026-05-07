package io.amichne.kontracts.value

import io.amichne.kontracts.schema.EnumSchema
import io.amichne.kontracts.schema.JsonSchema
import io.amichne.kontracts.schema.StringSchema
import io.amichne.kontracts.schema.ValidationResult

/**
 * JSON string value.
 */
@ConsistentCopyVisibility
data class JsonString internal constructor(val value: String) : JsonValue {
    override fun validate(schema: JsonSchema<*>): ValidationResult {
        return when (schema) {
            is StringSchema -> {
                if (schema.minLength != null && value.length < schema.minLength) {
                    return ValidationResult.Invalid(
                        "String length ${value.length} is less than minimum length ${schema.minLength}"
                    )
                }
                if (schema.maxLength != null && value.length > schema.maxLength) {
                    return ValidationResult.Invalid(
                        "String length ${value.length} is greater than maximum length ${schema.maxLength}"
                    )
                }
                if (schema.pattern != null && !value.matches(Regex(schema.pattern))) {
                    return ValidationResult.Invalid(
                        "String '$value' does not match pattern ${schema.pattern}"
                    )
                }
                ValidationResult.Valid
            }
            is EnumSchema<*> -> {
                // Validate that the string corresponds to a valid enum value
                val enumConstants = schema.enumClass.java.enumConstants
                if (enumConstants.any { it.name == value }) {
                    ValidationResult.Valid
                } else {
                    ValidationResult.Invalid(
                        "String '$value' is not a valid ${schema.enumClass.simpleName} value"
                    )
                }
            }
            else -> ValidationResult.Invalid(
                "Expected ${schema}, but got String"
            )
        }
    }

    override fun toString() = "\"$value\""
}
