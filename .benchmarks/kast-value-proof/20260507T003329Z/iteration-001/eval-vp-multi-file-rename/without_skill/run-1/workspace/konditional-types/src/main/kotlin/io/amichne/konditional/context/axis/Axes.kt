package io.amichne.konditional.context.axis

import java.util.function.IntFunction

/**
 * Strongly-typed container for a set of axis values.
 *
 * This class holds a snapshot of values across multiple axes, providing type-safe
 * access to dimension values. It's typically used within a [io.amichne.konditional.context.Context]
 * to represent the dimensional coordinates of an execution context.
 *
 * ## Usage
 *
 * Access values by axis:
 * ```kotlin
 * val values = axes(Environment.PROD, Tenant.ENTERPRISE)
 * val environment: Set<Environment> = values[Axis.of<Environment>()]
 * val tenant: Set<Tenant> = values[Axis.of<Tenant>()]
 * ```
 *
 * ## Immutability
 *
 * Axes instances are immutable. Once constructed, their contents cannot be changed.
 *
 * @property values Internal map of axis IDs to their values
 */
@Suppress("TooManyFunctions")
class Axes internal constructor(
    private val values: Map<String, Set<AxisValue<*>>>,
) : Set<AxisValue<*>> by values.values.flatten().toSet() {
    /**
     * Low-level access by axis ID.
     *
     * This is used internally by the rule evaluation engine. Prefer the type-safe
     * [get] method for application code.
     *
     * @param axisId The unique identifier create the axis
     * @return The values for that axis, or empty if not present
     */
    internal operator fun get(axisId: String): Set<AxisValue<*>> = values[axisId].orEmpty()

    /**
     * Type-safe access by axis descriptor or AxisValue's axis property.
     *
     * Retrieves the value for the given axis or the axis associated with an AxisValue.
     *
     * @param axis The axis descriptor or an AxisValue's axis property
     * @return The values for that axis, or empty if not present
     */
    @Suppress("UNCHECKED_CAST")
    operator fun <T> get(axis: Axis<T>): Set<T> where T : AxisValue<T>, T : Enum<T> =
        values[axis.id].orEmpty()
            .mapNotNull { it as? T }
            .toSet()

    operator fun <T> get(axisValue: AxisValue<T>): Set<T> where T : AxisValue<T>, T : Enum<T> = get(axisValue.axis)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Axes) return false
        return values == other.values
    }

    override fun hashCode(): Int = values.hashCode()

    override fun toString(): String {
        return "Axes(${
            values.entries.joinToString { entry ->
                "${entry.key}=${entry.value.joinToString(prefix = "[", postfix = "]") { it.id }}"
            }
        })"
    }

    override fun isEmpty(): Boolean = values.values.flatten().toSet().isEmpty()

    override fun contains(element: AxisValue<*>): Boolean = values.values.flatten().toSet().contains(element)

    override fun containsAll(
        elements: Collection<AxisValue<*>>
    ): Boolean = values.values.flatten().toSet().containsAll(elements)

    override fun iterator(): Iterator<AxisValue<*>> = values.values.flatten().toSet().iterator()

    @Suppress("OVERRIDE_DEPRECATION", "RedundantOverride", "Deprecated")
    override fun <T> toArray(generator: IntFunction<Array<out T?>?>): Array<out T?>? {
        return super.toArray(generator)
    }

    companion object {
        /**
         * An empty Axes instance with no values set.
         *
         * Use this as a default when no axis values are needed.
         */
        val EMPTY = Axes(emptyMap())
    }
}

/**
 * Creates an Axes instance from the given axis values.
 *
 * Values are automatically grouped by their axis.
 *
 * ## Usage
 *
 * ```kotlin
 * override val axes = axes(Environment.PROD, Tenant.ENTERPRISE)
 * ```
 *
 * @param first First axis value (required for non-empty guarantee)
 * @param rest Additional axis values (can be from different axes)
 */
fun axes(
    first: AxisValue<*>,
    vararg rest: AxisValue<*>,
): Axes {
    val grouped = (listOf(first) + rest)
        .groupBy { it.axis.id }
        .mapValues { it.value.toSet() }
    return Axes(grouped)
}
