package io.amichne.konditional.core.features

import io.amichne.konditional.context.Context
import io.amichne.konditional.core.Namespace
import io.amichne.konditional.values.FeatureId

/**
 * Represents a feature flag that can be used to enable or disable specific functionality
 * in an application based on a given state or condition.
 *
 * Features are **type-bound** to their [io.amichne.konditional.core.Namespace], providing compile-time isolation between teams.
 * Each feature can only be defined and configured within its designated namespace.
 *
 * Supports:
 * - Primitives: Boolean, String, Int, Double
 * - Enums: `E : Enum<E>`
 * - Custom structured types: [io.amichne.konditional.core.types.Konstrained]
 *
 * ## Example
 *
 * ```kotlin
 * object Payments : Namespace("payments")
 *
 * object Payments : Namespace("payments") {
 *     val APPLE_PAY by boolean<Context>(default = false)
 * }
 * ```
 *
 * Note: [Feature] is a sealed API. Consumer code defines flags via namespace property delegation rather than
 * implementing [Feature] directly.
 *
 * @param T The actual value type.
 * @param C The type create the context that the feature evaluates against.
 * @param M The namespace this feature belongs to (compile-time binding).
 */
sealed interface Feature<T : Any, C : Context, out M : Namespace> : Identifiable.ById {
    val key: String
    val namespace: M

    override val id: FeatureId
}
