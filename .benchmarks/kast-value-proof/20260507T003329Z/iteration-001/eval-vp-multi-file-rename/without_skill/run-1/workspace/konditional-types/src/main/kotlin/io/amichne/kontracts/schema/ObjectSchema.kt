package io.amichne.kontracts.schema

/**
 * Schema for JSON objects with typed fields.
 * @param fields Map of field names to their schemas
 */

@ConsistentCopyVisibility
data class ObjectSchema internal constructor(
    override val fields: Map<String, FieldSchema>,
    override val title: String? = null,
    override val description: String? = null,
    override val default: Map<String, Any?>? = null,
    override val nullable: Boolean = false,
    override val example: Map<String, Any?>? = null,
    override val deprecated: Boolean = false,
    override val required: Set<String>? = null
) : JsonSchema<Map<String, Any?>>(), ObjectTraits {
    override val type: Type = Type.OBJECT
    override fun toString() = "ObjectSchema(fields=${fields.keys})"
}
