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

    fun expired(): Boolean = request.elapsedExpired(clock.now() - started)

    fun consume(): Boolean {
        if (!request.budget.resources.hasWorkCapacity(consumedWork)) return false
        consumedWork++
        return true
    }
}
