package io.github.amichne.kast.symbol.intellij

import io.github.amichne.kast.symbol.contract.SymbolDiscoveryRequest

@JvmInline
internal value class IntellijDiscoveryElapsedLimitNanoseconds private constructor(
    val value: Long,
) {
    companion object {
        /**
         * Proof transition:
         * `SymbolDiscoveryRequest -> IntellijDiscoveryElapsedLimitNanoseconds`.
         *
         * Establishes the request's non-negative elapsed limit in saturated nanoseconds. Raw
         * nanoseconds may be extracted only at the monotonic-clock comparison boundary.
         */
        fun from(request: SymbolDiscoveryRequest): IntellijDiscoveryElapsedLimitNanoseconds {
            val millis = request.budget.resources.elapsedTimeLimit.value
            val nanoseconds = if (millis > Long.MAX_VALUE / NANOS_PER_MILLISECOND) {
                Long.MAX_VALUE
            } else {
                millis * NANOS_PER_MILLISECOND
            }
            return IntellijDiscoveryElapsedLimitNanoseconds(nanoseconds)
        }
    }
}

internal fun SymbolDiscoveryRequest.elapsedLimitNanoseconds():
    IntellijDiscoveryElapsedLimitNanoseconds =
    IntellijDiscoveryElapsedLimitNanoseconds.from(this)

private const val NANOS_PER_MILLISECOND = 1_000_000L
