package io.github.amichne.kast.symbol.intellij

import io.github.amichne.kast.symbol.contract.SymbolDiscoveryRequest

private const val DISCOVERY_NANOS_PER_MILLISECOND = 1_000_000L

internal fun SymbolDiscoveryRequest.elapsedExpired(elapsedNanos: Long): Boolean =
    !budget.resources.hasTimeRemaining(elapsedNanos / DISCOVERY_NANOS_PER_MILLISECOND)
