package io.amichne.kontracts.dsl

import io.amichne.kontracts.schema.IntSchema

@JsonSchemaBuilderDsl
open class IntSchemaBuilder @PublishedApi internal constructor() : JsonSchemaBuilder<Int> {
    var title: String? = null
    var description: String? = null
    var default: Int? = null
    var nullable: Boolean = false
    var example: Int? = null
    var deprecated: Boolean = false
    var minimum: Int? = null
    var maximum: Int? = null
    var enum: List<Int>? = null
    override fun build() =
        IntSchema(title, description, default, nullable, example, deprecated, minimum, maximum, enum)
}
