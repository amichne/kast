package io.amichne.konditional.core.dsl

import io.amichne.konditional.context.axis.Axis
import io.amichne.konditional.context.axis.AxisValue

@PublishedApi
internal interface VariantDispatchHost {
    fun <V> onAxisSelection(
        axis: Axis<V>,
        values: Set<V>,
    ) where V : AxisValue<V>, V : Enum<V>
}
