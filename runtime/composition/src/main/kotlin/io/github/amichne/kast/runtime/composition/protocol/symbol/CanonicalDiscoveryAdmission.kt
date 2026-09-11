package io.github.amichne.kast.runtime.composition.protocol

import io.github.amichne.kast.kernel.*
import io.github.amichne.kast.symbol.contract.*

internal fun installedDiscoveryProtocolBudget(limit: Int): SymbolDiscoveryBudget =
    SymbolDiscoveryBudget(
        ResourceBudget(
            ResultLimit.parse(limit).value(),
            WorkUnitLimit.parse(100_000).value(),
            ElapsedTimeLimitMillis.parse(30_000).value(),
        ),
        SymbolDiscoveryByteLimit.parse(1_048_576).value(),
    )

private fun <Value, Failure> Refinement<Value, Failure>.value(): Value =
    when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> error("Fixed installed discovery budget is invalid")
    }
