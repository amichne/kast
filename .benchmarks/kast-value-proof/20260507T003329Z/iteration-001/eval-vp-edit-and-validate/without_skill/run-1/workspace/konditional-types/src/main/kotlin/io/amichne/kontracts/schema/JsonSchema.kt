package io.amichne.kontracts.schema

/**
 * Sealed class representing compile-time schema definitions for JSON values, with OpenAPI-esque properties.
 */
sealed class JsonSchema<out T : Any> : OpenApi<T> {
    abstract override val type: Type
    override val title: String? = null
    override val description: String? = null
    override val default: T? = null
    override val nullable: Boolean = false
    override val example: T? = null
    override val deprecated: Boolean = false

    internal companion object {
        fun boolean(
            title: String? = null,
            description: String? = null,
            default: Boolean? = null,
            nullable: Boolean = false,
            example: Boolean? = null,
            deprecated: Boolean = false
        ): BooleanSchema = BooleanSchema(title, description, default, nullable, example, deprecated)

        fun string(
            title: String? = null,
            description: String? = null,
            default: String? = null,
            nullable: Boolean = false,
            example: String? = null,
            deprecated: Boolean = false,
            minLength: Int? = null,
            maxLength: Int? = null,
            pattern: String? = null,
            format: String? = null,
            enum: List<String>? = null
        ): StringSchema = StringSchema(
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

        fun int(
            title: String? = null,
            description: String? = null,
            default: Int? = null,
            nullable: Boolean = false,
            example: Int? = null,
            deprecated: Boolean = false,
            minimum: Int? = null,
            maximum: Int? = null,
            enum: List<Int>? = null
        ) = IntSchema(title, description, default, nullable, example, deprecated, minimum, maximum, enum)

        fun double(
            title: String? = null,
            description: String? = null,
            default: Double? = null,
            nullable: Boolean = false,
            example: Double? = null,
            deprecated: Boolean = false,
            minimum: Double? = null,
            maximum: Double? = null,
            enum: List<Double>? = null,
            format: String? = null
        ) = DoubleSchema(title, description, default, nullable, example, deprecated, minimum, maximum, enum, format)

        inline fun <reified E : Enum<E>> enum(
            values: List<E>,
            title: String? = null,
            description: String? = null,
            default: E? = null,
            nullable: Boolean = false,
            example: E? = null,
            deprecated: Boolean = false
        ) = EnumSchema(E::class, values, title, description, default, nullable, example, deprecated)

        fun nullSchema(
            title: String? = null,
            description: String? = null,
            default: Any? = null,
            example: Any? = null,
            deprecated: Boolean = false
        ) = NullSchema(title, description, default, true, example, deprecated)

        fun <E : Any> array(
            elementSchema: JsonSchema<E>,
            title: String? = null,
            description: String? = null,
            default: List<E>? = null,
            nullable: Boolean = false,
            example: List<E>? = null,
            deprecated: Boolean = false,
            minItems: Int? = null,
            maxItems: Int? = null,
            uniqueItems: Boolean = false
        ) = ArraySchema(
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

        fun <V : Any> map(
            valueSchema: JsonSchema<V>,
            title: String? = null,
            description: String? = null,
            default: Map<String, V>? = null,
            nullable: Boolean = false,
            example: Map<String, V>? = null,
            deprecated: Boolean = false,
            minProperties: Int? = null,
            maxProperties: Int? = null
        ) = MapSchema(
            valueSchema,
            title,
            description,
            default,
            nullable,
            example,
            deprecated,
            minProperties,
            maxProperties
        )

        fun oneOf(
            options: List<JsonSchema<*>>,
            discriminator: OneOfSchema.Discriminator? = null,
            title: String? = null,
            description: String? = null,
            default: Any? = null,
            nullable: Boolean = false,
            example: Any? = null,
            deprecated: Boolean = false
        ) = OneOfSchema(options, discriminator, title, description, default, nullable, example, deprecated)

        fun any(
            title: String? = null,
            description: String? = null,
            default: Any? = null,
            nullable: Boolean = false,
            example: Any? = null,
            deprecated: Boolean = false
        ) = AnySchema(title, description, default, nullable, example, deprecated)

        fun obj(
            fields: Map<String, FieldSchema>,
            title: String? = null,
            description: String? = null,
            default: Map<String, Any?>? = null,
            nullable: Boolean = false,
            example: Map<String, Any?>? = null,
            deprecated: Boolean = false,
            required: Set<String>? = null
        ) = ObjectSchema(fields, title, description, default, nullable, example, deprecated, required)
    }
}
