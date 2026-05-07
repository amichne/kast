package io.amichne.konditional.core.types

/**
 * Marker hierarchy for custom feature values supported by Konditional.
 *
 * Implement this interface when a feature value is not one of the built-in primitives or an enum.
 * The serialization layer recognizes the nested sub-interfaces below and applies the corresponding
 * object, primitive, array, or adapted codec path.
 *
 * ## Supported shapes
 *
 * ### Object-backed values
 * Use [Object] for multi-field structures or Kotlin `object` singletons.
 * ```kotlin
 * data class UserSettings(
 *     val theme: String = "light",
 *     val notificationsEnabled: Boolean = true,
 * ) : Konstrained.Object
 * ```
 *
 * ### Primitive-backed values
 * Use [Primitive] wrappers when the trusted domain value is itself a JSON primitive.
 * `@JvmInline value class` is the idiomatic shape because it enforces a single underlying property.
 * ```kotlin
 * @JvmInline
 * value class Email(override val value: String) : Konstrained.Primitive.String
 *
 * @JvmInline
 * value class RetryCount(override val value: Int) : Konstrained.Primitive.Int
 * ```
 *
 * ### Array-backed values
 * Use [Array] when the trusted value is a single list.
 * ```kotlin
 * @JvmInline
 * value class Tags(override val values: List<String>) : Konstrained.Array<String>
 * ```
 *
 * ### Adapted values
 * Use [AsString], [AsInt], [AsBoolean], or [AsDouble] when the domain value is not itself a JSON
 * primitive but has a deterministic primitive wire representation.
 *
 * ```kotlin
 * @JvmInline
 * value class ExpirationDate(val value: java.time.LocalDate) :
 *     Konstrained.AsString<java.time.LocalDate, ExpirationDate> {
 *     override fun encode(): String = value.toString()
 *     override fun decode(raw: String): ExpirationDate = ExpirationDate(java.time.LocalDate.parse(raw))
 * }
 * ```
 *
 * ## Invariants
 * - Primitive and array-backed implementations must expose exactly one runtime value property matching
 *   the expected wire shape. Violations fail during serialization with a descriptive error.
 * - [AsString], [AsInt], [AsBoolean], and [AsDouble] encoders and decoders must be pure and
 *   deterministic. `decode(encode(x))` must reconstruct `x`.
 * - External values remain untrusted until they pass through the serialization boundary.
 */
sealed interface Konstrained {
    sealed interface Primitive<V> : Konstrained {
        /**
         * The single underlying value of this primitive type.
         *
         * Must be the only value-bearing property of the implementing class, and its type must match
         * the primitive wire representation.
         */
        val value: V

        interface Int : Primitive<kotlin.Int>
        interface String : Primitive<kotlin.String>
        interface Boolean : Primitive<kotlin.Boolean>
        interface Double : Primitive<kotlin.Double>
    }

    interface Object : Konstrained

    interface Array<E> : Konstrained {
        /**
         * The list of values in this array type.
         *
         * Must be the only value-bearing property of the implementing class.
         */
        val values: List<E>
    }

    /**
     * Converts a domain value [T] to its JSON-primitive wire representation [P].
     *
     * Implement as a standalone `object` or `fun interface` lambda to share encoding logic across
     * multiple adapted [Konstrained] types.
     */
    fun interface Encoder<T : Any, P : Any> {
        fun encode(value: T): P
    }

    /**
     * Reconstructs a [V] instance from a raw JSON-primitive value [P].
     *
     * Implement as a standalone `object` or on a companion object to enable serialization-time
     * discovery for adapted [Konstrained] types.
     */
    fun interface Decoder<P : Any, out V : Any> {
        fun decode(raw: P): V
    }

    /**
     * Marker for a type whose domain value [T] is serialized as a JSON string.
     *
     * [encode] and [decode] must be pure, deterministic, and left-inverse of each other.
     */
    interface AsString<T : Any, V : AsString<T, V>> : Konstrained {
        val value: T

        fun encode(): kotlin.String

        fun decode(raw: kotlin.String): V
    }

    /**
     * Marker for a type whose domain value [T] is serialized as a JSON integer.
     */
    interface AsInt<T : Any, V : AsInt<T, V>> : Konstrained {
        val value: T

        fun encode(): kotlin.Int

        fun decode(raw: kotlin.Int): V
    }

    /**
     * Marker for a type whose domain value [T] is serialized as a JSON boolean.
     */
    interface AsBoolean<T : Any, V : AsBoolean<T, V>> : Konstrained {
        val value: T

        fun encode(): kotlin.Boolean

        fun decode(raw: kotlin.Boolean): V
    }

    /**
     * Marker for a type whose domain value [T] is serialized as a JSON number.
     */
    interface AsDouble<T : Any, V : AsDouble<T, V>> : Konstrained {
        val value: T

        fun encode(): kotlin.Double

        fun decode(raw: kotlin.Double): V
    }
}
