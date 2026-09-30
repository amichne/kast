package io.github.amichne.kast.symbol.intellij

import io.github.amichne.kast.symbol.contract.SymbolDiscoveryRequest

/** The execution allowance survives a restarted read action; every attempt's collected facts do not. */
internal class IntellijDeclarationDiscoveryAllowance(
    private val request: SymbolDiscoveryRequest,
    private val clock: IntellijReadNanoClock = SystemIntellijDiscoveryNanoClock,
) {
    private val started = clock.now()
    var consumedWork = 0L
        private set

    fun now(): Long = clock.now()

    fun expired(): Boolean =
        clock.now() - started >= request.budget.resources.elapsedTimeLimit.value * NANOSECONDS_PER_MILLISECOND

    fun consume(): Boolean {
        if (consumedWork >= request.budget.resources.workUnitLimit.value) return false
        consumedWork++
        return true
    }
}

private const val NANOSECONDS_PER_MILLISECOND = 1_000_000L
