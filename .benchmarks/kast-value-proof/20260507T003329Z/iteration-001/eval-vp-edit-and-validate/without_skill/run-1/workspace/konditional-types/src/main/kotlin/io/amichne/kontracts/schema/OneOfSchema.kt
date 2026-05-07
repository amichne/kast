package io.amichne.kontracts.schema

/**
 * Schema representing a one-of union of multiple schemas.
 */

@ConsistentCopyVisibility
data class OneOfSchema internal constructor(
    val options: List<JsonSchema<*>>,
    val discriminator: Discriminator? = null,
    override val title: String? = null,
    override val description: String? = null,
    override val default: Any? = null,
    override val nullable: Boolean = false,
    override val example: Any? = null,
    override val deprecated: Boolean = false
) : JsonSchema<Any>() {
    override val type: Type
        get() = options.firstOrNull()?.type ?: Type.OBJECT

    override fun toString() = "OneOfSchema(options=${options.size})"

    /**
     * OpenAPI discriminator for polymorphic types.
     * @param propertyName The property used to discriminate (typically "type")
     * @param mapping Maps discriminator values to schema names (used in $ref)
     */
    @ConsistentCopyVisibility
    data class Discriminator internal constructor(
        val propertyName: String,
        val mapping: Map<String, String>
    )
}
