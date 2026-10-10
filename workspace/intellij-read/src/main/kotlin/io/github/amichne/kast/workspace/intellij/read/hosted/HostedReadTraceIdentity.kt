package io.github.amichne.kast.workspace.intellij.read.hosted

import java.util.UUID

/** Diagnostic ownership only; observing this identity grants no semantic admission or freshness authority. */
class HostedReadTraceIdentity private constructor(val value: UUID) {
    companion object {
        fun fromBoundary(value: UUID): HostedReadTraceIdentity = HostedReadTraceIdentity(value)
    }
}

/** Optional diagnostic export remains separate from read authority. */
sealed interface HostedReadTraceObservation {
    data object Unobserved : HostedReadTraceObservation

    data class Observed(val identity: HostedReadTraceIdentity) : HostedReadTraceObservation
}
