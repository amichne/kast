package io.github.amichne.kast.workspace.intellij.read

import io.github.amichne.kast.symbol.contract.SymbolDiscoveryElapsedNanoseconds
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryOutcome
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryWorkCount

/** One produced page, with admitted measurements and no source or request payload. */
class IntellijDiscoveryPageMeasurements
private constructor(
    val outcome: IntellijDiscoveryPageOutcome,
    val examinedWorkUnits: SymbolDiscoveryWorkCount,
    val inventoryFiles: SymbolDiscoveryWorkCount,
    val reacquiredFiles: SymbolDiscoveryWorkCount,
    val examinedLeaves: SymbolDiscoveryWorkCount,
    val inventoryNanoseconds: SymbolDiscoveryElapsedNanoseconds,
    val declarationScanNanoseconds: SymbolDiscoveryElapsedNanoseconds,
    val projectionNanoseconds: SymbolDiscoveryElapsedNanoseconds,
) {
    companion object {
        fun from(outcome: SymbolDiscoveryOutcome): IntellijDiscoveryPageMeasurements {
            val (batch, kind) =
                when (outcome) {
                    is SymbolDiscoveryOutcome.Complete -> outcome.batch to IntellijDiscoveryPageOutcome.COMPLETE
                    is SymbolDiscoveryOutcome.Qualified -> outcome.batch to IntellijDiscoveryPageOutcome.QUALIFIED
                }
            return IntellijDiscoveryPageMeasurements(
                kind,
                batch.examinedWorkUnits,
                batch.measurements.inventoryFiles,
                batch.measurements.reacquiredFiles,
                batch.measurements.examinedLeaves,
                batch.timings.inventory,
                batch.timings.declarationScan,
                batch.timings.projection,
            )
        }
    }
}

enum class IntellijDiscoveryPageOutcome {
    COMPLETE,
    QUALIFIED,
}

enum class IntellijDiscoveryPagePublication {
    PUBLISHED,
    SUPPRESSED,
    FAILED,
    INACTIVE,
    UNOBSERVED,
}
