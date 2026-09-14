package io.github.amichne.kast.symbol.intellij

internal class IntellijDiscoveryStepClock(private val step: Long = 100L) : IntellijDiscoveryNanoClock {
    private var current = 0L

    override fun now(): Long = current.also { current += step }
}
