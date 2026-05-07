package io.amichne.kontracts.dsl

import io.amichne.kontracts.schema.ArraySchema
import io.amichne.kontracts.schema.JsonSchema
import kotlin.reflect.KClass

fun stringSchema(builder: StringSchemaBuilder.() -> Unit = {}): JsonSchema<String> =
    StringSchemaBuilder().apply(builder).build()

fun booleanSchema(builder: BooleanSchemaBuilder.() -> Unit = {}): JsonSchema<Boolean> =
    BooleanSchemaBuilder().apply(builder).build()

fun intSchema(builder: IntSchemaBuilder.() -> Unit = {}): JsonSchema<Int> =
    IntSchemaBuilder().apply(builder).build()

fun doubleSchema(builder: DoubleSchemaBuilder.() -> Unit = {}): JsonSchema<Double> =
    DoubleSchemaBuilder().apply(builder).build()

fun nullSchema(builder: NullSchemaBuilder.() -> Unit = {}): JsonSchema<Any> =
    NullSchemaBuilder().apply(builder).build()

inline fun <reified E : Any> arraySchema(builder: ArraySchemaBuilder<E>.() -> Unit): ArraySchema<E> =
    ArraySchemaBuilder<E>().apply(builder).build()

fun <E : Enum<E>> enumSchema(
    enumClass: KClass<E>,
    builder: EnumSchemaBuilder<E>.() -> Unit = {},
): JsonSchema<E> = EnumSchemaBuilder(enumClass).apply(builder).build()
