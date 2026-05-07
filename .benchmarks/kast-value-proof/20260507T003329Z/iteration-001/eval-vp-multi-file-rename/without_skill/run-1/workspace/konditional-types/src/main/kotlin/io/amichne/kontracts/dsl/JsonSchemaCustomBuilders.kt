package io.amichne.kontracts.dsl

import io.amichne.kontracts.dsl.custom.CustomBooleanSchemaBuilder
import io.amichne.kontracts.dsl.custom.CustomDoubleSchemaBuilder
import io.amichne.kontracts.dsl.custom.CustomIntSchemaBuilder
import io.amichne.kontracts.dsl.custom.CustomStringSchemaBuilder
import kotlin.reflect.KProperty0

context(root: RootObjectSchemaBuilder)
@PublishedApi
@JsonSchemaBuilderDsl
internal inline fun <B : JsonSchemaBuilder<Any>> KProperty0<*>.registerCustomSchema(
    builder: B,
    required: Boolean,
    configure: B.() -> Unit,
) {
    val schema = builder.apply(configure).build()
    root.fields[name] = fieldSchema {
        this.schema = schema
        this.required = required
        this.defaultValue = schema.default
        this.description = schema.description
        this.deprecated = schema.deprecated
    }
}

/**
 * Maps a custom type property to a String schema representation.
 *
 * Example:
 * ```kotlin
 * data class UserId(val value: String)
 *
 * ::userId asString {
 *     represent { this.value }
 *     pattern = "[A-Z0-9]+"
 *     minLength = 8
 *     description = "Unique user identifier"
 * }
 * ```
 */
context(root: RootObjectSchemaBuilder)
@JvmName("asString")
@JsonSchemaBuilderDsl
inline infix fun <reified V : Any> KProperty0<V>.asString(
    builder: CustomStringSchemaBuilder<V>.() -> Unit,
) {
    registerCustomSchema(
        CustomStringSchemaBuilder(),
        required = !returnType.isMarkedNullable,
        configure = builder
    )
}

/**
 * Maps a nullable custom type property to a String schema representation.
 */
@Suppress("unused")
context(root: RootObjectSchemaBuilder)
@JvmName("asNullableString")
@JsonSchemaBuilderDsl
inline infix fun <reified V : Any> KProperty0<V?>.asString(
    builder: CustomStringSchemaBuilder<V>.() -> Unit,
) {
    registerCustomSchema(CustomStringSchemaBuilder<V>(), required = false) {
        nullable = true
        builder()
    }
}

/**
 * Maps a custom type property to an Int schema representation.
 *
 * Example:
 * ```kotlin
 * data class Count(val value: Int)
 *
 * ::count asInt {
 *     represent { this.value }
 *     minimum = 0
 *     maximum = 100
 * }
 * ```
 */
context(root: RootObjectSchemaBuilder)
@JvmName("asInt")
@JsonSchemaBuilderDsl
inline infix fun <reified V : Any> KProperty0<V>.asInt(
    builder: CustomIntSchemaBuilder<V>.() -> Unit,
) {
    registerCustomSchema(
        CustomIntSchemaBuilder(),
        required = !returnType.isMarkedNullable,
        configure = builder
    )
}

/**
 * Maps a nullable custom type property to an Int schema representation.
 */
@Suppress("unused")
context(root: RootObjectSchemaBuilder)
@JvmName("asNullableInt")
@JsonSchemaBuilderDsl
inline infix fun <reified V : Any> KProperty0<V?>.asInt(
    @JsonSchemaBuilderDsl builder: CustomIntSchemaBuilder<V>.() -> Unit,
) {
    registerCustomSchema(CustomIntSchemaBuilder<V>(), required = false) {
        nullable = true
        builder()
    }
}

/**
 * Maps a custom type property to a Boolean schema representation.
 *
 * Example:
 * ```kotlin
 * data class Flag(val enabled: Boolean)
 *
 * ::flag asBoolean {
 *     represent { this.enabled }
 *     description = "Feature flag state"
 * }
 * ```
 */
context(root: RootObjectSchemaBuilder)
@JvmName("asBoolean")
@JsonSchemaBuilderDsl
inline infix fun <reified V : Any> KProperty0<V>.asBoolean(
    builder: CustomBooleanSchemaBuilder<V>.() -> Unit,
) {
    registerCustomSchema(
        CustomBooleanSchemaBuilder(),
        required = !returnType.isMarkedNullable,
        configure = builder
    )
}

/**
 * Maps a nullable custom type property to a Boolean schema representation.
 */
@Suppress("unused")
context(root: RootObjectSchemaBuilder)
@JvmName("asNullableBoolean")
@JsonSchemaBuilderDsl
inline infix fun <reified V : Any> KProperty0<V?>.asBoolean(
    builder: CustomBooleanSchemaBuilder<V>.() -> Unit,
) {
    registerCustomSchema(CustomBooleanSchemaBuilder<V>(), required = false) {
        nullable = true
        builder()
    }
}

/**
 * Maps a custom type property to a Double schema representation.
 *
 * Example:
 * ```kotlin
 * data class Percentage(val value: Double)
 *
 * ::percentage asDouble {
 *     represent { this.value }
 *     minimum = 0.0
 *     maximum = 100.0
 *     format = "double"
 * }
 * ```
 */
context(root: RootObjectSchemaBuilder)
@JvmName("asDouble")
@JsonSchemaBuilderDsl
inline infix fun <reified V : Any> KProperty0<V>.asDouble(
    builder: CustomDoubleSchemaBuilder<V>.() -> Unit,
) {
    registerCustomSchema(
        CustomDoubleSchemaBuilder(),
        required = !returnType.isMarkedNullable,
        configure = builder
    )
}

/**
 * Maps a nullable custom type property to a Double schema representation.
 */
@Suppress("unused")
context(root: RootObjectSchemaBuilder)
@JvmName("asNullableDouble")
@JsonSchemaBuilderDsl
inline infix fun <reified V : Any> KProperty0<V?>.asDouble(
    builder: CustomDoubleSchemaBuilder<V>.() -> Unit,
) {
    registerCustomSchema(CustomDoubleSchemaBuilder<V>(), required = false) {
        nullable = true
        builder()
    }
}
