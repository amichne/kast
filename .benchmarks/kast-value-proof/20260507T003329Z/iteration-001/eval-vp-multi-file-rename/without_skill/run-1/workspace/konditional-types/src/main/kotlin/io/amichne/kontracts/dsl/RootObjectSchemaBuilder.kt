package io.amichne.kontracts.dsl

import io.amichne.kontracts.schema.FieldSchema
import io.amichne.kontracts.schema.JsonSchema
import io.amichne.kontracts.schema.ObjectSchema

@JsonSchemaBuilderDsl
class RootObjectSchemaBuilder @PublishedApi internal constructor() {
    @PublishedApi
    internal val fields: MutableMap<String, FieldSchema> = mutableMapOf()

    @PublishedApi
    internal var schema: JsonSchema<*>? = null

    fun required(
        name: String,
        schema: JsonSchema<*>,
        description: String? = null,
        defaultValue: Any? = null,
        deprecated: Boolean = false
    ) {
        fields[name] = FieldSchema(
            schema,
            required = true,
            defaultValue = defaultValue,
            description = description,
            deprecated = deprecated
        )
    }

    fun optional(
        name: String,
        schema: JsonSchema<*>,
        description: String? = null,
        defaultValue: Any? = null,
        deprecated: Boolean = false
    ) {
        fields[name] = FieldSchema(
            schema,
            required = false,
            defaultValue = defaultValue,
            description = description,
            deprecated = deprecated
        )
    }

    fun build(): ObjectSchema = when (val builtSchema = schema) {
        is ObjectSchema -> builtSchema
        null -> ObjectSchema(fields.toMap())
        else -> throw IllegalStateException("Top-level schema must be an ObjectSchema")
    }
}
