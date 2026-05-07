package io.amichne.konditional.context.axis

import kotlin.reflect.KClass

/**
 * A value that exists along some axis (environment, region, tenant, etc.).
 *
 * This interface represents a specific value within a dimensional axis. For example,
 * if `Environment` is an axis, then `PROD`, `STAGE`, and `DEV` would be AxisValue
 * implementations.
 *
 * ## Usage
 *
 * Define an enum that implements this interface:
 * ```kotlin
 * enum class Environment : AxisValue<Environment> {
 *     PROD,
 *     STAGE,
 *     DEV,
 * }
 * ```
 *
 * By default, `id` is the enum entry's [Enum.name]. Override it only when you need a
 * different stable wire identifier.
 *
 * The `id` field must be stable and unique within the axis, as it's used for:
 * - Serialization and deserialization
 * - Rule matching and evaluation
 * - Storage and retrieval
 *
 * Each enum type that implements [AxisValue] must have exactly one [Axis] registered.
 *
 * @property id A stable, unique identifier for this value within its axis
 */
interface AxisValue<T> where T : Enum<T>, T : AxisValue<T> {
    val id: String
        get() = (this as Enum<*>).name

    val axis: Axis<T>
        get() = Axis.fromValueClass(this::class)

    operator fun getValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>): Axis<T> = axis
}

private fun <T> Axis.Companion.fromValueClass(
    enumClass: KClass<out AxisValue<T>>
): Axis<T> where T : AxisValue<T>, T : Enum<T> = of(enumClass)
