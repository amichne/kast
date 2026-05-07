package io.amichne.kontracts.schema

/**
 * Schema that allows any JSON value without constraints.
 */

@ConsistentCopyVisibility
data class AnySchema internal constructor(
    override val title: String? = null,
    override val description: String? = null,
    override val default: Any? = null,
    override val nullable: Boolean = false,
    override val example: Any? = null,
    override val deprecated: Boolean = false
) : JsonSchema<Any>() {
    override val type: Type = Type.OBJECT
    override fun toString() = "AnySchema"
}
