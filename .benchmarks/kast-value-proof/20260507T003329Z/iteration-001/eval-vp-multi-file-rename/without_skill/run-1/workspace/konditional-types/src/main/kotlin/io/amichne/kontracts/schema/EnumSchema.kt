package io.amichne.kontracts.schema

import kotlin.reflect.KClass

/**
 * Schema for enum values.
 * @param E The enum type
 * @param enumClass The KClass of the enum for runtime type checking
 * @param values The allowed enum values
 */

@ConsistentCopyVisibility
data class EnumSchema<E : Enum<E>> internal constructor(
    val enumClass: KClass<E>,
    val values: List<E>,
    override val title: String? = null,
    override val description: String? = null,
    override val default: E? = null,
    override val nullable: Boolean = false,
    override val example: E? = null,
    override val deprecated: Boolean = false
) : JsonSchema<E>() {
    override val type: Type = Type.STRING
    override fun toString() = "EnumSchema(${enumClass.simpleName})"
}
