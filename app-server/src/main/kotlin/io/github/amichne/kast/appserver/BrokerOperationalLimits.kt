package io.github.amichne.kast.appserver

import io.github.amichne.kast.kernel.ElapsedTimeLimitMillis
import io.github.amichne.kast.protocol.registry.OperationExecutionBudget

/** Fixed broker ingress limits; inspection projects these exact runtime authorities. */
object BrokerOperationalLimits {
    const val maximumMessageBytes: Int = 64 * 1_024 * 1_024
    const val maximumConnections: Int = 8
    val upstreamStartup: ElapsedTimeLimitMillis get() = OperationExecutionBudget.LOCAL_QUALIFICATION
}
