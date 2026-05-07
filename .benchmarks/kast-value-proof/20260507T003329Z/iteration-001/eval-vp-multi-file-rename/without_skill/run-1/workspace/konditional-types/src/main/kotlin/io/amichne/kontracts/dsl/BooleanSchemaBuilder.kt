package io.amichne.kontracts.dsl

import io.amichne.kontracts.schema.BooleanSchema

@JsonSchemaBuilderDsl
open class BooleanSchemaBuilder @PublishedApi internal constructor() : JsonSchemaBuilder<Boolean> {
    var title: String? = null
    var description: String? = null
    var default: Boolean? = null
    var nullable: Boolean = false
    var example: Boolean? = null
    var deprecated: Boolean = false
    override fun build() = BooleanSchema(title, description, default, nullable, example, deprecated)
}
