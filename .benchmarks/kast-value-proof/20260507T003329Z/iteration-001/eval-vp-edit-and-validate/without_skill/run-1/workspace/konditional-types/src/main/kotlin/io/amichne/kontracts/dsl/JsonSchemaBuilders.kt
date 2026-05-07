package io.amichne.kontracts.dsl

import io.amichne.kontracts.schema.JsonSchema
import io.amichne.kontracts.schema.ObjectSchema
import io.amichne.kontracts.schema.RefSchema
import kotlin.reflect.KProperty0

// ========== Type-inferred DSL for automatic schema type resolution ==========

@PublishedApi
internal fun RootObjectSchemaBuilder.registerField(
    name: String,
    schema: JsonSchema<*>,
    required: Boolean,
) {
    if (required) {
        required(
            name = name,
            schema = schema,
            description = schema.description,
            defaultValue = schema.default,
            deprecated = schema.deprecated,
        )
    } else {
        optional(
            name = name,
            schema = schema,
            description = schema.description,
            defaultValue = schema.default,
            deprecated = schema.deprecated,
        )
    }
}

context(root: RootObjectSchemaBuilder)
@PublishedApi
@JsonSchemaBuilderDsl
internal inline fun <B : JsonSchemaBuilder<*>> KProperty0<*>.registerSchema(
    builder: B,
    required: Boolean,
    configure: B.() -> Unit,
) {
    val schema = builder.apply(configure).build()
    root.registerField(name, schema, required)
}

/**
 * String property schema builder with automatic type inference.
 * Provides access to string-specific attributes without needing to call string(...).
 */
context(root: RootObjectSchemaBuilder)
@JvmName("ofString")
inline infix fun KProperty0<String>.of(
    @JsonSchemaBuilderDsl
    builder: StringSchemaBuilder.() -> Unit = {},
) {
    registerSchema(
        builder = StringSchemaBuilder(),
        required = !returnType.isMarkedNullable,
        configure = builder,
    )
}

/**
 * Nullable String property schema builder.
 */
context(root: RootObjectSchemaBuilder)
@JvmName("ofNullableString")
inline infix fun KProperty0<String?>.of(
    builder: StringSchemaBuilder.() -> Unit = {},
) {
    registerSchema(builder = StringSchemaBuilder(), required = false) {
        nullable = true
        builder()
    }
}

/**
 * Boolean property schema builder with automatic type inference.
 */
context(root: RootObjectSchemaBuilder)
@JvmName("ofBoolean")
inline infix fun KProperty0<Boolean>.of(
    builder: BooleanSchemaBuilder.() -> Unit = {},
) {
    registerSchema(
        builder = BooleanSchemaBuilder(),
        required = !returnType.isMarkedNullable,
        configure = builder,
    )
}

/**
 * Nullable Boolean property schema builder.
 */
context(root: RootObjectSchemaBuilder)
@JvmName("ofNullableBoolean")
inline infix fun KProperty0<Boolean?>.of(
    builder: BooleanSchemaBuilder.() -> Unit = {},
) {
    registerSchema(builder = BooleanSchemaBuilder(), required = false) {
        nullable = true
        builder()
    }
}

/**
 * Int property schema builder with automatic type inference.
 */
context(root: RootObjectSchemaBuilder)
@JvmName("ofInt")
inline infix fun KProperty0<Int>.of(
    builder: IntSchemaBuilder.() -> Unit = {},
) {
    registerSchema(
        builder = IntSchemaBuilder(),
        required = !returnType.isMarkedNullable,
        configure = builder,
    )
}

/**
 * Nullable Int property schema builder.
 */
context(root: RootObjectSchemaBuilder)
@JvmName("ofNullableInt")
inline infix fun KProperty0<Int?>.of(
    builder: IntSchemaBuilder.() -> Unit = {},
) {
    registerSchema(builder = IntSchemaBuilder(), required = false) {
        nullable = true
        builder()
    }
}

/**
 * Double property schema builder with automatic type inference.
 */
context(root: RootObjectSchemaBuilder)
@JvmName("ofDouble")
inline infix fun KProperty0<Double>.of(
    builder: DoubleSchemaBuilder.() -> Unit = {},
) {
    registerSchema(
        builder = DoubleSchemaBuilder(),
        required = !returnType.isMarkedNullable,
        configure = builder,
    )
}

/**
 * Nullable Double property schema builder.
 */
context(root: RootObjectSchemaBuilder)
@JvmName("ofNullableDouble")
inline infix fun KProperty0<Double?>.of(
    builder: DoubleSchemaBuilder.() -> Unit = {},
) {
    registerSchema(builder = DoubleSchemaBuilder(), required = false) {
        nullable = true
        builder()
    }
}

/**
 * Generic object property schema builder (fallback for complex types).
 * Use this for nested objects or when you need explicit object schema.
 */
context(root: RootObjectSchemaBuilder)
@JvmName("ofObject")
inline infix fun <reified V : Any> KProperty0<V>.of(
    builder: ObjectSchemaBuilder.() -> Unit,
) {
    registerSchema(
        builder = ObjectSchemaBuilder(),
        required = !returnType.isMarkedNullable,
        configure = builder,
    )
}

/**
 * Nullable object property schema builder (fallback for complex nullable types).
 */
context(root: RootObjectSchemaBuilder)
@JvmName("ofNullableObject")
inline infix fun <reified V : Any> KProperty0<V?>.of(
    builder: ObjectSchemaBuilder.() -> Unit = {},
) {
    registerSchema(builder = ObjectSchemaBuilder(), required = false) {
        nullable = true
        builder()
    }
}

fun schema(builder: RootObjectSchemaBuilder.() -> Unit): ObjectSchema =
    RootObjectSchemaBuilder().apply(builder).build()

fun schemaRef(ref: String): JsonSchema<Any> = RefSchema(ref = ref)
