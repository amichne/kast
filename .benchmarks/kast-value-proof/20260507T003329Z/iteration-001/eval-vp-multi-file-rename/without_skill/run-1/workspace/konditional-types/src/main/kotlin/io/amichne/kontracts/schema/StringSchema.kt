package io.amichne.kontracts.schema

/**
 * Schema for string values.
 * Supports OpenAPI string constraints.
 */

@ConsistentCopyVisibility
data class StringSchema internal constructor(
    override val title: String? = null,
    override val description: String? = null,
    override val default: String? = null,
    override val nullable: Boolean = false,
    override val example: String? = null,
    override val deprecated: Boolean = false,
    val minLength: Int? = null,
    val maxLength: Int? = null,
    val pattern: String? = null,
    val format: String? = null,
    val enum: List<String>? = null
) : JsonSchema<String>() {
    override val type: Type = Type.STRING
    override fun toString() = "StringSchema"
}
