package io.amichne.kontracts.dsl

import io.amichne.kontracts.schema.DoubleSchema

@JsonSchemaBuilderDsl
open class DoubleSchemaBuilder @PublishedApi internal constructor() : JsonSchemaBuilder<Double> {
    var title: String? = null
    var description: String? = null
    var default: Double? = null
    var nullable: Boolean = false
    var example: Double? = null
    var deprecated: Boolean = false
    var minimum: Double? = null
    var maximum: Double? = null
    var enum: List<Double>? = null
    var format: String? = null
    override fun build() = DoubleSchema(
        title,
        description,
        default,
        nullable,
        example,
        deprecated,
        minimum,
        maximum,
        enum,
        format
    )
}
