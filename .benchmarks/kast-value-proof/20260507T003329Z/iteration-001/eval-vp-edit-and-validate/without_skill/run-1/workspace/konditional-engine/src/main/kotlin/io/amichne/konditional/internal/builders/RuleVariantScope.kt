package io.amichne.konditional.internal.builders

import io.amichne.konditional.context.Context
import io.amichne.konditional.context.axis.Axis
import io.amichne.konditional.context.axis.AxisValue
import io.amichne.konditional.core.dsl.VariantDispatchHost
import io.amichne.konditional.rules.targeting.Targeting

/**
 * Internal variant dispatcher for rule-style targeting builders.
 *
 * Repeated writes to the same axis merge with OR semantics within that axis.
 */
internal class RuleVariantScope<C : Context>(
    private val leaves: MutableList<Targeting<C>>,
) : VariantDispatchHost {
    override fun <V> onAxisSelection(
        axis: Axis<V>,
        values: Set<V>,
    ) where V : AxisValue<V>, V : Enum<V> {
        val allowedIds = values.mapTo(linkedSetOf()) { it.id }
        val idx = leaves.indexOfFirst { it is Targeting.Axis && it.axisId == axis.id }
        if (idx >= 0) {
            val existing = leaves[idx] as Targeting.Axis
            leaves[idx] = existing.copy(allowedIds = existing.allowedIds + allowedIds)
        } else {
            leaves += Targeting.Axis(axis.id, allowedIds)
        }
    }
}
