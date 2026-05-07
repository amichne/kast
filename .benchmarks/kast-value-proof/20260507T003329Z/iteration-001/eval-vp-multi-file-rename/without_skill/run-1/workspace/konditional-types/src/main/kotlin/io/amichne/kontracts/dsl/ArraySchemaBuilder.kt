package io.amichne.kontracts.dsl

import io.amichne.kontracts.schema.ArraySchema
import io.amichne.kontracts.schema.JsonSchema

@JsonSchemaBuilderDsl
class ArraySchemaBuilder<E : Any> @PublishedApi internal constructor() : JsonSchemaBuilder<List<E>> {
    var title: String? = null
    var description: String? = null
    var default: List<E>? = null
    var nullable: Boolean = false
    var example: List<E>? = null
    var deprecated: Boolean = false
    var minItems: Int? = null
    var maxItems: Int? = null
    var uniqueItems: Boolean = false
    lateinit var elementSchema: JsonSchema<E>
    fun element(builder: JsonSchemaBuilder<E>.() -> Unit) {
        elementSchema = ArraySchemaBuilder<E>().apply { element { builder() } }.build().elementSchema
    }

    override fun build(): ArraySchema<E> = ArraySchema(
        elementSchema,
        title,
        description,
        default,
        nullable,
        example,
        deprecated,
        minItems,
        maxItems,
        uniqueItems
    )
}

fun <E : Any> ArraySchemaBuilder<E>.elementSchema(schema: JsonSchema<E>) {
    this.elementSchema = schema
}
