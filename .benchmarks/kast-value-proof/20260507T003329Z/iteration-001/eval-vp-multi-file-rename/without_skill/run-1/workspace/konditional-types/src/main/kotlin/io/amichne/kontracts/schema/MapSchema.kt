package io.amichne.kontracts.schema

/**
 * Schema for JSON objects with arbitrary string keys and uniform value schema.
 */

@ConsistentCopyVisibility
data class MapSchema<V : Any> internal constructor(
    val valueSchema: JsonSchema<V>,
    override val title: String? = null,
    override val description: String? = null,
    override val default: Map<String, V>? = null,
    override val nullable: Boolean = false,
    override val example: Map<String, V>? = null,
    override val deprecated: Boolean = false,
    val minProperties: Int? = null,
    val maxProperties: Int? = null
) : JsonSchema<Map<String, V>>() {
    override val type: Type = Type.OBJECT
    override fun toString() = "MapSchema($valueSchema)"
}
