package io.amichne.kontracts.value

import io.amichne.kontracts.schema.DoubleSchema
import io.amichne.kontracts.schema.IntSchema
import io.amichne.kontracts.schema.JsonSchema
import io.amichne.kontracts.schema.ValidationResult

/**
 * JSON number value (stored as Double for precision).
 */
@ConsistentCopyVisibility
data class JsonNumber internal constructor(val value: Double) : JsonValue {
    override fun validate(schema: JsonSchema<*>): ValidationResult {
        return when (schema) {
            is IntSchema -> {
                if (value % 1 != 0.0) return ValidationResult.Invalid("Expected integer value, but got $value")
                if (value < Int.MIN_VALUE || value > Int.MAX_VALUE) {
                    ValidationResult.Invalid("Value $value is out of Int range")
                } else if (schema.enum != null && !schema.enum.contains(value.toInt())) {
                    ValidationResult.Invalid("Value $value is not in enum ${schema.enum}")
                } else if (schema.minimum != null && value.toInt() < schema.minimum) {
                    ValidationResult.Invalid("Value $value is less than minimum ${schema.minimum}")
                } else if (schema.maximum != null && value.toInt() > schema.maximum) {
                    ValidationResult.Invalid("Value $value is greater than maximum ${schema.maximum}")
                } else {
                    ValidationResult.Valid
                }
            }
            is DoubleSchema -> {
                if (schema.enum != null && !schema.enum.contains(value)) {
                    ValidationResult.Invalid("Value $value is not in enum ${schema.enum}")
                } else if (schema.minimum != null && value < schema.minimum) {
                    ValidationResult.Invalid("Value $value is less than minimum ${schema.minimum}")
                } else if (schema.maximum != null && value > schema.maximum) {
                    ValidationResult.Invalid("Value $value is greater than maximum ${schema.maximum}")
                } else {
                    ValidationResult.Valid
                }
            }
            else -> ValidationResult.Invalid("Expected ${schema}, but got JsonNumber")
        }
    }

    fun toInt(): Int = value.toInt()
    fun toDouble(): Double = value

    override fun toString() = value.toString()
}
