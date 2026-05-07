package io.amichne.kontracts.schema

/**
 * Schema for null values.
 */

@ConsistentCopyVisibility
data class NullSchema internal constructor(
    override val title: String? = null,
    override val description: String? = null,
    override val default: Any? = null,
    override val nullable: Boolean = true,
    override val example: Any? = null,
    override val deprecated: Boolean = false
) : JsonSchema<Any>() {
    override val type: Type = Type.NULL
    override fun toString() = "NullSchema"
}
