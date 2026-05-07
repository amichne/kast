package io.amichne.kontracts.schema

@ConsistentCopyVisibility
data class RootObjectSchema internal constructor(
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
    override fun toString() = "RootObjectSchema(fields=${fields.keys})"
}
