package io.amichne.kontracts.schema

/**
 * Schema reference pointing to a component schema path.
 */

@ConsistentCopyVisibility
data class RefSchema internal constructor(
    val ref: String,
) : JsonSchema<Any>() {
    override val type: Type = Type.OBJECT
}
