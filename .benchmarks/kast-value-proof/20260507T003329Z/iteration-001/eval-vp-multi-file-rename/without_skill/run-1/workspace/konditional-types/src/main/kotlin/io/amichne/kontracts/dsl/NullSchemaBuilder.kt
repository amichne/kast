package io.amichne.kontracts.dsl

import io.amichne.kontracts.schema.NullSchema

@JsonSchemaBuilderDsl
class NullSchemaBuilder @PublishedApi internal constructor() : JsonSchemaBuilder<Any> {
    var title: String? = null
    var description: String? = null
    var default: Any? = null
    var example: Any? = null
    var deprecated: Boolean = false
    override fun build() = NullSchema(title, description, default, true, example, deprecated)
}
