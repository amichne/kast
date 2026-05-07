package io.amichne.kontracts.schema

/**
 * Schema for a single field in an object.
 * @param schema The schema for this field's value
 * @param required Whether this field is required (default: false)
 * @param defaultValue Optional default value if field is missing
 */

@ConsistentCopyVisibility
data class FieldSchema internal constructor(
    val schema: JsonSchema<*>,
    val required: Boolean = false,
    val defaultValue: Any? = null,
    val description: String? = null,
    val deprecated: Boolean = false
) {
    override fun toString() = "FieldSchema($schema, required=$required)"
}
