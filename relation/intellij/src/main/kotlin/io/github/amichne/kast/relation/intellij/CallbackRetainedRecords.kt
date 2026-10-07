package io.github.amichne.kast.relation.intellij

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.CallbackInvocationFlowCause
import java.util.IdentityHashMap

/** Equality is insufficient: each grant retains its own successful record admissions. */
internal class CallbackRetainedRecords<V>(
    private val retention: CallbackFlowRetention,
    private val footprint: (V) -> Long,
) {
    private val records = IdentityHashMap<V, Unit>()

    fun owns(value: V): Boolean = records.containsKey(value)

    fun admit(value: V): Refinement<Unit, CallbackInvocationFlowCause> =
        when (val admitted = retention.admit(footprint(value))) {
            is Refinement.Refined -> {
                records[value] = Unit
                admitted
            }
            is Refinement.Rejected -> admitted
        }
}
