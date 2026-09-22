package io.github.amichne.kast.appserver.runtime

import io.github.amichne.kast.appserver.BrokerOperationalLimits
import io.github.amichne.kast.kernel.Refinement

/** A finite in-memory allowance; zero disables retention or admission without deleting durable history. */
@JvmInline
internal value class InvocationCapacityLimit private constructor(val value: Int) {
    companion object {
        val Default = InvocationCapacityLimit(BrokerOperationalLimits.maximumInvocations)

        fun admit(value: Int): Refinement<InvocationCapacityLimit, InvocationFenceFailure> =
            if (value in 0..BrokerOperationalLimits.maximumInvocations)
                Refinement.Refined(InvocationCapacityLimit(value))
            else Refinement.Rejected(InvocationFenceFailure.LIMIT_REJECTED)
    }
}
