package io.amichne.kontracts.schema

/**
 * Schema for integer values.
 * Supports OpenAPI numeric constraints.
 */

@ConsistentCopyVisibility
data class IntSchema internal constructor(
    override val title: String? = null,
    override val description: String? = null,
    override val default: Int? = null,
    override val nullable: Boolean = false,
    override val example: Int? = null,
    override val deprecated: Boolean = false,
    val minimum: Int? = null,
    val maximum: Int? = null,
    val enum: List<Int>? = null
) : JsonSchema<Int>() {
    override val type: Type = Type.INTEGER
    override fun toString() = "IntSchema"
}
