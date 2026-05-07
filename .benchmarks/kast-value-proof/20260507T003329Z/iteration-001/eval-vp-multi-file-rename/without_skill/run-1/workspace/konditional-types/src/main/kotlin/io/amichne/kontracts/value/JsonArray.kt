package io.amichne.kontracts.value

import io.amichne.kontracts.schema.ArraySchema
import io.amichne.kontracts.schema.JsonSchema
import io.amichne.kontracts.schema.ValidationResult

/**
 * JSON array value with homogeneous elements.
 *
 * @param elements List of array elements (must all match elementSchema)
 * @param elementSchema Schema for array elements
 */
@ConsistentCopyVisibility
data class JsonArray internal constructor(
    val elements: List<JsonValue>,
    val elementSchema: JsonSchema<Any>? = null,
) : JsonValue {

    init {
        elementSchema?.let {
            with(validate(ArraySchema(it))) {
                if (isInvalid) throw IllegalArgumentException("JsonArray does not match schema: ${getErrorMessage()}")
            }
        }
    }

    override fun validate(schema: JsonSchema<*>): ValidationResult {
        if (schema !is ArraySchema<*>) {
            return ValidationResult.Invalid("Expected ${schema}, but got JsonArray")
        }

        elements.forEachIndexed { index, element ->
            with(element.validate(schema.elementSchema)) {
                if (isInvalid) return ValidationResult.Invalid("Element at index $index: ${getErrorMessage()}")
            }
        }

        return ValidationResult.Valid
    }

    /**
     * Gets an element by index.
     */
    operator fun get(index: Int): JsonValue? = elements.getOrNull(index)

    /**
     * Returns the number of elements.
     */
    val size: Int get() = elements.size

    /**
     * Checks if the array is empty.
     */
    fun isEmpty(): Boolean = elements.isEmpty()

    /**
     * Checks if the array is not empty.
     */
    fun isNotEmpty(): Boolean = elements.isNotEmpty()

    override fun toString(): String = elements.toString()
}
