package io.amichne.kontracts.schema

/**
 * Schema for homogeneous arrays.
 * @param elementSchema The schema for all elements in the array
 */

@ConsistentCopyVisibility
data class ArraySchema<E : Any> internal constructor(
    val elementSchema: JsonSchema<E>,
    override val title: String? = null,
    override val description: String? = null,
    override val default: List<E>? = null,
    override val nullable: Boolean = false,
    override val example: List<E>? = null,
    override val deprecated: Boolean = false,
    val minItems: Int? = null,
    val maxItems: Int? = null,
    val uniqueItems: Boolean = false
) : JsonSchema<List<E>>() {
    override val type: Type = Type.ARRAY
    override fun toString() = "ArraySchema($elementSchema)"
}
