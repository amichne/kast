package io.amichne.konditional.serialization

import io.amichne.kontracts.dsl.objectSchema
import io.amichne.kontracts.schema.JsonSchema
import io.amichne.kontracts.schema.ObjectSchema
import io.amichne.kontracts.schema.RootObjectSchema
import kotlin.reflect.KClass
import kotlin.reflect.KProperty1
import kotlin.reflect.full.companionObject
import kotlin.reflect.full.companionObjectInstance
import kotlin.reflect.full.createInstance
import kotlin.reflect.full.memberProperties

/**
 * Extracts an [ObjectSchema] from a Kotlin class by reflecting an optional `schema` property.
 *
 * The lookup order is:
 * 1. Kotlin `object` instance
 * 2. No-arg/default constructor instance
 * 3. Companion object property
 */
internal fun extractSchema(kClass: KClass<*>): ObjectSchema? {
    val schemaFromObject = extractSchemaProperty(kClass.objectInstance)

    val schemaFromConstructor =
        runCatching { kClass.createInstance() }
            .getOrNull()
            ?.let(::extractSchemaProperty)

    val schemaFromCompanion =
        kClass.companionObjectInstance
            ?.let { instance ->
                kClass.companionObject
                    ?.memberProperties
                    ?.firstOrNull { it.name == "schema" }
                    ?.let { property -> readSchemaProperty(property, instance) }
            }

    return schemaFromObject ?: schemaFromConstructor ?: schemaFromCompanion
}

private fun extractSchemaProperty(instance: Any?): ObjectSchema? =
    instance
        ?.let { candidate ->
            candidate::class.memberProperties
                .firstOrNull { it.name == "schema" }
                ?.let { property -> readSchemaProperty(property, candidate) }
        }

private fun readSchemaProperty(
    property: KProperty1<out Any, *>,
    instance: Any,
): ObjectSchema? =
    runCatching { property.getter.call(instance) }
        .getOrNull()
        .asObjectSchemaOrNull()

internal fun JsonSchema<*>.asObjectSchema(): ObjectSchema =
    when (this) {
        is ObjectSchema -> this
        is RootObjectSchema ->
            objectSchema {
                fields = this@asObjectSchema.fields
                title = this@asObjectSchema.title
                description = this@asObjectSchema.description
                default = this@asObjectSchema.default
                nullable = this@asObjectSchema.nullable
                example = this@asObjectSchema.example
                deprecated = this@asObjectSchema.deprecated
                required = this@asObjectSchema.required
            }
        else -> throw IllegalArgumentException("Expected an object schema, got ${this::class.qualifiedName}")
    }

private fun Any?.asObjectSchemaOrNull(): ObjectSchema? =
    (this as? JsonSchema<*>)?.let { schema ->
        runCatching { schema.asObjectSchema() }.getOrNull()
    }
