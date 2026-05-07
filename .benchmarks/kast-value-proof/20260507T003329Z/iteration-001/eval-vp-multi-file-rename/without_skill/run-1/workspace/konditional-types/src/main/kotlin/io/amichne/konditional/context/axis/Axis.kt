package io.amichne.konditional.context.axis

import io.amichne.konditional.context.axis.Axis.Companion.of
import kotlin.reflect.KClass

/**
 * Describes an axis along which values can vary (e.g., "environment", "region", "tenant").
 *
 * An Axis is a descriptor for a dimension of variation in your system. It pairs with
 * an enum type T that implements [AxisValue] to define the possible values along that axis.
 *
 * ## Usage
 *
 * Define an axis using the factory in [Axis.Companion]:
 * ```kotlin
 * enum class Environment : AxisValue<Environment> { PROD, STAGE, DEV }
 *
 * object Axes {
 *     val environment = Axis.of<Environment>()
 * }
 * ```
 *
 * The axis id is derived from the fully-qualified class name of [T]. To use a stable custom id
 * instead — for example when the class may be relocated across packages — annotate the enum with
 * [KonditionalExplicitId]:
 * ```kotlin
 * @KonditionalExplicitId("environment")
 * enum class Environment : AxisValue<Environment> { PROD, STAGE, DEV }
 * ```
 *
 * @param T The enum type that represents values along this axis.
 * @param valueClass The runtime class of the value type [T].
 *      This is intentionally passed explicitly to avoid fragile reflection-based extraction from generic supertypes.
 * @property id A stable, unique identifier for this axis derived from [T]'s FQCN or [KonditionalExplicitId].
 * @property key A typed [AxisKey] wrapping [id] for use in type-safe APIs.
 */
class Axis<T> private constructor(
    val id: String,
    val valueClass: KClass<out AxisValue<T>>,
) where T : AxisValue<T>, T : Enum<T> {
    /** Typed key for this axis. Prefer [key] over [id] in new code. */
    val key: AxisKey get() = AxisKey(id)
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Axis<*>) return false
        return id == other.id && valueClass == other.valueClass
    }

    override fun hashCode(): Int = 31 * id.hashCode() + valueClass.hashCode()

    override fun toString(): String = "Axis(id='$id', valueClass=${valueClass.simpleName})"

    companion object {
        private fun deriveId(valueClass: KClass<*>): String =
            valueClass.java.getAnnotation(KonditionalExplicitId::class.java)?.id
                ?: valueClass.qualifiedName
                ?: error(
                    "Cannot derive axis id for ${valueClass.simpleName}: class has no qualified name. " +
                        "Apply @KonditionalExplicitId to set an explicit id.",
                )

        /**
         * Creates a new axis handle whose id is derived from [valueClass]'s fully-qualified name,
         * or from a [KonditionalExplicitId] annotation if present.
         */
        fun <T> of(valueClass: KClass<out AxisValue<T>>): Axis<T> where T : AxisValue<T>, T : Enum<T> =
            Axis(id = deriveId(valueClass), valueClass = valueClass)

        /**
         * Reified helper for [of].
         */
        inline fun <reified T> of(): Axis<T> where T : AxisValue<T>, T : Enum<T> =
            of(valueClass = T::class)
    }
}
