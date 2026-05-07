package io.amichne.kontracts.schema

/**
 * Schema for boolean values.
 */

@ConsistentCopyVisibility
data class BooleanSchema internal constructor(
    override val title: String? = null,
    override val description: String? = null,
    override val default: Boolean? = null,
    override val nullable: Boolean = false,
    override val example: Boolean? = null,
    override val deprecated: Boolean = false
) : JsonSchema<Boolean>() {
    override val type: Type = Type.BOOLEAN
    override fun toString() = "BooleanSchema"
}
