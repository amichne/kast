package io.amichne.konditional.core.dsl.rules.targeting.scopes

import io.amichne.konditional.context.Context
import io.amichne.konditional.context.axis.Axis
import io.amichne.konditional.context.axis.AxisValue
import io.amichne.konditional.core.dsl.KonditionalDsl
import io.amichne.konditional.core.dsl.VariantDispatchHost

/**
 * Targeting mix-in for axis constraints in rules.
 *
 * Use [constrain] to express axis-based targeting with type inference.
 */
@KonditionalDsl
interface AxisTargetingScope<C : Context>

/**
 * Constrains rule targeting to the specified axis values.
 *
 * Expresses that the rule should only match contexts aligned along these axis values.
 * The axis is automatically derived from the enum type parameter.
 *
 * ## Usage
 *
 * ```kotlin
 * enable {
 *     constrain(Environment.PROD, Environment.STAGE)
 *     constrain(Tenant.ENTERPRISE)
 * }
 * ```
 *
 * ## Semantics
 *
 * - The axis is derived via `Axis.of<V>()` from the enum type.
 * - Multiple calls for the same axis widen allowed values with OR semantics within that axis.
 * - Multiple calls for different axes compose with AND semantics across axes.
 * - Requires at least one value (`first` parameter) for non-empty guarantee.
 *
 * @param first First value to constrain (required for non-empty guarantee)
 * @param rest Additional values to constrain
 */
inline fun <C : Context, reified V> AxisTargetingScope<C>.constrain(
    first: V,
    vararg rest: V,
) where V : AxisValue<V>, V : Enum<V> {
    val derivedAxis = Axis.of<V>()
    val host = this as? VariantDispatchHost
        ?: error("Unsupported AxisTargetingScope receiver: ${this::class.qualifiedName}")
    host.onAxisSelection(derivedAxis, linkedSetOf(first, *rest))
}
