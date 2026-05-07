package io.amichne.kontracts.dsl

import io.amichne.kontracts.schema.StringSchema

@JsonSchemaBuilderDsl
open class StringSchemaBuilder @PublishedApi internal constructor() : JsonSchemaBuilder<String> {
    var title: String? = null
    var description: String? = null
    var default: String? = null
    var nullable: Boolean = false
    var example: String? = null
    var deprecated: Boolean = false
    var minLength: Int? = null
    var maxLength: Int? = null
    var pattern: String? = null
    var format: String? = null
    var enum: List<String>? = null
    override fun build() = StringSchema(
        title,
        description,
        default,
        nullable,
        example,
        deprecated,
        minLength,
        maxLength,
        pattern,
        format,
        enum
    )
}
