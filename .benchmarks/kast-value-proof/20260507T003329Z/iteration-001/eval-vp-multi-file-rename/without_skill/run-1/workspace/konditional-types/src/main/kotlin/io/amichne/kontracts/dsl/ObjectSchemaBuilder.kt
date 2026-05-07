package io.amichne.kontracts.dsl

import io.amichne.kontracts.schema.FieldSchema
import io.amichne.kontracts.schema.ObjectSchema

@JsonSchemaBuilderDsl
class ObjectSchemaBuilder @PublishedApi internal constructor() : JsonSchemaBuilder<Map<String, Any?>> {
    var title: String? = null
    var description: String? = null
    var default: Map<String, Any?>? = null
    var nullable: Boolean = false
    var example: Map<String, Any?>? = null
    var deprecated: Boolean = false
    var required: Set<String>? = null
    private val fields = mutableMapOf<String, FieldSchema>()

    override fun build() = ObjectSchema(fields, title, description, default, nullable, example, deprecated, required)
}
