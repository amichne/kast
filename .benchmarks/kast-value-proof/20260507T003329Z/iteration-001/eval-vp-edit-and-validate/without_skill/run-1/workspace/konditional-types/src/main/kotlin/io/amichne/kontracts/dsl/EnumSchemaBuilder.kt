package io.amichne.kontracts.dsl

import io.amichne.kontracts.schema.EnumSchema
import kotlin.reflect.KClass

@JsonSchemaBuilderDsl
class EnumSchemaBuilder<E : Enum<E>> @PublishedApi internal constructor(private val enumClass: KClass<E>) :
    JsonSchemaBuilder<E> {
    var title: String? = null
    var description: String? = null
    var default: E? = null
    var nullable: Boolean = false
    var example: E? = null
    var deprecated: Boolean = false
    var values: List<E> = enumClass.java.enumConstants.toList()
    override fun build() = EnumSchema(enumClass, values, title, description, default, nullable, example, deprecated)
}
