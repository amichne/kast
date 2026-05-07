package io.amichne.kontracts.dsl

import io.amichne.kontracts.schema.FieldSchema
import io.amichne.kontracts.schema.JsonSchema
import io.amichne.kontracts.schema.MapSchema
import io.amichne.kontracts.schema.ObjectSchema
import io.amichne.kontracts.schema.OneOfSchema

@JsonSchemaBuilderDsl
class FieldSchemaFactoryBuilder {
    lateinit var schema: JsonSchema<*>
    var required: Boolean = false
    var defaultValue: Any? = null
    var description: String? = null
    var deprecated: Boolean = false

    internal fun build(): FieldSchema =
        FieldSchema(
            schema = schema,
            required = required,
            defaultValue = defaultValue,
            description = description,
            deprecated = deprecated,
        )
}

fun fieldSchema(builder: FieldSchemaFactoryBuilder.() -> Unit): FieldSchema =
    FieldSchemaFactoryBuilder().apply(builder).build()

@JsonSchemaBuilderDsl
class ObjectSchemaFactoryBuilder {
    var fields: Map<String, FieldSchema> = emptyMap()
    var title: String? = null
    var description: String? = null
    var default: Map<String, Any?>? = null
    var nullable: Boolean = false
    var example: Map<String, Any?>? = null
    var deprecated: Boolean = false
    var required: Set<String>? = null

    internal fun build(): ObjectSchema =
        JsonSchema.obj(
            fields = fields,
            title = title,
            description = description,
            default = default,
            nullable = nullable,
            example = example,
            deprecated = deprecated,
            required = required,
        )
}

fun objectSchema(builder: ObjectSchemaFactoryBuilder.() -> Unit): ObjectSchema =
    ObjectSchemaFactoryBuilder().apply(builder).build()

@JsonSchemaBuilderDsl
class MapSchemaFactoryBuilder<V : Any> {
    lateinit var valueSchema: JsonSchema<V>
    var title: String? = null
    var description: String? = null
    var default: Map<String, V>? = null
    var nullable: Boolean = false
    var example: Map<String, V>? = null
    var deprecated: Boolean = false
    var minProperties: Int? = null
    var maxProperties: Int? = null

    internal fun build(): MapSchema<V> =
        JsonSchema.map(
            valueSchema = valueSchema,
            title = title,
            description = description,
            default = default,
            nullable = nullable,
            example = example,
            deprecated = deprecated,
            minProperties = minProperties,
            maxProperties = maxProperties,
        )
}

fun <V : Any> mapSchema(builder: MapSchemaFactoryBuilder<V>.() -> Unit): MapSchema<V> =
    MapSchemaFactoryBuilder<V>().apply(builder).build()

@JsonSchemaBuilderDsl
class OneOfDiscriminatorBuilder {
    lateinit var propertyName: String
    var mapping: Map<String, String> = emptyMap()

    internal fun build(): OneOfSchema.Discriminator =
        OneOfSchema.Discriminator(
            propertyName = propertyName,
            mapping = mapping,
        )
}

@JsonSchemaBuilderDsl
class OneOfSchemaFactoryBuilder {
    var options: List<JsonSchema<*>> = emptyList()
    var title: String? = null
    var description: String? = null
    var default: Any? = null
    var nullable: Boolean = false
    var example: Any? = null
    var deprecated: Boolean = false
    private var discriminatorValue: OneOfSchema.Discriminator? = null

    fun discriminator(builder: OneOfDiscriminatorBuilder.() -> Unit) {
        discriminatorValue = OneOfDiscriminatorBuilder().apply(builder).build()
    }

    internal fun build(): OneOfSchema =
        JsonSchema.oneOf(
            options = options,
            discriminator = discriminatorValue,
            title = title,
            description = description,
            default = default,
            nullable = nullable,
            example = example,
            deprecated = deprecated,
        )
}

fun oneOfSchema(builder: OneOfSchemaFactoryBuilder.() -> Unit): OneOfSchema =
    OneOfSchemaFactoryBuilder().apply(builder).build()
